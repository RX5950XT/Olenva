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
    fun keepsPresetIdsWhenRestoring() {
        val ids = listOf("@preset/my-preset", "openai/gpt-5@preset/my-preset", "custom id")
        val settings = AppSettings(defaultModel = ids[0], favorites = ids, customModels = ids)
        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun replacesOutOfRangeValuesWithDefaults() {
        val d = AppSettings()
        val s = AppSettings(
            theme = "neon", fontScale = 9f, startup = "x", lastConversationId = "c1", defaultModel = " ",
            favorites = listOf("a/b", "", "x\ny/z"), temperature = 5f, topP = -1f, reasoning = "max",
        ).sanitized()
        assertEquals(d.copy(favorites = listOf("a/b")), s)
    }
}
