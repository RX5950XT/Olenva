package app.olenva

import app.olenva.scan.DocDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.exp

class DocDetectorTest {
    /** Four heatmap channels with a Gaussian peak at each normalized (x, y). */
    private fun heatmaps(vararg pts: Pair<Float, Float>): FloatArray {
        val n = 128
        val hm = FloatArray(4 * n * n)
        pts.forEachIndexed { k, (px, py) ->
            for (y in 0 until n) for (x in 0 until n) {
                val dx = x + 0.5f - px * n; val dy = y + 0.5f - py * n
                hm[k * n * n + y * n + x] = exp(-(dx * dx + dy * dy) / 4f)
            }
        }
        return hm
    }

    @Test fun cornersAreLocatedAndOrderedByPosition() {
        // Channels deliberately out of order: the result is still TL, TR, BR, BL by position.
        val q = DocDetector.decode(heatmaps(0.8f to 0.85f, 0.2f to 0.1f, 0.15f to 0.9f, 0.75f to 0.15f))!!
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0.75f, 0.15f, 0.8f, 0.85f, 0.15f, 0.9f), q, 0.01f)
    }

    @Test fun missingCornerMeansNoPage() {
        assertNull(DocDetector.decode(heatmaps(0.2f to 0.1f, 0.8f to 0.1f, 0.8f to 0.9f)))
    }

    @Test fun tinyOutlineIsRejected() {
        assertNull(DocDetector.decode(heatmaps(0.45f to 0.45f, 0.55f to 0.45f, 0.55f to 0.55f, 0.45f to 0.55f)))
    }
}
