package com.englishai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Optional fully on-device English speech-to-text. The model is downloaded once
 * by explicit user action; audio is never uploaded to a server.
 */
class VoskOfflineSpeech(
    private val context: Context,
    private val status: (Boolean, String) -> Unit,
    private val ready: () -> Unit,
    private val started: () -> Unit,
    private val partial: (String) -> Unit,
    private val result: (String) -> Unit,
    private val error: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val modelDir = File(context.filesDir, "vosk-model-en-us")
    private val zipFile = File(context.filesDir, "vosk-model-en-us.zip")
    private val tempDir = File(context.filesDir, "vosk-model-en-us.tmp")
    @Volatile private var listening = false
    @Volatile private var cancelled = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null

    fun isInstalled(): Boolean = File(modelDir, "am").isDirectory &&
        File(modelDir, "conf").isDirectory && File(modelDir, "graph").isDirectory

    private fun ui(block: () -> Unit) = main.post(block)

    fun downloadModel() {
        if (isInstalled()) {
            ui { status(true, "Offline English speech model is already installed (about 40 MB).") }
            return
        }
        io.execute {
            try {
                tempDir.deleteRecursively()
                zipFile.delete()
                ui { status(false, "Downloading offline English speech model… 0%") }
                val connection = (URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) { "Download failed (HTTP ${connection.responseCode}). Check your internet and try again." }
                    val length = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                total += n
                                if (length > 0) {
                                    val pct = (total * 100 / length).toInt()
                                    if (pct != last) {
                                        last = pct
                                        ui { status(false, "Downloading offline English speech model… $pct%") }
                                    }
                                }
                            }
                        }
                    }
                } finally { connection.disconnect() }

                tempDir.mkdirs()
                ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val raw = entry.name.replace('\\', '/')
                        val pieces = raw.split('/').filter { it.isNotEmpty() }
                        // Ignore the archive's single top-level model folder.
                        val relative = if (pieces.size > 1) pieces.drop(1).joinToString("/") else ""
                        if (relative.isNotEmpty() && !relative.split('/').any { it == ".." }) {
                            val out = File(tempDir, relative)
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(File(tempDir, "am").isDirectory && File(tempDir, "conf").isDirectory &&
                    File(tempDir, "graph").isDirectory) { "Downloaded speech model is incomplete. Delete it and try again." }
                modelDir.deleteRecursively()
                check(tempDir.renameTo(modelDir)) { "Could not install the offline speech model. Check available storage." }
                zipFile.delete()
                ui { status(true, "Offline English speech is installed and ready. You can now use voice practice without the Android speech service.") }
            } catch (e: Exception) {
                zipFile.delete()
                tempDir.deleteRecursively()
                ui { status(false, e.message ?: "Could not download the offline English speech model.") }
            }
        }
    }

    fun start() {
        if (!isInstalled()) {
            ui { error("The offline English speech model is not installed yet. Open Settings → Offline speech and tap Download (about 40 MB, one-time download).") }
            return
        }
        if (listening) return
        listening = true
        cancelled = false
        io.execute {
            try {
                val activeModel = model ?: Model(modelDir.absolutePath).also { model = it }
                val activeRecognizer = Recognizer(activeModel, 16000.0f).also { recognizer = it }
                val minBytes = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBytes > 0) { "This phone could not initialize microphone audio for offline speech." }
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes, 8192)).also { recorder = it }
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be initialized." }
                audio.startRecording()
                ui { ready() }
                val buffer = ShortArray(4096)
                var speechStarted = false
                while (listening) {
                    val n = audio.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    if (!speechStarted) { speechStarted = true; ui { started() } }
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                        bytes[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                    }
                    if (activeRecognizer.acceptWaveForm(bytes, bytes.size)) {
                        val text = JSONObject(activeRecognizer.result).optString("text", "")
                        if (text.isNotBlank()) ui { partial(text) }
                    } else {
                        val text = JSONObject(activeRecognizer.partialResult).optString("partial", "")
                        if (text.isNotBlank()) ui { partial(text) }
                    }
                }
                try { audio.stop() } catch (_: Exception) {}
                if (!cancelled) {
                    val finalText = JSONObject(activeRecognizer.finalResult).optString("text", "")
                    ui { result(finalText) }
                }
            } catch (e: Exception) {
                if (!cancelled) ui { error(e.message ?: "Offline English speech recognition failed. Try again or type your answer.") }
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                listening = false
                try { recognizer?.close() } catch (_: Exception) {}
                recognizer = null
            }
        }
    }

    fun stop(cancel: Boolean = false) {
        cancelled = cancel
        listening = false
        try { recorder?.stop() } catch (_: Exception) {}
    }

    fun close() {
        stop(true)
        io.shutdownNow()
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
    }
}
