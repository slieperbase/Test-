package de.localai.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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

data class UiState(
    val modelName: String? = null,
    val modelStatus: ModelStatus = ModelStatus.NONE,
    val importProgress: Float = 0f,
    val useGpu: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val pendingAttachments: List<Attachment> = emptyList(),
    val isGenerating: Boolean = false,
    val snackbar: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", 0)
    private val engine = LlmEngine(app)
    private val ids = AtomicLong(0)

    private val _state = MutableStateFlow(UiState(useGpu = prefs.getBoolean(KEY_GPU, false)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val path = prefs.getString(KEY_MODEL_PATH, null)
        if (path != null && File(path).exists()) loadModel(path)
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
                prefs.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
                loadModel(file.absolutePath)
            } catch (e: Exception) {
                _state.update {
                    it.copy(modelStatus = ModelStatus.ERROR, snackbar = "Import fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    private fun loadModel(path: String) {
        val name = File(path).name
        _state.update { it.copy(modelStatus = ModelStatus.LOADING, modelName = name) }
        viewModelScope.launch {
            try {
                engine.load(path, _state.value.useGpu)
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

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val att = withContext(Dispatchers.IO) {
                    FileUtils.readTextAttachment(getApplication(), uri)
                }
                _state.update {
                    it.copy(
                        pendingAttachments = it.pendingAttachments + att,
                        snackbar = if (att.truncated)
                            "„${att.name}“ ist lang und wurde auf ${FileUtils.MAX_ATTACHMENT_CHARS} Zeichen gekürzt." else null
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
    }
}
