package app.olenva.scan

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Quad corners as 8 normalized floats (x,y) in order TL, TR, BR, BL. */
typealias Quad = FloatArray

object DocDetector {
    private const val MODEL = "docaligner_lcnet100.onnx"
    private const val IN = 256.0
    private const val HM = 128
    private const val PEAK = 0.3f
    private const val THREADS = 4
    @Volatile private var net: Net? = null

    /** Loads the corner model (DocAligner, see third_party/DocAligner). Until it is ready, nothing is detected. */
    fun load(context: Context) {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        // Set once, never per call: changing the count rebuilds OpenCV's thread pool, and doing that while the capture
        // pipeline is mid-filter on another thread crashed the app (SIGSEGV in GaussianBlur). Pixel 6a: 2 threads ≈ 50 ms
        // per frame, the default 8 ≈ 130 ms (syncing with the little cores dominates).
        Core.setNumThreads(THREADS)
        net = Dnn.readNetFromONNX(MatOfByte(*bytes))
            // On Android this currently falls back to FP32: OpenCV 4.13 gates CPU_FP16 on the Apple-only `__arm64__`
            // macro (both the official and the slim build log "fall back to DNN_TARGET_CPU").
            .apply { setPreferableTarget(Dnn.DNN_TARGET_CPU_FP16) }
    }

    /**
     * Finds the page in an upright grayscale frame. A small heatmap network predicts each corner, which copes with
     * clutter, hands and low contrast far better than edge contours.
     */
    @Synchronized fun detect(gray: Mat): Quad? {
        val n = net ?: return null
        val input = Mat()
        Imgproc.resize(gray, input, Size(IN, IN), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(input, input, Imgproc.COLOR_GRAY2RGB)
        val blob = Dnn.blobFromImage(input, 1 / 255.0)
        input.release()
        n.setInput(blob)
        val out = n.forward()
        blob.release()
        val hm = FloatArray(4 * HM * HM)
        out.reshape(1, intArrayOf(1, hm.size)).run { get(0, 0, hm); release() }
        out.release()
        return decode(hm)
    }

    /** Heatmaps (4 × 128 × 128, TL/TR/BR/BL) → ordered quad, or null when a corner is missing or the shape is implausible. */
    fun decode(hm: FloatArray): Quad? {
        val pts = Array(4) { corner(hm, it) ?: return null }
        val q = order(pts).let { p -> FloatArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toFloat() } }
        return q.takeIf { convex(it) && area(it) > 0.04f }
    }

    /** Weighted centre of the strongest peak; a 7×7 window matches the reference largest-blob centroid. */
    private fun corner(hm: FloatArray, k: Int): Point? {
        val off = k * HM * HM
        var best = off
        for (i in off until off + HM * HM) if (hm[i] > hm[best]) best = i
        if (hm[best] < PEAK) return null
        val by = (best - off) / HM
        val bx = (best - off) % HM
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (y in max(0, by - 3)..min(HM - 1, by + 3)) for (x in max(0, bx - 3)..min(HM - 1, bx + 3)) {
            val v = hm[off + y * HM + x]
            if (v >= PEAK) { sw += v; sx += v * x; sy += v * y }
        }
        return Point((sx / sw + 0.5) / HM, (sy / sw + 0.5) / HM)
    }

    private fun cross(q: Quad, i: Int): Float {
        val a = i * 2; val b = (i + 1) % 4 * 2; val c = (i + 2) % 4 * 2
        return (q[b] - q[a]) * (q[c + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[c] - q[b])
    }

    private fun convex(q: Quad) = (0 until 4).all { cross(q, it) > 0 }

    /** Shoelace area as a fraction of the frame. */
    private fun area(q: Quad) = abs((0 until 4).sumOf { i ->
        val j = (i + 1) % 4
        (q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]).toDouble()
    }).toFloat() / 2

    fun order(p: Array<Point>): Array<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    /** Variance of the Laplacian: low means blurry. */
    fun sharpness(gray: Mat): Double {
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        lap.release()
        val s = std.get(0, 0)[0]
        return s * s
    }

    /** Tiny warped thumbnail of the page, used to notice when a new page is in front of the camera. */
    fun signature(gray: Mat, q: Quad): Mat {
        val w = gray.cols().toDouble(); val h = gray.rows().toDouble()
        val src = MatOfPoint2f(*Array(4) { Point(q[it * 2] * w, q[it * 2 + 1] * h) })
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(24.0, 0.0), Point(24.0, 32.0), Point(0.0, 32.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(gray, out, m, Size(24.0, 32.0))
        Imgproc.GaussianBlur(out, out, Size(3.0, 3.0), 0.0)
        m.release(); src.release(); dst.release()
        return out
    }

    fun signatureDiff(a: Mat, b: Mat): Double {
        val d = Mat()
        Core.absdiff(a, b, d)
        val v = Core.mean(d).`val`[0]
        d.release()
        return v
    }
}

object DocProcessor {
    const val MAX_SIDE = 3200
    private const val DEAD_ZONE = 2.0

    fun detectFull(bmp: Bitmap): Quad? {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val scale = 900.0 / max(gray.cols(), gray.rows())
        if (scale < 1) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        return DocDetector.detect(gray).also { gray.release() }
    }

    /** [EdgeSnap] on a 1600 px grayscale copy: fine enough for a sub-percent edge, cheap enough to run per shot. */
    fun snapEdges(bmp: Bitmap, q: Quad): Quad {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val scale = 1600.0 / max(gray.cols(), gray.rows())
        if (scale < 1) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), 1.5)   // paper grain and JPEG noise are not edges
        gray.convertTo(gray, CvType.CV_32F)
        val px = FloatArray(gray.total().toInt())
        gray.get(0, 0, px)
        return EdgeSnap.snap(px, gray.cols(), gray.rows(), q).also { gray.release() }
    }

    fun warp(bmp: Bitmap, q: Quad): Bitmap {
        val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
        val p = Array(4) { Point(q[it * 2] * w, q[it * 2 + 1] * h) }
        var outW = max(dist(p[0], p[1]), dist(p[3], p[2]))
        var outH = max(dist(p[0], p[3]), dist(p[1], p[2]))
        // Edge lengths squash a page shot at a low angle; recover its real proportions from the perspective.
        aspect(p, w, h)?.let { r ->
            val long = max(outW, outH)
            if (r < 1) { outH = long; outW = long * r } else { outW = long; outH = long / r }
        }
        val s = min(1.0, MAX_SIDE / max(outW, outH))
        val ow = max(16.0, outW * s).roundToInt().toDouble()
        val oh = max(16.0, outH * s).roundToInt().toDouble()
        val src = Mat()
        Utils.bitmapToMat(bmp, src)
        val m = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*p),
            MatOfPoint2f(Point(0.0, 0.0), Point(ow, 0.0), Point(ow, oh), Point(0.0, oh)),
        )
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, m, Size(ow, oh), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        src.release(); m.release()
        // Trim a hair off each edge so a sliver of desk along the detected border doesn't survive.
        val t = (min(ow, oh) * 0.006).roundToInt()
        val trimmed = dst.submat(t, dst.rows() - t, t, dst.cols() - t).clone()
        dst.release()
        return trimmed.toBitmap()
    }

    /**
     * Enhancement keeps strokes intact: illumination is estimated with a closing larger than any pen stroke,
     * so dividing by it whitens paper and lifts shadows without blurring or thinning symbols.
     */
    fun enhance(bmp: Bitmap, mode: String): Bitmap {
        if (mode == "original") return bmp
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        // Fine background tracking follows shadows and paper creases; filled areas are still kept.
        val l = normalizeIllumination(ch[0], 30)
        ch[0].release()
        val lit = l.clone()

        // Every other mode is "auto": levels adapt per page and per region; ink keeps its colour, paper comes out neutral white.
        autoLevels(l)
        unsharp(l, 0.3)
        // Chroma noise lives in a/b; smoothing it there leaves stroke detail (in L) untouched.
        Imgproc.GaussianBlur(ch[1], ch[1], Size(5.0, 5.0), 0.0)
        Imgproc.GaussianBlur(ch[2], ch[2], Size(5.0, 5.0), 0.0)
        recolor(lit, l, ch[1], ch[2])
        lit.release()
        val out = Mat()
        Core.merge(listOf(l, ch[1], ch[2]), out)
        Imgproc.cvtColor(out, out, Imgproc.COLOR_Lab2RGB)
        Imgproc.cvtColor(out, out, Imgproc.COLOR_RGB2RGBA)
        ch.forEach { it.release() }
        l.release()
        return out.toBitmap()
    }

    private fun normalizeIllumination(l: Mat, kDiv: Int): Mat {
        val small = Mat()
        val f = 0.25
        Imgproc.resize(l, small, Size(), f, f, Imgproc.INTER_AREA)
        val k = (max(small.cols(), small.rows()) / kDiv).coerceAtLeast(9) or 1
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        Imgproc.medianBlur(small, small, 5)
        val bg = Mat()
        small.convertTo(bg, CvType.CV_32F)
        small.release()

        // Local paper level: the brightest background nearby, smoothed. A region whose background is far
        // darker than the paper around it (a filled chart, a photo) is content; milder darkening is shadow.
        val kk = (max(bg.cols(), bg.rows()) / 4) or 1
        val paper = Mat()
        Imgproc.dilate(bg, paper, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kk.toDouble(), kk.toDouble())))
        Imgproc.GaussianBlur(paper, paper, Size(0.0, 0.0), kk / 3.0)
        Core.max(paper, bg, paper)
        Core.max(paper, Scalar(1.0), paper)
        Core.max(bg, Scalar(1.0), bg)
        // Classify on an eroded background so the soft rim around a filled region also counts as content.
        val eroded = Mat()
        Imgproc.erode(bg, eroded, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        val w = Mat()
        Core.divide(eroded, paper, w)
        eroded.release()
        Core.subtract(w, Scalar(0.35), w)
        Core.multiply(w, Scalar(1 / 0.40), w)
        Core.min(w, Scalar(1.0), w)
        Core.max(w, Scalar(0.0), w)
        val w2 = Mat()
        Core.multiply(w, w, w2)
        Core.multiply(w, Scalar(-2.0), w)
        Core.add(w, Scalar(3.0), w)
        Core.multiply(w2, w, w)               // smoothstep
        w2.release()
        Imgproc.GaussianBlur(w, w, Size(0.0, 0.0), 2.0)

        // gain = 250 * (w / bg + (1 - w) / paper), computed small and upsampled (it is smooth by construction)
        val a = Mat(); val b = Mat()
        Core.divide(w, bg, a)
        val inv = Mat()
        Core.subtract(Mat.ones(w.size(), w.type()), w, inv)
        Core.divide(inv, paper, b)
        Core.add(a, b, a)
        Core.multiply(a, Scalar(250.0), a)
        bg.release(); paper.release(); w.release(); b.release(); inv.release()
        val gain = Mat()
        Imgproc.resize(a, gain, l.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        a.release()

        val l32 = Mat()
        l.convertTo(l32, CvType.CV_32F)
        Core.multiply(l32, gain, l32)
        gain.release()
        val out = Mat()
        l32.convertTo(out, CvType.CV_8U)
        l32.release()
        return out
    }

    /**
     * Paper to pure white and ink to black. The white point sits just below the paper's own noise (cleaner background on
     * grainy paper, faint strokes kept on clean paper); the black point follows the ink nearby, because writing far
     * from the lens in a low-angle shot is blurrier and greyer than the rest. The gamma also follows the ink nearby (see
     * [inkGamma]).
     * The local black only applies where ink sits on paper nearby: in a shadow or blank area it would stretch paper
     * grain into speckles.
     */
    private fun autoLevels(m: Mat) {
        val med = percentile(m, 50.0)
        // Paper noise from paper pixels only: on a dense page the plain 25th percentile lands on stroke edges, the white
        // point drops far below the paper, and thin or faint strokes (far from the lens) are cut to white.
        val sigma = max((med - percentile(m, 25.0, from = med - 20)) / 0.674, 1.0)
        val white = (med - 3 * sigma).coerceIn(225.0, 250.0)
        val globalBlack = min(percentile(m, 0.5), 120.0)
        val k = max(15, max(m.cols(), m.rows()) / 40) or 1
        val small = Mat()
        Imgproc.resize(m, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
        small.convertTo(small, CvType.CV_32F)
        val ks = ((k / 4) or 1).toDouble()
        val win = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ks, ks))
        val ink = Mat(); val paper = Mat()
        Imgproc.erode(small, ink, win)
        Imgproc.dilate(small, paper, win)
        small.release()
        // gate = clamp((paper − ink − 15) / 25): 0 with no contrast nearby, 1 around real strokes. It opens already at
        // faint strokes (out-of-focus print, pencil); blank paper varies by under ~10, so its grain stays white.
        val gate = Mat()
        Core.subtract(paper, ink, gate)
        gate.convertTo(gate, -1, 1 / 25.0, -0.6)
        Core.min(gate, Scalar(1.0), gate)
        Core.max(gate, Scalar(0.0), gate)
        paper.release()
        val gamma = inkGamma(ink, k, m.size())
        // local black = globalBlack + gate · (clamp(ink, globalBlack, 190) − globalBlack)
        Core.max(ink, Scalar(globalBlack), ink)
        Core.min(ink, Scalar(190.0), ink)
        Core.subtract(ink, Scalar(globalBlack), ink)
        Core.multiply(ink, gate, ink)
        Core.add(ink, Scalar(globalBlack), ink)
        gate.release()
        Imgproc.GaussianBlur(ink, ink, Size(0.0, 0.0), k / 8.0)
        val black = Mat()
        Imgproc.resize(ink, black, m.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        ink.release()
        val t = Mat()
        m.convertTo(t, CvType.CV_32F)
        Core.subtract(t, black, t)
        black.convertTo(black, -1, -1.0, white)          // white − black
        Core.divide(t, black, t)
        black.release()
        Core.min(t, Scalar(1.0), t)
        Core.max(t, Scalar(0.0), t)
        // t^gamma per pixel
        Core.max(t, Scalar(1e-6), t)
        Core.log(t, t)
        Core.multiply(t, gamma, t)
        Core.exp(t, t)
        gamma.release()
        t.convertTo(m, CvType.CV_8U, 255.0)
        t.release()
    }

    /**
     * Gamma 1 where the ink nearby is dark, rising to 2 where it is faint. A steep curve deepens pencil and out-of-focus
     * print, but on dense dark print it also fills the light gaps inside many-stroke characters, merging their strokes.
     */
    private fun inkGamma(ink: Mat, k: Int, size: Size): Mat {
        val g = Mat()
        ink.convertTo(g, -1, 1 / 80.0, -100 / 80.0)   // (ink − 100) / 80
        Core.min(g, Scalar(1.0), g)
        Core.max(g, Scalar(0.0), g)
        Core.add(g, Scalar(1.0), g)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), k / 8.0)
        val out = Mat()
        Imgproc.resize(g, out, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        g.release()
        return out
    }

    /**
     * Neutral white page, ink in its own colour. A thin or faint stroke is ink blended with paper, so its colour is weak
     * (blue pen reads as light grey-blue); once levels darken it, the same weak colour looks grey. Scaling chroma by how
     * much darker the pixel got, (255 − L after) / (255 − L before), puts back the pen's colour, and fades the paper's
     * cast to zero where it turns white. A small dead zone keeps black ink from picking up a tint from chroma noise.
     */
    private fun recolor(before: Mat, after: Mat, a: Mat, b: Mat) {
        val paper = Mat()
        Core.compare(after, Scalar(250.0), paper, Core.CMP_GE)
        val gain = Mat(); val room = Mat()
        after.convertTo(gain, CvType.CV_32F, -1.0, 255.0)
        before.convertTo(room, CvType.CV_32F, -1.0, 255.0)
        Core.max(room, Scalar(8.0), room)
        Core.divide(gain, room, gain)
        Core.min(gain, Scalar(4.0), gain)
        room.release()
        val hasPaper = Core.countNonZero(paper) > 0
        for (c in listOf(a, b)) {
            val cast = if (hasPaper) Core.mean(c, paper).`val`[0] - 128 else 0.0
            val f = Mat(); val neg = Mat()
            c.convertTo(f, CvType.CV_32F, 1.0, -128 - cast)
            Core.add(f, Scalar(DEAD_ZONE), neg)
            Core.min(neg, Scalar(0.0), neg)
            Core.subtract(f, Scalar(DEAD_ZONE), f)
            Core.max(f, Scalar(0.0), f)
            Core.add(f, neg, f)
            Core.multiply(f, gain, f)
            f.convertTo(c, CvType.CV_8U, 1.0, 128.0)
            f.release(); neg.release()
        }
        paper.release(); gain.release()
    }

    private fun unsharp(m: Mat, amount: Double) {
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), 1.2)
        Core.addWeighted(m, 1 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    /** [pct] percentile of the pixels of [m] at or above [from]. */
    private fun percentile(m: Mat, pct: Double, from: Double = 0.0): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val start = from.toInt().coerceIn(0, 255)
        val total = (start until 256).sumOf { hist.get(it, 0)[0] }
        var acc = 0.0
        for (i in start until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= total * pct / 100) { hist.release(); return i.toDouble() }
        }
        hist.release()
        return 255.0
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /**
     * Width/height of the real rectangle behind corners TL, TR, BR, BL in a [w]×[h] photo (Zhang & He, "Whiteboard
     * scanning", 2007). The focal length comes from the corners when they pin it down; when a pair of sides is
     * parallel in the photo (phone tilted straight forward) they cannot, and a typical value is assumed.
     */
    fun aspect(p: Array<Point>, w: Double, h: Double): Double? {
        val u0 = w / 2; val v0 = h / 2
        fun v(pt: Point) = doubleArrayOf(pt.x, pt.y, 1.0)
        val m1 = v(p[0]); val m2 = v(p[1]); val m3 = v(p[3]); val m4 = v(p[2])
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        if (!k2.isFinite() || !k3.isFinite()) return null
        val long = max(w, h)
        val f2 = if (abs(n2[2]) < 1e-6 || abs(n3[2]) < 1e-6) -1.0 else
            -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)) / (n2[2] * n3[2])
        // shortcut: fallback focal 0.55 × long side matches the default 0.6× wide lens (measured 0.51–0.53 on a Pixel 6a);
        // pass the real zoom from the scanner if 1× shots come out stretched.
        val f = (if (f2 > 0) sqrt(f2) else 0.0).takeIf { it in 0.3 * long..1.5 * long } ?: (0.55 * long)
        // nᵀ (A⁻ᵀA⁻¹) n with A = [[f,0,u0],[0,f,v0],[0,0,1]]
        fun q(n: DoubleArray): Double {
            val x = (n[0] - u0 * n[2]) / f; val y = (n[1] - v0 * n[2]) / f
            return x * x + y * y + n[2] * n[2]
        }
        return sqrt(q(n2) / q(n3)).takeIf { it.isFinite() && it in 0.25..4.0 }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun Mat.toBitmap(): Bitmap {
        val b = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(this, b)
        release()
        return b
    }
}
