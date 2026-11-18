package de.localai.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class Attachment(
    val name: String,
    val content: String,
    val truncated: Boolean,
)

object FileUtils {

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Max. Zeichen pro Datei, abhängig vom Kontextfenster des Modells (Platz für die Antwort lassen). */
    fun maxAttachmentChars(maxTokens: Int): Int = (maxTokens * 1.5).toInt().coerceIn(1000, 8000)

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment ?: "Datei"
    }

    fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        return -1L
    }

    /**
     * Liest eine Textdatei (txt, md, csv, json, Quellcode, …) ein.
     * Wirft eine Exception, wenn es sich offensichtlich um eine Binärdatei handelt.
     */
    fun readTextAttachment(context: Context, uri: Uri, maxChars: Int): Attachment {
        val name = displayName(context, uri)
        val limitBytes = maxChars * 4 + 1
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(limitBytes)
            var total = 0
            while (total < limitBytes) {
                val n = input.read(buf, total, limitBytes - total)
                if (n < 0) break
                total += n
            }
            buf.copyOf(total)
        } ?: throw IllegalArgumentException("Datei konnte nicht geöffnet werden")

        val sample = bytes.take(4096)
        if (sample.count { it == 0.toByte() } > 0) {
            throw IllegalArgumentException(
                "„$name“ scheint keine Textdatei zu sein. Unterstützt werden Textformate wie .txt, .md, .csv, .json oder Quellcode."
            )
        }
        var text = bytes.toString(Charsets.UTF_8)
        var truncated = bytes.size >= limitBytes
        if (text.length > maxChars) {
            text = text.substring(0, maxChars)
            truncated = true
        }
        return Attachment(name, text, truncated)
    }

    /** Kopiert die gewählte Modelldatei in den App-Speicher (MediaPipe benötigt einen Dateipfad). */
    fun importModel(context: Context, uri: Uri, onProgress: (Float) -> Unit): File {
        val name = displayName(context, uri)
        val total = size(context, uri)
        val dir = modelsDir(context)
        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (total > 0 && copied - lastReport > (8 shl 20)) {
                        lastReport = copied
                        onProgress(copied.toFloat() / total)
                    }
                }
            }
        } ?: throw IllegalArgumentException("Modelldatei konnte nicht geöffnet werden")
        if (!tmp.renameTo(target)) throw IllegalStateException("Modelldatei konnte nicht gespeichert werden")
        onProgress(1f)
        return target
    }
}
