package app.olenva.scan

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

enum class ScanStatus { SEARCHING, DETECTED, HOLD, ADJUST, BLURRY, NEXT_PAGE, CAPTURING, PROCESSING }

data class ScanFrame(
    val quad: Quad? = null,
    val frameW: Int = 3,
    val frameH: Int = 4,
    val status: ScanStatus = ScanStatus.SEARCHING,
    val progress: Float = 0f,
)

/**
 * Live document tracking. Auto-capture fires only after the outline has stayed put for a moment,
 * and re-arms only once the page leaves the frame or visibly changes — so one page is never shot twice.
 */
class DocAnalyzer(
    private val onFrame: (ScanFrame) -> Unit,
    private val onTrigger: (Quad) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var auto = true
    @Volatile var paused = false

    private val history = ArrayDeque<Pair<Long, Quad>>()
    private var armed = true
    private var lastSig: Mat? = null
    private var noDocSince = 0L
    private var stableSince = 0L
    private val tracker = QuadTracker()
    private var lastFrame = ScanFrame()

    /** Called after a capture (auto or manual) with the page that was taken. */
    @Synchronized fun disarm(sig: Mat?) {
        armed = false
        lastSig?.release()
        lastSig = sig
    }

    @Synchronized fun rearm() {
        armed = true
        lastSig?.release()
        lastSig = null
    }

    @Volatile private var latestSmall: Mat? = null
    @Volatile private var latestQuad: Quad? = null

    /** Signature of the most recent frame, for manual captures. */
    @Synchronized fun currentSignature(): Mat? {
        val s = latestSmall ?: return null
        val q = latestQuad ?: return null
        return DocDetector.signature(s, q)
    }

    fun lastQuad(): Quad? = latestQuad

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val small = toSmallUpright(image)
            val raw = DocDetector.detect(small)
            val frame = synchronized(this) {
                latestSmall?.release()
                latestSmall = small
                step(small, raw, SystemClock.uptimeMillis()).also { latestQuad = it.quad }
            }
            onFrame(frame.copy(frameW = small.cols(), frameH = small.rows()))
        } catch (e: Exception) {
            Log.w("DocAnalyzer", "frame skipped", e)
        } finally {
            image.close()
        }
    }

    private fun toSmallUpright(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buf = plane.buffer
        val full = Mat(image.height, image.width, CvType.CV_8UC1, buf, plane.rowStride.toLong())
        val scale = 360.0 / max(image.width, image.height)
        val small = Mat()
        Imgproc.resize(full, small, Size(), scale, scale, Imgproc.INTER_AREA)
        full.release()
        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(small, small, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(small, small, Core.ROTATE_180)
            270 -> Core.rotate(small, small, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return small
    }

    private fun step(small: Mat, raw: Quad?, now: Long): ScanFrame {
        val quad = tracker.update(raw, now)
        // A frame without a detection (glare, motion blur) keeps the last outline and progress instead of blinking off.
        if (quad != null && raw == null) return lastFrame.copy(quad = quad)
        return judge(small, quad, now).also { lastFrame = it }
    }

    private fun judge(small: Mat, quad: Quad?, now: Long): ScanFrame {
        if (quad == null) {
            history.clear()
            stableSince = 0
            if (noDocSince == 0L) noDocSince = now
            if (!armed && now - noDocSince > 700) rearm()
            return ScanFrame(status = ScanStatus.SEARCHING)
        }
        noDocSince = 0
        if (touchesEdge(quad)) {
            history.clear(); stableSince = 0
            return ScanFrame(quad, status = ScanStatus.ADJUST)
        }
        history.addLast(now to quad)
        while (now - history.first().first > 600) history.removeFirst()

        if (!armed) {
            lastSig?.let { prev ->
                val sig = DocDetector.signature(small, quad)
                if (DocDetector.signatureDiff(sig, prev) > 22) rearm()
                sig.release()
            }
            if (!armed) return ScanFrame(quad, status = ScanStatus.NEXT_PAGE)
        }
        if (!auto) return ScanFrame(quad, status = ScanStatus.DETECTED)

        val moved = history.maxOf { QuadTracker.diff(it.second, quad) }
        val steady = moved < 0.025f && now - history.first().first >= 200
        if (!steady) {
            stableSince = 0
            return ScanFrame(quad, status = ScanStatus.DETECTED)
        }
        if (DocDetector.sharpness(small) < 15) {
            stableSince = 0
            return ScanFrame(quad, status = ScanStatus.BLURRY)
        }
        if (stableSince == 0L) stableSince = now
        val progress = ((now - stableSince) / 300f).coerceIn(0f, 1f)
        if (progress >= 1f) {
            disarm(DocDetector.signature(small, quad))
            stableSince = 0
            history.clear()
            tracker.reset()
            paused = true
            onTrigger(quad)
            return ScanFrame(quad, status = ScanStatus.CAPTURING, progress = 1f)
        }
        return ScanFrame(quad, status = ScanStatus.HOLD, progress = progress)
    }

    private fun touchesEdge(q: Quad) = q.any { it < 0.015f || it > 0.985f }
}
