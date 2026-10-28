package app.olenva

import app.olenva.data.AppSettings
import app.olenva.data.sanitized
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackupTest {
    @Test
    fun keepsValidSettings() {
        val s = AppSettings(theme = "dark", fontScale = 1.4f, defaultModel = "google/gemini-4-argon", temperature = 0.7f, reasoning = "low")
        assertEquals(s, s.sanitized())
    }

    @Test
    fun replacesOutOfRangeValuesWithDefaults() {
        val d = AppSettings()
        val s = AppSettings(
            theme = "neon", fontScale = 9f, startup = "x", lastConversationId = "c1", defaultModel = "bad id",
            favorites = listOf("a/b", "no slash", "x\ny/z"), temperature = 5f, topP = -1f, reasoning = "max",
        ).sanitized()
        assertEquals(d.copy(favorites = listOf("a/b")), s)
    }
}
