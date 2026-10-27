package app.olenva.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.olenva.data.Attachment
import app.olenva.data.AttachmentException
import app.olenva.data.AttachmentStore
import app.olenva.data.rotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Turns a captured frame (or a picked photo) into a cropped, enhanced attachment; keeps the original for re-editing. */
class ScanPipeline(private val store: AttachmentStore) {

    suspend fun fromCapture(upright: Bitmap, hint: Quad?, autoCrop: Boolean, mode: String): Attachment = withContext(Dispatchers.Default) {
        val origAtt = store.saveBitmap(upright, "original.jpg", quality = 90)
        val quad = if (!autoCrop) null else refine(upright, hint)
        val cropped = if (quad != null) DocProcessor.warp(upright, quad) else upright
        val detected = Orientation.detect(cropped)
        val rotation = detected ?: 0
        val processed = DocProcessor.enhance(cropped.rotate(rotation), mode)
        store.saveBitmap(processed, "scan-${stamp()}.jpg").copy(
            originalPath = origAtt.path,
            quad = quad?.toList(),
            mode = mode,
            rotation = rotation,
            oriented = detected != null,
        )
    }

    /** Re-renders an image attachment from its original with new crop / rotation / mode. */
    suspend fun reprocess(a: Attachment, quad: Quad?, rotation: Int, mode: String): Attachment = withContext(Dispatchers.Default) {
        val originalPath = a.originalPath ?: a.path
        val original = decode(originalPath) ?: throw AttachmentException(AttachmentException.Reason.CORRUPT, a.name)
        val cropped = if (quad != null) DocProcessor.warp(original, quad) else original
        // Check text direction until it has actually been checked once; a manual rotation always wins.
        val manual = rotation != a.rotation
        val detected = if (!a.oriented && !manual) Orientation.detect(cropped) else null
        val rot = detected ?: rotation
        val out = DocProcessor.enhance(cropped.rotate(rot), mode)
        val fresh = store.saveBitmap(out, a.name)
        if (a.originalPath != null) store.file(a.path).delete()   // old processed copy; never the original
        fresh.copy(
            id = a.id, originalPath = originalPath, quad = quad?.toList(), mode = mode, rotation = rot,
            oriented = a.oriented || manual || detected != null,
        )
    }

    suspend fun detect(a: Attachment): Quad? = withContext(Dispatchers.Default) {
        decode(a.originalPath ?: a.path)?.let { DocProcessor.detectFull(it) }
    }

    fun decode(path: String, maxSide: Int = 4096): Bitmap? {
        val f = store.file(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = AttachmentStore.sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSide)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Prefer a quad re-detected at full resolution when it agrees with what the live preview saw. */
    private fun refine(bmp: Bitmap, hint: Quad?): Quad? {
        val full = DocProcessor.detectFull(bmp)
        if (hint == null) return full
        if (full != null && (0 until 8).all { abs(full[it] - hint[it]) < 0.06f }) return full
        return hint
    }

    private fun stamp() = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
}
