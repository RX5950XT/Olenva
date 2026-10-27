package app.olenva

import app.olenva.scan.Orientation
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTest {
    @Test fun lineAnglesMapToQuarterTurns() {
        assertEquals(0, Orientation.correctionFor(2f))
        assertEquals(90, Orientation.correctionFor(-89f))      // text turned counter-clockwise
        assertEquals(270, Orientation.correctionFor(91f))      // text turned clockwise
        assertEquals(180, Orientation.correctionFor(179f))
        assertEquals(180, Orientation.correctionFor(-178f))
    }

    @Test fun uprightPageStays() = assertEquals(0, Orientation.pick(mapOf(0 to 300.0, 90 to 10.0)))

    @Test fun upsideDownPageFlips() = assertEquals(180, Orientation.pick(mapOf(180 to 280.0, 0 to 15.0)))

    @Test fun sidewaysPageTurns() = assertEquals(90, Orientation.pick(mapOf(90 to 73.0)))

    @Test fun noTextMeansNoRotation() = assertEquals(0, Orientation.pick(mapOf(180 to 6.0)))

    @Test fun mixedDirectionsDoNotFlip() = assertEquals(0, Orientation.pick(mapOf(0 to 100.0, 180 to 120.0)))
}
