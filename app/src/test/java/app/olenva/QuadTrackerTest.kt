package app.olenva

import app.olenva.scan.QuadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuadTrackerTest {
    private fun page(dx: Float = 0f) = floatArrayOf(0.2f + dx, 0.2f, 0.8f + dx, 0.2f, 0.8f + dx, 0.8f, 0.2f + dx, 0.8f)

    private fun tracking(): QuadTracker = QuadTracker().also { it.update(page(), 0); it.update(page(), 30) }

    @Test fun singleFrameIsNotEnoughToShowAnOutline() {
        val t = QuadTracker()
        assertNull(t.update(page(), 0))
        assertNotNull(t.update(page(), 30))
    }

    @Test fun briefMissKeepsOutlineThenDrops() {
        val t = tracking()
        assertNotNull(t.update(null, 200))
        assertNull(t.update(null, 500))
    }

    @Test fun jitterIsAveraged() {
        val t = tracking()
        val q = t.update(page(0.02f), 60)!!
        assertEquals(0.21f, q[0], 1e-4f)
    }

    @Test fun oneOffMisreadIsIgnoredButARealMoveIsFollowed() {
        val t = tracking()
        assertEquals(0.2f, t.update(page(0.3f), 60)!![0], 1e-4f)   // far jump, once: ignored
        t.update(page(0.3f), 90)
        assertEquals(0.5f, t.update(page(0.3f), 120)!![0], 1e-4f)  // repeated three times: followed
    }
}
