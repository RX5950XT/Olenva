package app.olenva

import app.olenva.scan.EdgeSnap
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class EdgeSnapTest {
    private val w = 400
    private val h = 500
    // A slightly rotated page: TL, TR, BR, BL in pixels.
    private val page = doubleArrayOf(62.0, 48.0, 338.0, 60.0, 330.0, 452.0, 50.0, 440.0)

    private fun inside(x: Double, y: Double): Boolean = (0 until 4).all { i ->
        val ax = page[i * 2]; val ay = page[i * 2 + 1]
        val bx = page[(i + 1) % 4 * 2]; val by = page[(i + 1) % 4 * 2 + 1]
        (bx - ax) * (y - ay) - (by - ay) * (x - ax) >= 0
    }

    /** Desk at 70, paper at 225, and a dark pen line just inside the top edge that must not be taken for the edge. */
    private fun image() = FloatArray(w * h) { i ->
        val x = i % w + 0.5; val y = i / w + 0.5
        when {
            !inside(x, y) -> 70f
            y in 66.0..69.0 && x in 90.0..300.0 -> 40f
            else -> 225f
        }
    }

    private fun norm(p: DoubleArray) = FloatArray(8) { (if (it % 2 == 0) p[it] / w else p[it] / h).toFloat() }

    @Test fun cornersOffByAFewPercentSnapToThePaper() {
        val off = doubleArrayOf(52.0, 38.0, 349.0, 52.0, 341.0, 462.0, 42.0, 449.0)   // ~10 px outside the page
        assertArrayEquals(norm(page), EdgeSnap.snap(image(), w, h, norm(off)), 0.004f)
    }

    @Test fun noEdgeKeepsTheModelCorners() {
        val blank = FloatArray(w * h) { 225f }
        val q = norm(page)
        assertArrayEquals(q, EdgeSnap.snap(blank, w, h, q), 0f)
    }
}
