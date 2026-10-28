package app.olenva

import app.olenva.scan.Orientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationTest {
    @Test fun uprightPageStays() = assertEquals(0, Orientation.pick(floatArrayOf(0.92f, 0.03f, 0.03f, 0.02f)))

    // The model reports how far the page is turned clockwise; the fix turns it back.
    @Test fun pageTurnedClockwiseIsTurnedBack() = assertEquals(270, Orientation.pick(floatArrayOf(0.02f, 0.93f, 0.03f, 0.02f)))

    @Test fun upsideDownPageFlips() = assertEquals(180, Orientation.pick(floatArrayOf(0.03f, 0.02f, 0.93f, 0.02f)))

    @Test fun pageTurnedCounterClockwise() = assertEquals(90, Orientation.pick(floatArrayOf(0.02f, 0.03f, 0.02f, 0.93f)))

    @Test fun unsureMeansNoAnswer() = assertNull(Orientation.pick(floatArrayOf(0.26f, 0.25f, 0.24f, 0.25f)))

    @Test fun unCroppedPhotoIsNotTrusted() = assertNull(Orientation.pick(floatArrayOf(0.1f, 0.02f, 0.78f, 0.1f)))
}
