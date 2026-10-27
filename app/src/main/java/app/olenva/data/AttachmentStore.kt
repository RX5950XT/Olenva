package app.olenva.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class AttachmentException(val reason: Reason, val name: String) : Exception(reason.name) {
    enum class Reason { UNSUPPORTED, TOO_LARGE, CORRUPT, READ_FAILED }
}

class AttachmentStore(private val context: Context) {
    val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(path: String) = File(dir, path)
    fun newPath(ext: String) = "${UUID.randomUUID()}.$ext"

    /** Copies a picked/shared document into private storage. Throws [AttachmentException] for user-facing failures. */
    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val name = queryName(uri) ?: uri.lastPathSegment ?: "file"
        val mime = (cr.getType(uri) ?: guessMime(name)).lowercase()
        val size = querySize(uri)
        val kind = when {
            mime.startsWith("image/") -> AttachmentKind.IMAGE
            mime == "application/pdf" -> AttachmentKind.PDF
            mime.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".txt", true) -> AttachmentKind.TEXT
            else -> throw AttachmentException(AttachmentException.Reason.UNSUPPORTED, name)
        }
        val limit = when (kind) { AttachmentKind.TEXT -> MAX_TEXT; else -> MAX_BINARY }
        if (size != null && size > limit) throw AttachmentException(AttachmentException.Reason.TOO_LARGE, name)

        val bytes = try {
            cr.openInputStream(uri)?.use { it.readBytes() } ?: throw AttachmentException(AttachmentException.Reason.READ_FAILED, name)
        } catch (e: SecurityException) {
            throw AttachmentException(AttachmentException.Reason.READ_FAILED, name)
        }
        if (bytes.size > limit) throw AttachmentException(AttachmentException.Reason.TOO_LARGE, name)

        when (kind) {
            AttachmentKind.IMAGE -> saveImageBytes(bytes, name)
            AttachmentKind.PDF -> {
                if (!String(bytes, 0, minOf(5, bytes.size)).startsWith("%PDF")) throw AttachmentException(AttachmentException.Reason.CORRUPT, name)
                val path = newPath("pdf")
                file(path).writeBytes(bytes)
                Attachment(UUID.randomUUID().toString(), kind, name, "application/pdf", path, bytes.size.toLong())
            }
            AttachmentKind.TEXT -> {
                val path = newPath("txt")
                file(path).writeBytes(bytes)
                Attachment(UUID.randomUUID().toString(), kind, name, if (name.endsWith(".md", true)) "text/markdown" else "text/plain", path, bytes.size.toLong())
            }
        }
    }

    /** Re-encodes any decodable image (HEIC, GIF, …) to JPEG, applying EXIF rotation. */
    private fun saveImageBytes(bytes: ByteArray, name: String): Attachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw AttachmentException(AttachmentException.Reason.CORRUPT, name)
        val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        val passthrough = rotation == 0 && bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp") &&
            maxOf(bounds.outWidth, bounds.outHeight) <= MAX_IMAGE_SIDE
        if (passthrough) {
            val ext = bounds.outMimeType.substringAfter('/').replace("jpeg", "jpg")
            val path = newPath(ext)
            file(path).writeBytes(bytes)
            return Attachment(UUID.randomUUID().toString(), AttachmentKind.IMAGE, name, bounds.outMimeType, path, bytes.size.toLong(), bounds.outWidth, bounds.outHeight)
        }
        val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), MAX_IMAGE_SIDE)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw AttachmentException(AttachmentException.Reason.CORRUPT, name)
        val rotated = if (rotation != 0) bmp.rotate(rotation) else bmp
        return saveBitmap(rotated, name.substringBeforeLast('.') + ".jpg")
    }

    fun saveBitmap(bmp: Bitmap, name: String, quality: Int = 92): Attachment {
        val path = newPath("jpg")
        file(path).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return Attachment(UUID.randomUUID().toString(), AttachmentKind.IMAGE, name, "image/jpeg", path, file(path).length(), bmp.width, bmp.height)
    }

    /** Data URL for the API; large images are downscaled so requests stay small but text remains legible. */
    suspend fun imageDataUrl(a: Attachment): String = withContext(Dispatchers.IO) {
        val f = file(a.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (!f.exists()) throw AttachmentException(AttachmentException.Reason.READ_FAILED, a.name)
        val passthrough = longSide <= API_IMAGE_SIDE && f.length() < 4_000_000
        val bytes = if (passthrough) f.readBytes() else {
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(longSide, API_IMAGE_SIDE) })
                ?: throw AttachmentException(AttachmentException.Reason.CORRUPT, a.name)
            val scale = API_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        val mime = if (passthrough) a.mime else "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun base64(a: Attachment): String = withContext(Dispatchers.IO) {
        Base64.encodeToString(file(a.path).readBytes(), Base64.NO_WRAP)
    }

    suspend fun text(a: Attachment): String = withContext(Dispatchers.IO) { file(a.path).readText() }

    fun delete(a: Attachment) {
        file(a.path).delete()
        a.originalPath?.let { file(it).delete() }
    }

    fun usageBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Removes files no message or draft references (e.g. abandoned scans). */
    suspend fun cleanup(referenced: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach { it.delete() }
        File(context.cacheDir, "share").deleteRecursively()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        const val MAX_BINARY = 20L * 1024 * 1024
        const val MAX_TEXT = 1L * 1024 * 1024
        const val MAX_IMAGE_SIDE = 4096
        const val API_IMAGE_SIDE = 2400

        fun sampleSize(longSide: Int, target: Int): Int {
            var s = 1
            while (longSide / (s * 2) >= target) s *= 2
            return s
        }
    }
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
