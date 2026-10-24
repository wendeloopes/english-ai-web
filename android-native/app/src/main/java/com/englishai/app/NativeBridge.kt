package com.englishai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class NativeBridge(private val activity: Activity, private val web: WebView, private val choose: () -> Unit, private val requestMic: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = LiteRtEngine(activity)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var cancelled = false
    @Volatile private var listening = false
    private val modelFile get() = File(activity.filesDir, "model.bin")
    init {
        tts = TextToSpeech(activity) { status -> if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { id?.let { call("onTtsDone", JSONObject.quote(it)) } }
                override fun onError(id: String?) { id?.let { call("onTtsDone", JSONObject.quote(it)) } }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { id?.let { call("onTtsRange", JSONObject.quote(it), start.toString()) } }
            })
            refreshVoices()
        } }
    }
    private fun call(name: String, vararg args: String) { val code = "window.Native && window.Native." + name + " && window.Native." + name + "(" + args.joinToString(",") + ")"; main.post { web.evaluateJavascript(code, null) } }
    private fun status(ok: Boolean, label: String, msg: String) = call("onModelStatus", ok.toString(), JSONObject.quote(label), JSONObject.quote(msg))
    @JavascriptInterface fun loadModel() { if (!modelFile.exists()) { status(false, "Demo mode", "Choose your compatible local model file in Settings."); return }; loadPath() }
    private fun loadPath() { status(false, "Loading model…", "Initializing the local model; this may take several seconds."); worker.execute { try { engine.load(modelFile.absolutePath); status(true, "Gemma ready · offline", "Model ready. Inference runs locally on this phone.") } catch (e: Exception) { status(false, "Model error", e.message ?: "Could not load this model file.") } } }
    @JavascriptInterface fun pickModel() = main.post { choose() }
    fun importModel(uri: Uri) { worker.execute {
        val temp = File(activity.filesDir, "model.tmp")
        try {
            val meta = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            var expected = -1L
            meta.use { c -> if (c != null && c.moveToFirst()) { if (!c.isNull(1)) expected = c.getLong(1) } }
            activity.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Could not open selected file." }.use { src -> temp.outputStream().use { dst -> val buf = ByteArray(1024 * 1024); var n: Int; var total = 0L; while (src.read(buf).also { n = it } > 0) { dst.write(buf, 0, n); total += n; if (expected > 0) call("onModelProgress", (total.toDouble() / expected).coerceIn(0.0, 1.0).toString()) } } } }
            require(temp.length() > 1024 * 1024) { "The selected file is too small to be a language model." }
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Could not save the model in private app storage." }
            status(false, "Loading model…", "Model saved (${modelFile.length() / 1024 / 1024} MB). Initializing LiteRT-LM…")
            loadPath()
        } catch (e: Exception) { temp.delete(); status(false, "Import failed", e.message ?: "Could not import model.") }
    } }
    @JavascriptInterface fun removeModel() { worker.execute { engine.close(); modelFile.delete(); File(activity.filesDir, "model.tmp").delete(); status(false, "Demo mode", "Model removed from this phone.") } }
    @JavascriptInterface fun generate(json: String) { cancelled = false; worker.execute { try {
        val root = JSONObject(json); val arr = root.optJSONArray("messages") ?: JSONArray(); val messages = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) { val m = arr.getJSONObject(i); messages += Pair(m.optString("role"), m.optString("content")) }
        engine.generate(root.optString("system"), messages) { piece -> if (!cancelled) call("onToken", JSONObject.quote(piece)) }
        call("onGenDone")
    } catch (e: Exception) { if (cancelled) call("onGenDone") else call("onGenError", JSONObject.quote(e.message ?: "Model generation failed.")) } } }
    @JavascriptInterface fun cancelGenerate() { cancelled = true; engine.cancel() }
    @JavascriptInterface fun getVoices() { refreshVoices() }
    fun refreshVoices() { val arr = JSONArray(); tts?.voices?.filter { it.locale.language == "en" }?.forEach { v -> arr.put(JSONObject().put("name", v.name).put("lang", v.locale.toLanguageTag())) }; call("onVoices", JSONObject.quote(arr.toString())) }
    @JavascriptInterface fun speak(id: String, text: String, rate: String, voiceName: String) { main.post { try { tts?.setSpeechRate(rate.toFloatOrNull() ?: 1f); tts?.voices?.firstOrNull { it.name == voiceName }?.let { tts?.voice = it }; tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) } catch (_: Exception) { call("onTtsDone", JSONObject.quote(id)) } } }
    @JavascriptInterface fun stopSpeaking() { main.post { tts?.stop() } }
    @JavascriptInterface fun startListening() { main.post { requestMic() } }
    fun startListeningGranted() {
        main.post {
            if (listening) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                call("onSpeechError", JSONObject.quote("No speech recognition service is available. Check Android's Speech Recognition settings."))
                return@post
            }
            // Prefer truly on-device recognition, but recover from ERROR_SERVER_DISCONNECTED (11)
            // by retrying once with Android's default recognition service.
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                beginListening(useOnDevice = true)
            } else {
                beginListening(useOnDevice = false)
            }
        }
    }

    private fun beginListening(useOnDevice: Boolean) {
        try {
            try { recognizer?.destroy() } catch (_: Exception) {}
            listening = true
            recognizer = if (useOnDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { call("onLevel", (rmsdB / 12f).coerceIn(0f, 1f).toString()) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && useOnDevice) {
                        // Some Android builds advertise on-device recognition but its service
                        // disconnects. Retry with the system recognizer instead of stopping cold.
                        main.postDelayed({ if (!listening) beginListening(useOnDevice = false) }, 250)
                    } else {
                        val detail = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android denied microphone access."
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English (United States) speech data is not installed."
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android's speech recognition service disconnected. Check or update the Speech Recognition service in system settings."
                            else -> "Speech recognition error ($error). You can type instead."
                        }
                        call("onSpeechError", JSONObject.quote(detail))
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    listening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    call("onSpeechEnd", JSONObject.quote(text))
                }
                override fun onPartialResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    call("onSpeechPartial", JSONObject.quote(text))
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            call("onSpeechError", JSONObject.quote(e.message ?: "Could not start microphone."))
        }
    }
    @JavascriptInterface fun stopListening() { main.post { try { recognizer?.stopListening() } catch (_: Exception) {} } }
    @JavascriptInterface fun cancelListening() { main.post { listening = false; try { recognizer?.cancel() } catch (_: Exception) {} } }
    fun speechError(message: String) = call("onSpeechError", JSONObject.quote(message))
    fun close() { try { recognizer?.destroy() } catch (_: Exception) {}; try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}; worker.shutdownNow(); engine.close() }
}
