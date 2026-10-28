package app.olenva.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.ConfigurationCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import app.olenva.R
import app.olenva.container
import app.olenva.data.AttachmentKind
import app.olenva.data.MessageEntity
import app.olenva.data.Status
import app.olenva.net.ModelInfo
import app.olenva.ui.LocalReading
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.NumberFormat
import java.util.Locale

private const val HOST = "https://appassets.androidplatform.net"

interface TranscriptCallbacks {
    fun onAction(action: String, messageId: String)
    fun onImage(messageId: String, index: Int)
    fun onMath(tex: String)
    fun onCopy(text: String)
    fun onOpenDrawer()
}

/** Renders messages in one WebView (markdown-it + KaTeX). Message actions come back through [callbacks]. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Transcript(
    messages: List<MessageEntity>,
    scrollSignal: Int,
    showUsage: Boolean,
    modelLookup: (String) -> ModelInfo?,
    callbacks: TranscriptCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reading = LocalReading.current
    val systemScale = LocalDensity.current.fontScale
    var ready by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(callbacks)
    val strings = remember(context.resources.configuration) { jsStrings(context) }

    val webView = remember {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(context, context.container.attachments.dir))
            .build()
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    openExternal(context, request.url.toString())
                    return true
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun ready() { post { ready = true } }
                @JavascriptInterface fun copy(text: String) { post { cb.onCopy(text) } }
                @JavascriptInterface fun action(act: String, id: String) { post { cb.onAction(act, id) } }
                @JavascriptInterface fun openImage(id: String, index: Int) { post { cb.onImage(id, index) } }
                @JavascriptInterface fun openLink(url: String) { post { openExternal(context, url) } }
                @JavascriptInterface fun onMath(tex: String) { post { cb.onMath(tex) } }
                @JavascriptInterface fun openDrawer() { post { cb.onOpenDrawer() } }
            }, "Android")
            loadUrl("$HOST/assets/web/chat.html" + if (reading.dark) "?dark=1" else "")
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    val lang = ConfigurationCompat.getLocales(context.resources.configuration)[0]?.toLanguageTag() ?: "zh-Hant"
    LaunchedEffect(ready, reading, systemScale, strings) {
        if (!ready) return@LaunchedEffect
        val o = buildJsonObject {
            put("dark", reading.dark)
            put("scale", reading.fontScale * systemScale)
            put("compact", reading.compact)
            put("lang", if (lang.startsWith("zh")) "zh-Hant" else lang)
            put("strings", strings)
        }
        webView.evaluateJavascript("Olenva.setAppearance($o)", null)
    }

    var lastSignal by remember { mutableStateOf(-1) }
    val json = remember(messages, showUsage, strings) { toJson(context, messages, showUsage, modelLookup) }
    LaunchedEffect(ready, json, scrollSignal, reading, strings) {
        if (!ready) return@LaunchedEffect
        val scroll = scrollSignal != lastSignal
        lastSignal = scrollSignal
        webView.evaluateJavascript("Olenva.setMessages($json, $scroll)", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in setOf("http", "https", "mailto")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Log.w("Transcript", "no app for link") }
}

private fun jsStrings(context: Context) = buildJsonObject {
    fun s(k: String, id: Int) = put(k, context.getString(id))
    s("copy", R.string.copy); s("copied", R.string.copied); s("edit", R.string.edit); s("more", R.string.more)
    s("regenerate", R.string.regenerate); s("retry", R.string.retry); s("thinking", R.string.thinking)
    s("thoughts", R.string.thoughts); s("jump", R.string.jump_latest)
}

private fun toJson(context: Context, list: List<MessageEntity>, showUsage: Boolean, lookup: (String) -> ModelInfo?) = buildJsonArray {
    for (m in list) add(buildJsonObject {
        put("id", m.id)
        put("role", m.role)
        put("content", m.content)
        put("reasoning", m.reasoning)
        put("status", m.status)
        m.error?.let { put("error", it) }
        notice(context, m)?.let { put("notice", it) }
        if (m.role == "assistant" && m.status != Status.STREAMING) put("meta", meta(context, m, showUsage, lookup))
        putJsonArray("attachments") {
            m.attachmentList.forEach { a ->
                add(buildJsonObject {
                    put("kind", a.kind.name)
                    put("name", a.name)
                    put("url", "$HOST/files/${a.path}")
                    put("meta", when (a.kind) {
                        AttachmentKind.PDF -> "PDF · ${sizeText(a.size)}"
                        AttachmentKind.TEXT -> context.getString(R.string.text_file) + " · ${sizeText(a.size)}"
                        AttachmentKind.IMAGE -> ""
                    })
                })
            }
        }
    })
}

private fun notice(context: Context, m: MessageEntity): String? = when {
    m.status == Status.STOPPED -> context.getString(R.string.notice_stopped)
    m.finishReason == "length" -> context.getString(R.string.notice_truncated)
    m.finishReason == "content_filter" -> context.getString(R.string.notice_filtered)
    else -> null
}

private fun meta(context: Context, m: MessageEntity, showUsage: Boolean, lookup: (String) -> ModelInfo?): String {
    val info = lookup(m.modelId)
    val name = info?.name ?: m.modelId.substringAfter('/')
    if (!showUsage) return name
    val nf = NumberFormat.getIntegerInstance()
    val parts = mutableListOf(name)
    if (m.promptTokens != null && m.completionTokens != null) parts += "↑${nf.format(m.promptTokens)} ↓${nf.format(m.completionTokens)}"
    when {
        m.cost != null -> parts += money(m.cost)
        m.promptTokens != null && m.completionTokens != null && info?.promptPrice != null && info.completionPrice != null -> {
            val est = m.promptTokens * info.promptPrice + m.completionTokens * info.completionPrice
            parts += "≈" + money(est) + " " + context.getString(R.string.estimated)
        }
    }
    return parts.joinToString(" · ")
}

fun money(v: Double): String = when {
    v == 0.0 -> "$0"
    v < 0.0001 -> "<$0.0001"
    v < 1 -> String.format(Locale.US, "$%.4f", v)
    else -> String.format(Locale.US, "$%.2f", v)
}

fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
