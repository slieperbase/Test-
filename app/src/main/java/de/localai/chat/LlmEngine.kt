package de.localai.chat

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Kapselt die MediaPipe-LLM-Inferenz. Alles läuft vollständig auf dem Gerät.
 * Eine Session behält den Gesprächsverlauf (KV-Cache), dadurch "erinnert" sich
 * das Modell an vorherige Nachrichten, bis eine neue Session gestartet wird.
 */
class LlmEngine(private val context: Context) {

    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null

    val isLoaded: Boolean get() = llm != null

    suspend fun load(modelPath: String, useGpu: Boolean, maxTokens: Int) =
        withContext(Dispatchers.IO) {
            close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(64)
                .setPreferredBackend(
                    if (useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
                )
                .build()
            llm = LlmInference.createFromOptions(context, options)
            newSessionInternal()
        }

    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        if (llm != null) newSessionInternal()
    }

    private fun newSessionInternal() {
        session?.close()
        val engine = llm ?: return
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.95f)
            .setTemperature(0.8f)
            .build()
        session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
    }

    /**
     * Schickt [prompt] an das Modell. [onPartial] erhält jeweils den bisher
     * erzeugten Gesamttext (für die Live-Anzeige).
     */
    suspend fun generate(prompt: String, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val s = session ?: throw IllegalStateException("Kein Modell geladen")
            s.addQueryChunk(prompt)
            val builder = StringBuilder()
            suspendCancellableCoroutine { cont ->
                val future = s.generateResponseAsync { partial, _ ->
                    synchronized(builder) {
                        if (partial != null) builder.append(partial)
                        onPartial(builder.toString())
                    }
                }
                future.addListener({
                    try {
                        future.get()
                        if (cont.isActive) cont.resume(synchronized(builder) { builder.toString() })
                    } catch (e: ExecutionException) {
                        if (cont.isActive) cont.resumeWithException(e.cause ?: e)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, Runnable::run)
            }
        }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { llm?.close() } catch (_: Exception) {}
        session = null
        llm = null
    }

}
