package app.olenva.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Moves each side of a detected quad onto the real paper edge nearby. The corner model works on a 128-cell heatmap,
 * so its corners can sit a few percent off, leaving a strip of desk in the scan; the edge itself is a sharp step
 * in the photo. Each side is sampled across a narrow band, the strongest dark-to-bright step going into the page is
 * taken, and a line fitted through those points; corners are where adjacent lines meet. A side without a clear edge
 * (white paper on a white desk) keeps the model's corners.
 */
object EdgeSnap {
    private const val BAND = 0.04       // search ± this fraction of the long side
    private const val SAMPLES = 60
    private const val WIN = 6           // pixels averaged on each side of a step: wider than a pen stroke, so text is not an edge
    private const val MIN_STEP = 15f

    /** [img] is a grayscale image, row-major, [w]×[h]; [q] and the result are normalized TL, TR, BR, BL. */
    fun snap(img: FloatArray, w: Int, h: Int, q: Quad): Quad {
        val band = BAND * max(w, h)
        val px = DoubleArray(4) { q[it * 2] * w.toDouble() }
        val py = DoubleArray(4) { q[it * 2 + 1] * h.toDouble() }
        val cx = px.average(); val cy = py.average()
        val lines = Array(4) { i -> side(img, w, h, px[i], py[i], px[(i + 1) % 4], py[(i + 1) % 4], cx, cy, band) }
        val out = q.copyOf()
        for (i in 0 until 4) {
            val p = intersect(lines[(i + 3) % 4] ?: continue, lines[i] ?: continue) ?: continue
            if (hypot(p[0] - px[i], p[1] - py[i]) < 2 * band) {
                out[i * 2] = (p[0] / w).toFloat()
                out[i * 2 + 1] = (p[1] / h).toFloat()
            }
        }
        return out
    }

    /** Line (x0, y0, dx, dy) through the edge found along side a→b, or null. */
    private fun side(img: FloatArray, w: Int, h: Int, ax: Double, ay: Double, bx: Double, by: Double,
                     cx: Double, cy: Double, band: Double): DoubleArray? {
        val tx = bx - ax; val ty = by - ay
        val len = hypot(tx, ty)
        if (len < 1) return null
        var nx = -ty / len; var ny = tx / len
        if ((cx - (ax + bx) / 2) * nx + (cy - (ay + by) / 2) * ny < 0) { nx = -nx; ny = -ny }   // n points into the page
        val n = 2 * band.toInt() + 1
        val prefix = DoubleArray(n + 1)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val steps = ArrayList<Float>()
        for (s in 0 until SAMPLES) {
            val t = 0.08 + 0.84 * s / (SAMPLES - 1)
            val mx = ax + tx * t; val my = ay + ty * t
            for (k in 0 until n) {
                val o = k - band.toInt()
                prefix[k + 1] = prefix[k] + sample(img, w, h, mx + o * nx, my + o * ny)
            }
            var best = -1f; var bestK = -1
            for (k in WIN until n - WIN) {
                // inside (towards the page) minus outside
                val step = ((prefix[k + WIN] - prefix[k]) - (prefix[k] - prefix[k - WIN])) / WIN
                if (step > best) { best = step.toFloat(); bestK = k }
            }
            if (bestK < 0) continue
            val o = bestK - band.toInt() - 0.5
            xs += mx + o * nx; ys += my + o * ny; steps += best
        }
        val median = steps.sorted().getOrNull(steps.size / 2) ?: return null
        val keep = steps.indices.filter { steps[it] > max(MIN_STEP, 0.5f * median) }
        if (keep.size < 12) return null
        var line = fit(keep.map { xs[it] }, keep.map { ys[it] })
        // One pass of outlier rejection: a hand, a clip or a shadow can pull a few samples off the edge.
        val res = keep.map { dist(line, xs[it], ys[it]) }
        val cut = max(2.0, 2.5 * res.sorted()[res.size / 2])
        val inliers = keep.indices.filter { res[it] <= cut }.map { keep[it] }
        if (inliers.size < 12) return null
        line = fit(inliers.map { xs[it] }, inliers.map { ys[it] })
        return line
    }

    /** Total least squares: line through the centroid along the principal direction. */
    private fun fit(x: List<Double>, y: List<Double>): DoubleArray {
        val mx = x.average(); val my = y.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx; val dy = y[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return doubleArrayOf(mx, my, cos(angle), sin(angle))
    }

    private fun dist(l: DoubleArray, x: Double, y: Double) = abs((x - l[0]) * l[3] - (y - l[1]) * l[2])

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val det = a[2] * b[3] - a[3] * b[2]
        if (abs(det) < 1e-6) return null
        val s = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / det
        return doubleArrayOf(a[0] + s * a[2], a[1] + s * a[3])
    }

    private fun sample(img: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        val cx = x.coerceIn(0.0, w - 1.001); val cy = y.coerceIn(0.0, h - 1.001)
        val x0 = floor(cx).toInt(); val y0 = floor(cy).toInt()
        val fx = cx - x0; val fy = cy - y0
        val i = y0 * w + x0
        return (img[i] * (1 - fx) + img[i + 1] * fx) * (1 - fy) + (img[i + w] * (1 - fx) + img[i + w + 1] * fx) * fy
    }
}
