package app.olenva.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.olenva.data.Attachment
import app.olenva.data.AttachmentException
import app.olenva.data.AttachmentStore
import app.olenva.data.rotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Turns a captured frame (or a picked photo) into a cropped, enhanced attachment; keeps the original for re-editing. */
class ScanPipeline(private val store: AttachmentStore) {

    /** [turn] is the clockwise turn gravity says the page needs; the orientation model then refines it. */
    suspend fun fromCapture(upright: Bitmap, hint: Quad?, autoCrop: Boolean, mode: String, turn: Int): Attachment = withContext(Dispatchers.Default) {
        // Encoding the full-size original takes ~0.2 s; do it alongside the processing, which only reads the bitmap.
        val origAtt = async { store.saveBitmap(upright, "original.jpg", quality = 90) }
        val quad = if (!autoCrop) null else refine(upright, hint)
        val cropped = if (quad != null) DocProcessor.warp(upright, quad) else upright
        val detected = Orientation.detect(cropped.rotate(turn))
        val rotation = (turn + (detected ?: 0)) % 360
        val processed = DocProcessor.enhance(cropped.rotate(rotation), mode)
        store.saveBitmap(processed, "scan-${stamp()}.jpg").copy(
            originalPath = origAtt.await().path,
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
        val detected = if (!a.oriented && !manual) Orientation.detect(cropped.rotate(rotation)) else null
        val rot = (rotation + (detected ?: 0)) % 360
        val out = DocProcessor.enhance(cropped.rotate(rot), mode)
        val fresh = store.saveBitmap(out, a.name)
        if (a.originalPath != null) store.file(a.path).delete()   // old processed copy; never the original
        fresh.copy(
            id = a.id, originalPath = originalPath, quad = quad?.toList(), mode = mode, rotation = rot,
            oriented = a.oriented || manual || detected != null,
        )
    }

    suspend fun detect(a: Attachment): Quad? = withContext(Dispatchers.Default) {
        decode(a.originalPath ?: a.path)?.let { bmp -> DocProcessor.detectFull(bmp)?.let { DocProcessor.snapEdges(bmp, it) } }
    }

    fun decode(path: String, maxSide: Int = 4096): Bitmap? {
        val f = store.file(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = AttachmentStore.sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSide)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Prefer a quad re-detected on the photo when it agrees with what the live preview saw; then snap it to the paper edges. */
    private fun refine(bmp: Bitmap, hint: Quad?): Quad? {
        val full = DocProcessor.detectFull(bmp)
        val q = if (hint == null || full != null && (0 until 8).all { abs(full[it] - hint[it]) < 0.06f }) full else hint
        return q?.let { DocProcessor.snapEdges(bmp, it) }
    }

    private fun stamp() = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
}
