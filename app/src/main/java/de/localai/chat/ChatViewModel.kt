package de.localai.chat

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val attachments: List<String> = emptyList(),
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
)

enum class ModelStatus { NONE, IMPORTING, LOADING, READY, ERROR }

data class DownloadProgress(val downloaded: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
}

/** Eine auf dem Gerät gespeicherte Modelldatei. */
data class InstalledModel(val fileName: String, val sizeMb: Int)

data class UiState(
    val modelName: String? = null,
    val activeFile: String? = null,
    val modelStatus: ModelStatus = ModelStatus.NONE,
    val importProgress: Float = 0f,
    val useGpu: Boolean = false,
    val contextTokens: Int = 2048,
    val messages: List<ChatMessage> = emptyList(),
    val pendingAttachments: List<Attachment> = emptyList(),
    val isGenerating: Boolean = false,
    val snackbar: String? = null,
    val installed: List<InstalledModel> = emptyList(),
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val hfToken: String = "",
    val deviceRamGb: Int = 0,
    val freeStorageMb: Long = 0,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", 0)
    private val engine = LlmEngine(app)
    private val ids = AtomicLong(0)
    private val downloadJobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(
        UiState(
            useGpu = prefs.getBoolean(KEY_GPU, false),
            hfToken = prefs.getString(KEY_HF_TOKEN, "") ?: "",
            deviceRamGb = deviceRamGb(app),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val modelsDir get() = FileUtils.modelsDir(getApplication())

    init {
        refreshInstalled()
        val path = prefs.getString(KEY_MODEL_PATH, null)
        if (path != null && File(path).exists()) loadModel(path)
    }

    // ---------- Modellverwaltung ----------

    fun refreshInstalled() {
        val files = modelsDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            ?.map { InstalledModel(it.name, (it.length() / 1_000_000).toInt()) }
            ?: emptyList()
        val free = try { StatFs(modelsDir.path).availableBytes / 1_000_000 } catch (_: Exception) { 0L }
        _state.update { it.copy(installed = files, freeStorageMb = free) }
    }

    fun setHfToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
        _state.update { it.copy(hfToken = token) }
    }

    fun downloadModel(model: CatalogModel) {
        if (downloadJobs.containsKey(model.id)) return
        val s = _state.value
        if (model.needsToken && s.hfToken.isBlank()) {
            _state.update { it.copy(snackbar = "Für ${model.name} bitte zuerst unten deinen Hugging-Face-Token eintragen.") }
            return
        }
        val partSize = File(modelsDir, model.fileName + ".part").let { if (it.exists()) it.length() / 1_000_000 else 0 }
        if (s.freeStorageMb in 1 until (model.sizeMb - partSize + 200)) {
            _state.update { it.copy(snackbar = "Nicht genug Speicherplatz: ${model.sizeMb} MB benötigt, ${s.freeStorageMb} MB frei.") }
            return
        }
        _state.update { it.copy(downloads = it.downloads + (model.id to DownloadProgress(0, model.sizeMb * 1_000_000L))) }
        downloadJobs[model.id] = viewModelScope.launch {
            try {
                ModelDownloader.download(
                    url = model.url,
                    target = File(modelsDir, model.fileName),
                    token = if (model.needsToken) _state.value.hfToken else null,
                ) { done, total ->
                    _state.update { it.copy(downloads = it.downloads + (model.id to DownloadProgress(done, total))) }
                }
                refreshInstalled()
                _state.update { it.copy(snackbar = "${model.name} ist bereit.") }
                // Wenn noch kein Modell aktiv ist, das neue direkt verwenden
                if (_state.value.modelStatus != ModelStatus.READY) useModel(model.fileName)
            } catch (e: CancellationException) {
                // vom Nutzer abgebrochen – .part-Datei bleibt zum Fortsetzen liegen
            } catch (e: Throwable) {
                _state.update { it.copy(snackbar = "Download fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                downloadJobs.remove(model.id)
                _state.update { it.copy(downloads = it.downloads - model.id) }
                refreshInstalled()
            }
        }
    }

    fun cancelDownload(model: CatalogModel) {
        downloadJobs[model.id]?.cancel()
    }

    fun hasPartialDownload(model: CatalogModel): Boolean =
        File(modelsDir, model.fileName + ".part").exists()

    fun useModel(fileName: String) {
        if (_state.value.isGenerating) return
        val file = File(modelsDir, fileName)
        if (!file.exists()) return
        prefs.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
        loadModel(file.absolutePath)
    }

    fun deleteModel(fileName: String) {
        if (_state.value.activeFile == fileName) {
            if (_state.value.isGenerating) return
            engine.close()
            prefs.edit().remove(KEY_MODEL_PATH).apply()
            _state.update {
                it.copy(modelStatus = ModelStatus.NONE, activeFile = null, modelName = null, messages = emptyList())
            }
        }
        File(modelsDir, fileName).delete()
        File(modelsDir, "$fileName.part").delete()
        refreshInstalled()
    }

    fun importModel(uri: Uri) {
        if (_state.value.isGenerating) return
        _state.update { it.copy(modelStatus = ModelStatus.IMPORTING, importProgress = 0f) }
        viewModelScope.launch {
            try {
                engine.close()
                val file = withContext(Dispatchers.IO) {
                    FileUtils.importModel(getApplication(), uri) { p ->
                        _state.update { it.copy(importProgress = p) }
                    }
                }
                refreshInstalled()
                useModel(file.name)
            } catch (e: Exception) {
                _state.update {
                    it.copy(modelStatus = ModelStatus.ERROR, snackbar = "Import fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    private fun loadModel(path: String) {
        val fileName = File(path).name
        val displayName = ModelCatalog.byFileName(fileName)?.name ?: fileName
        val tokens = ModelCatalog.maxTokensFor(fileName)
        _state.update {
            it.copy(
                modelStatus = ModelStatus.LOADING, modelName = displayName,
                activeFile = fileName, contextTokens = tokens,
            )
        }
        viewModelScope.launch {
            try {
                engine.load(path, _state.value.useGpu, tokens)
                _state.update { it.copy(modelStatus = ModelStatus.READY, messages = emptyList()) }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        modelStatus = ModelStatus.ERROR,
                        snackbar = "Modell konnte nicht geladen werden: ${e.message}" +
                            if (it.useGpu) " – versuche es mit CPU." else ""
                    )
                }
            }
        }
    }

    fun setUseGpu(enabled: Boolean) {
        if (_state.value.isGenerating) return
        prefs.edit().putBoolean(KEY_GPU, enabled).apply()
        _state.update { it.copy(useGpu = enabled) }
        prefs.getString(KEY_MODEL_PATH, null)?.let { if (File(it).exists()) loadModel(it) }
    }

    // ---------- Chat ----------

    fun addAttachment(uri: Uri) {
        val maxChars = FileUtils.maxAttachmentChars(_state.value.contextTokens)
        viewModelScope.launch {
            try {
                val att = withContext(Dispatchers.IO) {
                    FileUtils.readTextAttachment(getApplication(), uri, maxChars)
                }
                _state.update {
                    it.copy(
                        pendingAttachments = it.pendingAttachments + att,
                        snackbar = if (att.truncated)
                            "„${att.name}“ ist für dieses Modell zu lang und wurde auf $maxChars Zeichen gekürzt." else null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(snackbar = e.message ?: "Datei konnte nicht gelesen werden") }
            }
        }
    }

    fun removeAttachment(att: Attachment) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments - att) }
    }

    fun newChat() {
        if (_state.value.isGenerating) return
        _state.update { it.copy(messages = emptyList(), pendingAttachments = emptyList()) }
        viewModelScope.launch {
            try { engine.resetConversation() } catch (_: Throwable) {}
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }

    fun send(text: String) {
        val s = _state.value
        if (s.isGenerating || s.modelStatus != ModelStatus.READY) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && s.pendingAttachments.isEmpty()) return

        val attachments = s.pendingAttachments
        val prompt = buildPrompt(trimmed, attachments)
        val userMsg = ChatMessage(ids.incrementAndGet(), Role.USER, trimmed, attachments.map { it.name })
        val botId = ids.incrementAndGet()
        val botMsg = ChatMessage(botId, Role.ASSISTANT, "", isStreaming = true)

        _state.update {
            it.copy(
                messages = it.messages + userMsg + botMsg,
                pendingAttachments = emptyList(),
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            try {
                val result = engine.generate(prompt) { partial -> updateBot(botId, partial, streaming = true) }
                updateBot(botId, result.trim(), streaming = false)
            } catch (e: Throwable) {
                // Session ist nach einem Fehler (z. B. Kontext voll) meist unbrauchbar
                try { engine.resetConversation() } catch (_: Throwable) {}
                _state.update { st ->
                    st.copy(messages = st.messages.map {
                        if (it.id == botId) it.copy(
                            text = "Fehler: ${e.message ?: e.javaClass.simpleName}\n\n" +
                                "Der Gesprächskontext wurde zurückgesetzt (evtl. war er zu lang).",
                            isError = true, isStreaming = false
                        ) else it
                    })
                }
            } finally {
                _state.update { it.copy(isGenerating = false) }
            }
        }
    }

    private fun updateBot(id: Long, text: String, streaming: Boolean) {
        _state.update { st ->
            st.copy(messages = st.messages.map {
                if (it.id == id) it.copy(text = text, isStreaming = streaming) else it
            })
        }
    }

    private fun buildPrompt(text: String, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return text
        val sb = StringBuilder()
        attachments.forEach { a ->
            sb.append("Inhalt der Datei \"").append(a.name).append("\"")
            if (a.truncated) sb.append(" (gekürzt)")
            sb.append(":\n```\n").append(a.content).append("\n```\n\n")
        }
        sb.append(if (text.isNotEmpty()) text else "Bitte fasse den Inhalt der Datei(en) zusammen.")
        return sb.toString()
    }

    override fun onCleared() {
        engine.close()
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_GPU = "use_gpu"
        private const val KEY_HF_TOKEN = "hf_token"

        private fun deviceRamGb(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return (info.totalMem / 1_000_000_000.0).roundToInt()
        }
    }
}
