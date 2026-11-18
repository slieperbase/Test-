package de.localai.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    class AuthRequiredException : IOException(
        "Zugriff verweigert. Für dieses Modell brauchst du einen Hugging-Face-Token und musst die Lizenz auf der Modellseite akzeptiert haben."
    )

    /**
     * Lädt [url] nach [target]. Bricht ein Download ab, wird beim nächsten Versuch
     * an der gleichen Stelle weitergemacht (Datei „.part“).
     */
    suspend fun download(
        url: String,
        target: File,
        token: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LokaleKI-Android")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> throw AuthRequiredException()
                code == 416 -> { // bereits vollständig
                    part.renameTo(target); return@withContext
                }
                code == 200 -> existing = 0L // Server unterstützt kein Fortsetzen → neu beginnen
                code == 206 -> Unit
                else -> throw IOException("Serverfehler $code")
            }
            val length = conn.contentLengthLong
            val total = if (length > 0) existing + length else -1L

            conn.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    var lastReport = 0L
                    onProgress(done, total)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 2_000_000) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                    if (total > 0 && done < total) throw IOException("Download unvollständig")
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
        } finally {
            conn.disconnect()
        }
    }
}
