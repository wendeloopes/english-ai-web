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
    @Volatile private var modelLoadFailure: String? = null
    private val modelFile get() = File(activity.filesDir, "model.litertlm")
    private val legacyModelFile get() = File(activity.filesDir, "model.bin")
    private fun migrateLegacyModelIfNeeded() {
        // Older builds saved the model as model.bin. LiteRT-LM dispatches by extension,
        // so migrate the same bytes to the required .litertlm filename without re-importing.
        if (!modelFile.exists() && legacyModelFile.exists()) {
            check(legacyModelFile.renameTo(modelFile)) {
                "The previous model could not be renamed to .litertlm. Please import it again."
            }
        }
    }
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
    @JavascriptInterface fun loadModel() { try { migrateLegacyModelIfNeeded() } catch (e: Exception) { status(false, "Model error", e.message ?: "Could not prepare the model file."); return }; if (!modelFile.exists()) { modelLoadFailure = "No local model is installed. Open Settings, choose a compatible .litertlm file, and wait until the status says Model ready."; status(false, "Model required", "No model is installed on this phone. Open Settings and import a compatible .litertlm file. The app can display the interface without a model, but AI replies need one."); return }; loadPath() }
    private fun loadPath() {
        // LiteRT-LM memory-maps model weights; total RAM is a safer coarse guard than
        // Android availableMem, which can look low even when the OS can reclaim cache.
        val modelBytes = modelFile.length()
        val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        if (memory.totalMem < modelBytes * 13L / 10L) {
            val gb = String.format(Locale.US, "%.1f", memory.totalMem / 1073741824.0)
            val detail = "This phone has about $gb GB of RAM and the model file is ${modelBytes / 1048576L} MB. " +
                "For phones with 4 GB of RAM use Gemma 4 E2B (about 2.6 GB, file gemma-4-E2B-it.litertlm). " +
                "Gemma 4 E4B (about 3.6 GB) needs a phone with 6 GB of RAM or more."
            modelLoadFailure = detail
            status(false, "Model too large for this phone", detail)
            return
        }

        modelLoadFailure = "The model is still loading. Wait for the Model ready status before sending a message."
        status(false, "Loading model safely…", "Available RAM check passed. Loading with the CPU backend to reduce GPU-driver instability.")
        worker.execute {
            try {
                engine.load(modelFile.absolutePath, totalRam())
                modelLoadFailure = null
                status(true, "Model ready · offline", "Model loaded in stability mode (CPU). Replies may be slower, but this avoids GPU initialization on this device.")
            } catch (e: Exception) {
                modelLoadFailure = e.message ?: "Could not load this model file. Check that it is a compatible LiteRT-LM .litertlm model."
                status(false, "Model error", modelLoadFailure!!)
            }
        }
    }
    private fun totalRam(): Long {
        val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }
    @JavascriptInterface fun getInfo(): String {
        val v = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "v$v · RAM " + String.format(Locale.US, "%.1f", totalRam() / 1073741824.0) + " GB"
    }
    @Volatile private var unloaded = false
    fun unloadForBackground() { worker.execute { if (modelFile.exists() && modelLoadFailure == null) { engine.close(); unloaded = true; modelLoadFailure = "The model is reloading. Wait for Model ready." } } }
    fun reloadIfUnloaded() { if (unloaded) { unloaded = false; loadPath() } }
    fun trimMemory() { worker.execute { engine.trimConversation() } }

    @JavascriptInterface fun pickModel() = main.post { choose() }
    fun importModel(uri: Uri) { worker.execute {
        modelLoadFailure = "The model import is in progress. Wait until loading finishes before sending a message."
        unloaded = false
        // Release the loaded engine before replacing a large model file to reduce peak RAM use.
        engine.close()
        val temp = File(activity.filesDir, "model.tmp")
        try {
            val meta = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            var expected = -1L
            meta.use { c -> if (c != null && c.moveToFirst()) { if (!c.isNull(1)) expected = c.getLong(1) } }
            activity.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Could not open selected file." }.use { src -> temp.outputStream().use { dst -> val buf = ByteArray(1024 * 1024); var n: Int; var total = 0L; while (src.read(buf).also { n = it } > 0) { dst.write(buf, 0, n); total += n; if (expected > 0) call("onModelProgress", (total.toDouble() / expected).coerceIn(0.0, 1.0).toString()) } } } }
            require(temp.length() > 1024 * 1024) { "The selected file is too small to be a language model." }
            // LiteRT-LM requires the on-disk filename to end in .litertlm. The picker display
            // name is not trusted; we always save to this canonical filename.
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Could not save the model in private app storage." }
            status(false, "Loading model…", "Model saved as ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB). Initializing LiteRT-LM…")
            loadPath()
        } catch (e: Exception) { temp.delete(); modelLoadFailure = e.message ?: "Could not import model."; status(false, "Import failed", modelLoadFailure!!) }
    } }
    @JavascriptInterface fun removeModel() { worker.execute { unloaded = false; engine.close(); modelFile.delete(); File(activity.filesDir, "model.tmp").delete(); modelLoadFailure = "The local model was removed. Import a compatible .litertlm file in Settings before starting an AI conversation."; status(false, "Demo mode", "Model removed from this phone.") } }
    @JavascriptInterface fun generate(json: String) { cancelled = false; worker.execute { try {
        if (modelLoadFailure != null) throw IllegalStateException(modelLoadFailure)
        val root = JSONObject(json); val arr = root.optJSONArray("messages") ?: JSONArray(); val messages = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) { val m = arr.getJSONObject(i); messages += Pair(m.optString("role"), m.optString("content")) }
        engine.generate(root.optString("chat"), root.optInt("count", messages.size), root.optString("system"), messages) { piece -> if (!cancelled) call("onToken", JSONObject.quote(piece)) }
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
                override fun onReadyForSpeech(params: android.os.Bundle?) { call("onSpeechReady") }
                override fun onBeginningOfSpeech() { call("onSpeechStarted") }
                override fun onRmsChanged(rmsdB: Float) { call("onLevel", (rmsdB / 12f).coerceIn(0f, 1f).toString()) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    if ((error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                         error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                         error == SpeechRecognizer.ERROR_NO_MATCH) && useOnDevice) {
                        // On some phones, on-device recognition is advertised but the language pack is
                        // missing, the service disconnects, or it fails to match speech. Retry
                        // once with Android's default service. The useOnDevice flag prevents loops.
                        main.postDelayed({ if (!listening) beginListening(useOnDevice = false) }, 250)
                    } else {
                        val detail = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android denied microphone access."
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition is unavailable on this phone. Install/update the Google app and Speech Recognition service, then download English (United States) speech/language data in their settings. You can still type your answer."
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android's speech recognition service disconnected. Check or update the Speech Recognition service in system settings. You can still type your answer."
                            SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that phrase. Move closer to the microphone, speak clearly in English after the listening screen appears, and try again. You can also type your answer."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear speech. Tap the microphone and start speaking when the listening screen appears. You can also type your answer."
                            SpeechRecognizer.ERROR_AUDIO -> "The microphone audio could not be captured. Check microphone permission and make sure another app isn't using the microphone. You can also type your answer."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Wait a moment and try the microphone again, or type your answer."
                            else -> "Speech recognition couldn't complete (code $error). Check microphone permission and the English (United States) speech service, then try again or type your answer."
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
