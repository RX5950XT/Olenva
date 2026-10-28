package app.olenva

import app.olenva.scan.DocProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.Point
import kotlin.math.cos
import kotlin.math.sin

class PageAspectTest {
    /** Corners (TL, TR, BR, BL) of a [pw]×[ph] page lying on a table, photographed at [deg] from straight down. */
    private fun shot(pw: Double, ph: Double, deg: Double, cx: Double = 0.08, f: Double = 2000.0, w: Double = 3024.0, h: Double = 4032.0): Array<Point> {
        val t = Math.toRadians(deg)
        // Page rotated 20° on the table and off-centre, so neither pair of sides stays parallel in the photo.
        val r = Math.toRadians(if (cx == 0.0) 0.0 else 20.0)
        fun p(a0: Double, b0: Double): Point {
            val a = a0 * cos(r) - b0 * sin(r); val b = a0 * sin(r) + b0 * cos(r)
            val x = cx + a; val y = -0.04 + b * cos(t); val z = 1.0 / cos(t) - b * sin(t)
            return Point(f * x / z + w / 2, f * y / z + h / 2)
        }
        return arrayOf(p(-pw / 2, -ph / 2), p(pw / 2, -ph / 2), p(pw / 2, ph / 2), p(-pw / 2, ph / 2))
    }

    @Test fun lowAngleA4KeepsItsProportions() {
        val p = shot(0.21, 0.297, 60.0)
        val squashed = (p[1].x - p[0].x) / (p[3].y - p[0].y)
        assert(squashed > 1.0) { "the photo makes the portrait page look landscape" }
        assertEquals(0.707, DocProcessor.aspect(p, 3024.0, 4032.0)!!, 0.01)
    }

    @Test fun tiltedStraightForwardUsesTheTypicalFocalLength() {
        // Top and bottom edges parallel in the photo: the focal length can't be recovered, the assumed one is ~10% off.
        val p = shot(0.21, 0.297, 60.0, cx = 0.0)
        assertEquals(0.707, DocProcessor.aspect(p, 3024.0, 4032.0)!!, 0.06)
    }

    @Test fun frontalPageIsUnchanged() {
        assertEquals(0.707, DocProcessor.aspect(shot(0.21, 0.297, 0.0), 3024.0, 4032.0)!!, 0.01)
    }
}
