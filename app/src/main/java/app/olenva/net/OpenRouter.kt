package app.olenva.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val contextLength: Int?,
    val inputModalities: List<String>,
    val promptPrice: Double?,      // USD per token
    val completionPrice: Double?,
    val supportedParameters: List<String>,
    val created: Long? = null,     // epoch seconds when OpenRouter listed it
    val outputModalities: List<String> = emptyList(),
    val maxOutput: Int? = null,    // longest reply the top provider allows
) {
    val supportsImages get() = "image" in inputModalities
    val supportsFiles get() = "file" in inputModalities
    val supportsText get() = inputModalities.isEmpty() || "text" in inputModalities
    fun supports(param: String) = param in supportedParameters
}

sealed interface StreamEvent {
    data class Delta(val content: String, val reasoning: String) : StreamEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val cost: Double?) : StreamEvent
    data class Finish(val reason: String) : StreamEvent
}

enum class ErrorKind { NETWORK, TIMEOUT, INVALID_KEY, NO_CREDITS, MODEL_UNAVAILABLE, RATE_LIMITED, BUSY, MODERATION, BAD_REQUEST, UNKNOWN }

/** [detail] is the provider's message with anything key-like scrubbed; safe to show. */
class ApiException(val kind: ErrorKind, val detail: String? = null) : IOException("$kind ${detail.orEmpty()}") {
    val retryable get() = kind in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.RATE_LIMITED, ErrorKind.BUSY, ErrorKind.UNKNOWN)
}

class OpenRouter(private val http: OkHttpClient = defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rawModels(): String = get("$BASE/models", null)

    fun parseModels(body: String): List<ModelInfo> =
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val id = o.str("id").orEmpty()
            val arch = o["architecture"] as? JsonObject
            val pricing = o["pricing"] as? JsonObject
            val name = o.str("name") ?: id
            ModelInfo(
                id = id,
                name = name.substringAfter(": ", name),
                provider = name.substringBefore(": ", id.substringBefore('/')),
                contextLength = o["context_length"]?.jsonPrimitive?.intOrNull,
                inputModalities = arch?.get("input_modalities")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                promptPrice = pricing?.str("prompt")?.toDoubleOrNull()?.takeIf { it >= 0 },
                completionPrice = pricing?.str("completion")?.toDoubleOrNull()?.takeIf { it >= 0 },
                supportedParameters = o["supported_parameters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                created = o["created"]?.jsonPrimitive?.longOrNull,
                outputModalities = arch?.get("output_modalities")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                maxOutput = (o["top_provider"] as? JsonObject)?.get("max_completion_tokens")?.jsonPrimitive?.intOrNull,
            )
        }.filter { it.id.isNotEmpty() }

    /** Returns the key's label on success. */
    suspend fun checkKey(key: String): String {
        val body = get("$BASE/key", key)
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject
        return data?.str("label").orEmpty()
    }

    suspend fun complete(key: String, body: JsonObject): String {
        val text = post(key, body)
        val choice = json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        return (choice?.get("message") as? JsonObject)?.str("content").orEmpty()
    }

    fun stream(key: String, body: JsonObject): Flow<StreamEvent> = callbackFlow {
        val call = http.newCall(request("$BASE/chat/completions", key, body.toString()))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(mapIo(e)) }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        if (!r.isSuccessful) throw httpError(r.code, r.body.string())
                        val source = r.body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue   // blank lines and ": OPENROUTER PROCESSING" keep-alives
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            parseChunk(data).forEach { trySendBlocking(it) }
                        }
                    }
                    close()
                } catch (e: IOException) {
                    close(if (e is ApiException) e else mapIo(e))
                } catch (e: Exception) {
                    close(ApiException(ErrorKind.UNKNOWN, e.javaClass.simpleName))
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun parseChunk(data: String): List<StreamEvent> {
        val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        (o["error"] as? JsonObject)?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull ?: 0
            throw httpError(code, o.toString())
        }
        val out = mutableListOf<StreamEvent>()
        val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = delta?.str("content").orEmpty()
        val reasoning = delta?.str("reasoning").orEmpty()
        if (content.isNotEmpty() || reasoning.isNotEmpty()) out += StreamEvent.Delta(content, reasoning)
        choice?.str("finish_reason")?.let { out += StreamEvent.Finish(it) }
        (o["usage"] as? JsonObject)?.let { u ->
            var cost = u["cost"]?.jsonPrimitive?.doubleOrNull
            // BYOK: OpenRouter bills ~0 and reports the provider's charge separately; both are reported values.
            if (u["is_byok"]?.jsonPrimitive?.contentOrNull == "true") {
                val upstream = (u["cost_details"] as? JsonObject)?.get("upstream_inference_cost")?.jsonPrimitive?.doubleOrNull
                if (upstream != null) cost = (cost ?: 0.0) + upstream
            }
            out += StreamEvent.Usage(
                u["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                u["completion_tokens"]?.jsonPrimitive?.intOrNull,
                cost,
            )
        }
        return out
    }

    private suspend fun get(url: String, key: String?): String = execute(request(url, key, null))
    private suspend fun post(key: String, body: JsonObject): String =
        execute(request("$BASE/chat/completions", key, body.toString()))

    private suspend fun execute(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(mapIo(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = runCatching { r.body.string() }.getOrDefault("")
                    if (r.isSuccessful) cont.resume(text) else cont.resumeWithException(httpError(r.code, text))
                }
            }
        })
    }

    private fun request(url: String, key: String?, body: String?): Request = Request.Builder().url(url).apply {
        key?.let { header("Authorization", "Bearer $it") }
        header("X-Title", "Olenva")
        if (body != null) post(body.toRequestBody("application/json".toMediaType()))
    }.build()

    private fun httpError(code: Int, body: String): ApiException {
        val msg = runCatching {
            (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.str("message")
        }.getOrNull()?.let(::scrub)?.take(300)
        val kind = when (code) {
            401 -> ErrorKind.INVALID_KEY
            402 -> ErrorKind.NO_CREDITS
            403 -> ErrorKind.MODERATION
            404 -> ErrorKind.MODEL_UNAVAILABLE
            408 -> ErrorKind.TIMEOUT
            429 -> ErrorKind.RATE_LIMITED
            400, 413, 422 -> ErrorKind.BAD_REQUEST
            in 500..599 -> ErrorKind.BUSY
            else -> ErrorKind.UNKNOWN
        }
        return ApiException(kind, msg)
    }

    private fun mapIo(e: IOException): ApiException = when (e) {
        is ApiException -> e
        is UnknownHostException -> ApiException(ErrorKind.NETWORK)
        is InterruptedIOException -> ApiException(ErrorKind.TIMEOUT)
        is SSLException -> ApiException(ErrorKind.NETWORK)
        else -> ApiException(ErrorKind.NETWORK, e.javaClass.simpleName)
    }

    companion object {
        const val BASE = "https://openrouter.ai/api/v1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)   // reasoning models can pause long between chunks
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        private val keyPattern = Regex("sk-[A-Za-z0-9_-]{8,}")
        fun scrub(s: String) = keyPattern.replace(s, "sk-…")
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonElement)?.let {
    runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
}
