package app.olenva.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

const val DEFAULT_MODEL = "google/gemini-3.8-flash"
val EFFORTS = listOf("low", "medium", "high")

@Serializable
data class AppSettings(
    val theme: String = "system",          // system | light | dark
    val fontScale: Float = 1f,
    val compact: Boolean = false,
    val startup: String = "new",            // new | last
    val lastConversationId: String? = null,
    val defaultModel: String = DEFAULT_MODEL,
    val favorites: List<String> = emptyList(),
    val customModels: List<String> = emptyList(),
    val temperature: Float? = null,
    val topP: Float? = null,
    val reasoning: String = "high",         // low | medium | high
    val systemPrompt: String = "",
    val showUsage: Boolean = true,
    val autoScan: Boolean = true,
    val preferWide: Boolean = true,
    val autoCrop: Boolean = true,
    val autoEnhance: Boolean = true,
    val multiPage: Boolean = false,
    val haptics: Boolean = true,
)

private fun isStorableModelId(id: String) = id.isNotBlank() && '\n' !in id && '\r' !in id

/** Settings read from a backup file are untrusted: anything out of range falls back to the default. */
fun AppSettings.sanitized(): AppSettings {
    val d = AppSettings()
    return copy(
        theme = theme.takeIf { it in listOf("system", "light", "dark") } ?: d.theme,
        fontScale = fontScale.takeIf { it in 0.8f..1.6f } ?: d.fontScale,
        startup = startup.takeIf { it in listOf("new", "last") } ?: d.startup,
        lastConversationId = null,
        defaultModel = defaultModel.takeIf(::isStorableModelId) ?: d.defaultModel,
        favorites = favorites.filter(::isStorableModelId),
        customModels = customModels.filter(::isStorableModelId),
        temperature = temperature?.takeIf { it in 0f..2f },
        topP = topP?.takeIf { it in 0f..1f },
        reasoning = reasoning.takeIf { it in EFFORTS } ?: d.reasoning,
    )
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val fontScale = floatPreferencesKey("fontScale")
        val compact = booleanPreferencesKey("compact")
        val startup = stringPreferencesKey("startup")
        val lastConversation = stringPreferencesKey("lastConversation")
        val defaultModel = stringPreferencesKey("defaultModel")
        val favorites = stringPreferencesKey("favorites")
        val customModels = stringPreferencesKey("customModels")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("topP")
        val reasoning = stringPreferencesKey("reasoning")
        val systemPrompt = stringPreferencesKey("systemPrompt")
        val showUsage = booleanPreferencesKey("showUsage")
        val autoScan = booleanPreferencesKey("autoScan")
        val preferWide = booleanPreferencesKey("preferWide")
        val autoCrop = booleanPreferencesKey("autoCrop")
        val autoEnhance = booleanPreferencesKey("autoEnhance")
        val multiPage = booleanPreferencesKey("multiPage")
        val haptics = booleanPreferencesKey("haptics")
    }

    private fun Preferences.toSettings() = AppSettings().let { d ->
        AppSettings(
            theme = this[K.theme] ?: d.theme,
            fontScale = this[K.fontScale] ?: d.fontScale,
            compact = this[K.compact] ?: d.compact,
            startup = this[K.startup] ?: d.startup,
            lastConversationId = this[K.lastConversation],
            defaultModel = this[K.defaultModel] ?: d.defaultModel,
            favorites = this[K.favorites].toList(),
            customModels = this[K.customModels].toList(),
            temperature = this[K.temperature],
            topP = this[K.topP],
            // Older versions also stored "default" and "none"; those now fall back to high.
            reasoning = this[K.reasoning]?.takeIf { it in EFFORTS } ?: d.reasoning,
            systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
            showUsage = this[K.showUsage] ?: d.showUsage,
            autoScan = this[K.autoScan] ?: d.autoScan,
            preferWide = this[K.preferWide] ?: d.preferWide,
            autoCrop = this[K.autoCrop] ?: d.autoCrop,
            autoEnhance = this[K.autoEnhance] ?: d.autoEnhance,
            multiPage = this[K.multiPage] ?: d.multiPage,
            haptics = this[K.haptics] ?: d.haptics,
        )
    }

    private fun String?.toList() = this?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    // Read once synchronously so the first frame already has the right theme and font size.
    private val initial = runBlocking { context.dataStore.data.first().toSettings() }

    val settings: StateFlow<AppSettings> =
        context.dataStore.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Eagerly, initial)

    init {
        // Drop what older versions stored for retired settings (recent models, per-page enhance mode, shutter sound).
        scope.launch {
            context.dataStore.edit { p -> listOf("recents", "enhanceMode").forEach { p.remove(stringPreferencesKey(it)) }
                p.remove(booleanPreferencesKey("shutterSound")); p.remove(intPreferencesKey("maxTokens")) }
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = block(p.toSettings())
            p[K.theme] = s.theme
            p[K.fontScale] = s.fontScale
            p[K.compact] = s.compact
            p[K.startup] = s.startup
            s.lastConversationId?.let { p[K.lastConversation] = it } ?: p.remove(K.lastConversation)
            p[K.defaultModel] = s.defaultModel
            p[K.favorites] = s.favorites.joinToString("\n")
            p[K.customModels] = s.customModels.joinToString("\n")
            s.temperature?.let { p[K.temperature] = it } ?: p.remove(K.temperature)
            s.topP?.let { p[K.topP] = it } ?: p.remove(K.topP)
            p[K.reasoning] = s.reasoning
            p[K.systemPrompt] = s.systemPrompt
            p[K.showUsage] = s.showUsage
            p[K.autoScan] = s.autoScan
            p[K.preferWide] = s.preferWide
            p[K.autoCrop] = s.autoCrop
            p[K.autoEnhance] = s.autoEnhance
            p[K.multiPage] = s.multiPage
            p[K.haptics] = s.haptics
        }
    }
}
