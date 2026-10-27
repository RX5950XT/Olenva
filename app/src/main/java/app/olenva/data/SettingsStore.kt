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
import kotlinx.coroutines.runBlocking

const val DEFAULT_MODEL = "google/gemini-3.8-flash"

data class AppSettings(
    val theme: String = "system",          // system | light | dark
    val fontScale: Float = 1f,
    val compact: Boolean = false,
    val startup: String = "new",            // new | last
    val lastConversationId: String? = null,
    val defaultModel: String = DEFAULT_MODEL,
    val favorites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoning: String = "default",      // default | none | low | medium | high
    val systemPrompt: String = "",
    val showUsage: Boolean = true,
    val autoScan: Boolean = true,
    val preferWide: Boolean = true,
    val autoCrop: Boolean = true,
    val autoEnhance: Boolean = true,
    val enhanceMode: String = "auto",       // auto | original | color | gray | bw
    val multiPage: Boolean = false,
    val haptics: Boolean = true,
    val shutterSound: Boolean = false,
)

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
        val recents = stringPreferencesKey("recents")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("topP")
        val maxTokens = intPreferencesKey("maxTokens")
        val reasoning = stringPreferencesKey("reasoning")
        val systemPrompt = stringPreferencesKey("systemPrompt")
        val showUsage = booleanPreferencesKey("showUsage")
        val autoScan = booleanPreferencesKey("autoScan")
        val preferWide = booleanPreferencesKey("preferWide")
        val autoCrop = booleanPreferencesKey("autoCrop")
        val autoEnhance = booleanPreferencesKey("autoEnhance")
        val enhanceMode = stringPreferencesKey("enhanceMode")
        val multiPage = booleanPreferencesKey("multiPage")
        val haptics = booleanPreferencesKey("haptics")
        val shutterSound = booleanPreferencesKey("shutterSound")
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
            recents = this[K.recents].toList(),
            temperature = this[K.temperature],
            topP = this[K.topP],
            maxTokens = this[K.maxTokens],
            reasoning = this[K.reasoning] ?: d.reasoning,
            systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
            showUsage = this[K.showUsage] ?: d.showUsage,
            autoScan = this[K.autoScan] ?: d.autoScan,
            preferWide = this[K.preferWide] ?: d.preferWide,
            autoCrop = this[K.autoCrop] ?: d.autoCrop,
            autoEnhance = this[K.autoEnhance] ?: d.autoEnhance,
            enhanceMode = this[K.enhanceMode] ?: d.enhanceMode,
            multiPage = this[K.multiPage] ?: d.multiPage,
            haptics = this[K.haptics] ?: d.haptics,
            shutterSound = this[K.shutterSound] ?: d.shutterSound,
        )
    }

    private fun String?.toList() = this?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    // Read once synchronously so the first frame already has the right theme and font size.
    private val initial = runBlocking { context.dataStore.data.first().toSettings() }

    val settings: StateFlow<AppSettings> =
        context.dataStore.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Eagerly, initial)

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
            p[K.recents] = s.recents.joinToString("\n")
            s.temperature?.let { p[K.temperature] = it } ?: p.remove(K.temperature)
            s.topP?.let { p[K.topP] = it } ?: p.remove(K.topP)
            s.maxTokens?.let { p[K.maxTokens] = it } ?: p.remove(K.maxTokens)
            p[K.reasoning] = s.reasoning
            p[K.systemPrompt] = s.systemPrompt
            p[K.showUsage] = s.showUsage
            p[K.autoScan] = s.autoScan
            p[K.preferWide] = s.preferWide
            p[K.autoCrop] = s.autoCrop
            p[K.autoEnhance] = s.autoEnhance
            p[K.enhanceMode] = s.enhanceMode
            p[K.multiPage] = s.multiPage
            p[K.haptics] = s.haptics
            p[K.shutterSound] = s.shutterSound
        }
    }

    suspend fun touchModel(id: String) = update { s ->
        s.copy(recents = (listOf(id) + s.recents.filter { it != id }).take(8))
    }
}
