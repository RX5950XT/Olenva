package app.olenva.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Quad corners as 8 normalized floats (x,y) in order TL, TR, BR, BL. */
typealias Quad = FloatArray

object DocDetector {
    /** Finds the most plausible document outline in a small upright grayscale frame. */
    fun detect(gray: Mat): Quad? {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        try {
            val m = Core.mean(blur).`val`[0]
            val edges = Mat()
            Imgproc.Canny(blur, edges, max(10.0, 0.5 * m), min(255.0, 1.2 * m + 20))
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)), Point(-1.0, -1.0), 2)
            var quad = bestQuad(edges, w * h)
            edges.release()
            if (quad == null) {
                // Paper is usually the brightest large region: fall back to Otsu segmentation,
                // but only trust it when the region really stands out from what surrounds it.
                val bin = Mat()
                Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
                quad = bestQuad(bin, w * h)?.takeIf { contrasts(blur, bin) }
                bin.release()
            }
            return quad?.let { normalize(it, w, h) }?.takeIf { !fillsFrame(it) }
        } finally {
            blur.release()
        }
    }

    private fun bestQuad(bin: Mat, frameArea: Double): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val ranked = contours.map { it to Imgproc.contourArea(it) }.sortedByDescending { it.second }.take(5)
        var result: Array<Point>? = null
        for ((c, area) in ranked) {
            if (area < 0.12 * frameArea) break
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            // Try tight → loose approximation; the hull handles slightly curved book pages.
            val hull = MatOfInt()
            Imgproc.convexHull(c, hull)
            val pts = c.toArray()
            val hullPts = MatOfPoint2f(*hull.toArray().map { pts[it] }.toTypedArray())
            for ((src, eps) in listOf(c2f to 0.02, hullPts to 0.02, hullPts to 0.04, hullPts to 0.06)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(src, approx, eps * peri, true)
                val p = approx.toArray()
                approx.release()
                if (p.size == 4 && Imgproc.isContourConvex(MatOfPoint(*p)) && Imgproc.contourArea(MatOfPoint(*p)) > 0.12 * frameArea) {
                    result = order(p)
                    break
                }
            }
            c2f.release(); hull.release(); hullPts.release()
            if (result != null) break
            if (area > 0.25 * frameArea) {
                val rect = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))
                val p = arrayOfNulls<Point>(4).also { rect.points(it) }.requireNoNulls()
                result = order(p)
                break
            }
        }
        contours.forEach { it.release() }
        return result
    }

    private fun contrasts(gray: Mat, mask: Mat): Boolean {
        val inside = Core.mean(gray, mask).`val`[0]
        val inv = Mat()
        Core.bitwise_not(mask, inv)
        val outside = if (Core.countNonZero(inv) > 0) Core.mean(gray, inv).`val`[0] else 0.0
        inv.release()
        return inside - outside > 25
    }

    /** All four corners on the frame border means we "found" the frame itself, not a page. */
    private fun fillsFrame(q: Quad) = (0 until 4).all { i ->
        val x = q[i * 2]; val y = q[i * 2 + 1]
        x < 0.03f || x > 0.97f || y < 0.03f || y > 0.97f
    }

    fun order(p: Array<Point>): Array<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    private fun normalize(p: Array<Point>, w: Double, h: Double): Quad =
        FloatArray(8) { i -> if (i % 2 == 0) (p[i / 2].x / w).toFloat() else (p[i / 2].y / h).toFloat() }

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

    fun warp(bmp: Bitmap, q: Quad): Bitmap {
        val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
        val p = Array(4) { Point(q[it * 2] * w, q[it * 2 + 1] * h) }
        val outW = max(dist(p[0], p[1]), dist(p[3], p[2]))
        val outH = max(dist(p[0], p[3]), dist(p[1], p[2]))
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
        val l = normalizeIllumination(ch[0])
        ch[0].release()

        val out: Mat = when (mode) {
            "bw" -> {
                val block = (min(l.cols(), l.rows()) / 40).coerceIn(15, 61) or 1
                val bw = Mat()
                Imgproc.adaptiveThreshold(l, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
                // Adaptive thresholding alone hollows out solid fills; on the illumination-normalized image a
                // global cut keeps them dark while the adaptive pass still catches faint strokes.
                val global = Mat()
                Imgproc.threshold(l, global, 140.0, 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(bw, global, bw)
                global.release()
                Imgproc.cvtColor(bw, bw, Imgproc.COLOR_GRAY2RGBA)
                bw
            }
            "gray" -> {
                stretch(l, 1.0, 99.0)
                unsharp(l, 0.6)
                Mat().also { Imgproc.cvtColor(l, it, Imgproc.COLOR_GRAY2RGBA) }
            }
            else -> {
                if (mode == "color") stretch(l, 2.0, 92.0) else stretch(l, 0.5, 99.5)
                unsharp(l, if (mode == "color") 0.7 else 0.5)
                // Chroma noise lives in a/b; smoothing it there leaves stroke detail (in L) untouched.
                Imgproc.GaussianBlur(ch[1], ch[1], Size(5.0, 5.0), 0.0)
                Imgproc.GaussianBlur(ch[2], ch[2], Size(5.0, 5.0), 0.0)
                if (mode == "color") {
                    ch[1].convertTo(ch[1], -1, 1.15, -128 * 0.15)
                    ch[2].convertTo(ch[2], -1, 1.15, -128 * 0.15)
                }
                val merged = Mat()
                Core.merge(listOf(l, ch[1], ch[2]), merged)
                Imgproc.cvtColor(merged, merged, Imgproc.COLOR_Lab2RGB)
                Imgproc.cvtColor(merged, merged, Imgproc.COLOR_RGB2RGBA)
                merged
            }
        }
        ch.forEach { it.release() }
        l.release()
        return out.toBitmap()
    }

    private fun normalizeIllumination(l: Mat): Mat {
        val small = Mat()
        val f = 0.25
        Imgproc.resize(l, small, Size(), f, f, Imgproc.INTER_AREA)
        val k = (max(small.cols(), small.rows()) / 18).coerceAtLeast(9) or 1
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

    private fun stretch(m: Mat, loPct: Double, hiPct: Double) {
        val lo = percentile(m, loPct)
        val hi = max(percentile(m, hiPct), lo + 40)
        val a = 255.0 / (hi - lo)
        m.convertTo(m, -1, a, -lo * a)
    }

    private fun unsharp(m: Mat, amount: Double) {
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), 1.2)
        Core.addWeighted(m, 1 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    private fun percentile(m: Mat, pct: Double): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val total = m.total().toDouble()
        var acc = 0.0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= total * pct / 100) { hist.release(); return i.toDouble() }
        }
        hist.release()
        return 255.0
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun Mat.toBitmap(): Bitmap {
        val b = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(this, b)
        release()
        return b
    }
}
