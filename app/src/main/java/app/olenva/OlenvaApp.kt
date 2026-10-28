package app.olenva

import android.app.Application
import android.content.Context
import android.util.Log
import app.olenva.data.AppDatabase
import app.olenva.data.AttachmentStore
import app.olenva.data.Backup
import app.olenva.scan.DocDetector
import app.olenva.scan.Orientation
import app.olenva.scan.ScanPipeline
import app.olenva.data.SecretStore
import app.olenva.data.SettingsStore
import app.olenva.net.ModelInfo
import app.olenva.net.OpenRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class OlenvaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { container.dao.markInterrupted() }
        System.loadLibrary("opencv_java4")
        container.scope.launch {
            runCatching { DocDetector.load(this@OlenvaApp) }.onFailure { Log.e("OlenvaApp", "document model failed to load", it) }
            runCatching { Orientation.load(this@OlenvaApp) }.onFailure { Log.e("OlenvaApp", "orientation model failed to load", it) }
        }
    }
}

val Context.container get() = (applicationContext as OlenvaApp).container

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val dao = db.dao()
    val settings = SettingsStore(context, scope)
    val secrets = SecretStore(context)
    val attachments = AttachmentStore(context)
    val openRouter = OpenRouter()
    val models = ModelRepository(File(context.filesDir, "models.json"), openRouter, scope)
    val scan = ScanPipeline(attachments)
    val backup = Backup(dao, attachments)
}

/** Model catalogue from OpenRouter, cached on disk so the picker and capability checks work offline. */
class ModelRepository(private val cache: File, private val api: OpenRouter, private val scope: CoroutineScope) {
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed
    private val mutex = Mutex()

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (cache.exists()) runCatching { _models.value = api.parseModels(cache.readText()) }
            }
            refresh(force = false)
        }
    }

    fun find(id: String): ModelInfo? = _models.value.firstOrNull { it.id == id }

    fun refresh(force: Boolean = true) = scope.launch {
        mutex.withLock {
            val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 6 * 3600_000
            if (!force && fresh && _models.value.isNotEmpty()) return@withLock
            _loading.value = true
            try {
                val text = withContext(Dispatchers.IO) {
                    api.rawModels().also { cache.writeText(it) }
                }
                _models.value = api.parseModels(text)
                _failed.value = false
            } catch (e: Exception) {
                Log.w("Models", "refresh failed: ${e.message}")
                _failed.value = _models.value.isEmpty()
            } finally {
                _loading.value = false
            }
        }
    }
}
