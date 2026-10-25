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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class NativeBridge(private val activity: Activity, private val web: WebView, private val choose: () -> Unit, private val requestMic: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = LiteRtEngine(activity)
    private val userSettings = UserSettings(activity)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var ptTranslator: Translator? = null
    private val ptLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var listening = false
    @Volatile private var modelLoadFailure: String? = null
    private val modelFile get() = File(activity.filesDir, "model.litertlm")
    private val backupModelFile get() = File(activity.filesDir, "model.bak")
    private val tempModelFile get() = File(activity.filesDir, "model.tmp")
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
    @JavascriptInterface fun loadModel() {
        try {
            // Recover a backup if Android stopped the app between renaming the old model
            // and installing the new one. Never leave a valid backup stranded.
            if (!modelFile.exists() && backupModelFile.exists()) {
                check(backupModelFile.renameTo(modelFile)) { "The previous model backup could not be restored. Re-import the model from Settings." }
            }
            tempModelFile.delete() // a .tmp file is never a committed model
            migrateLegacyModelIfNeeded()
        } catch (e: Exception) {
            status(false, "Model error", e.message ?: "Could not prepare the model file.")
            return
        }
        if (!modelFile.exists()) {
            modelLoadFailure = "No local model is installed. Open Settings, choose a compatible .litertlm file, and wait until the status says Model ready."
            status(false, "Model required", "No model is installed on this phone. Open Settings and import a compatible .litertlm file. The app can display the interface without a model, but AI replies need one.")
            return
        }
        loadPath()
    }
    /** Blocking. RAM sanity check + load + warm-up. Returns null on success, or the error text. Call on the worker thread. */
    private fun doLoad(): String? {
        val modelBytes = modelFile.length()
        // LiteRT-LM memory-maps the big embedding tables, so Android does not need "free" RAM equal to the file size.
        // Refuse only when the phone's TOTAL RAM is clearly too small for this model file.
        val total = totalRam()
        if (total < modelBytes * 13L / 10L) {
            val gb = String.format(Locale.US, "%.1f", total / 1073741824.0)
            return "This phone has about $gb GB of RAM and the model file is ${modelBytes / 1048576L} MB. " +
                "For phones with 4 GB of RAM use Gemma 4 E2B (about 2.6 GB, file gemma-4-E2B-it.litertlm). " +
                "Gemma 4 E4B (about 3.6 GB) needs a phone with 6 GB of RAM or more."
        }
        return try {
            engine.load(modelFile.absolutePath, total)
            try { engine.generate("warmup", 0, "Reply with one word.", listOf("user" to "Hi")) { _ -> }; engine.trimConversation() } catch (_: Exception) {}   // first run is slow: do it now, not on your first question
            null
        } catch (e: Throwable) {
            try { engine.close() } catch (_: Exception) {}
            e.message ?: "Could not load this model file. Check that it is a compatible LiteRT-LM .litertlm model."
        }
    }
    private fun reportLoad(err: String?, okMsg: String) {
        if (err == null) { modelLoadFailure = null; status(true, "Model ready · offline", okMsg) }
        else { modelLoadFailure = err; status(false, if (err.startsWith("This phone has")) "Model too large for this phone" else "Model error", err) }
    }
    private fun loadPath() {
        modelLoadFailure = "The model is still loading. Wait for the Model ready status before sending a message."
        status(false, "Loading model…", "Loading in memory-saving mode. This can take up to a minute the first time.")
        worker.execute {
            val err = doLoad()
            if (err == null) {
                backupModelFile.delete() // a surviving backup means the app was interrupted mid-import
                tempModelFile.delete()
                reportLoad(null, "Model loaded in stability mode (CPU).")
            } else if (backupModelFile.exists()) {
                // If a process was killed after the file swap but before validating the new
                // model, roll back automatically instead of trapping the user on a broken model.
                try { engine.close() } catch (_: Exception) {}
                modelFile.delete()
                if (backupModelFile.renameTo(modelFile)) {
                    val rollbackError = doLoad()
                    if (rollbackError == null) {
                        tempModelFile.delete()
                        reportLoad(null, "The previous model was restored after an interrupted update.")
                    } else {
                        reportLoad(rollbackError, "")
                    }
                } else {
                    reportLoad(err + " The previous model backup could not be restored.", "")
                }
            } else {
                reportLoad(err, "")
            }
        }
    }
    private fun totalRam(): Long {
        val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.totalMem
    }
    /** Native mirror of settings edited by the WebView. */
    @JavascriptInterface fun saveUserSettings(json: String) {
        try { userSettings.save(json) } catch (_: Exception) {}
    }
    @JavascriptInterface fun getUserName(): String = userSettings.userName()
    @JavascriptInterface fun isWithinActiveRoutine(): Boolean = userSettings.isWithinActiveRoutine()
    /** Speak the personalized greeting only inside a configured morning/evening window. */
    @JavascriptInterface fun triggerGreeting() {
        if (!userSettings.isWithinActiveRoutine()) return
        val hour = java.time.LocalTime.now().hour
        val greeting = EnglishTtsManager.greeting(userSettings.userName(), hour < 12)
        main.post {
            try {
                tts?.setLanguage(Locale.US)
                tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, "english-ai-greeting")
            } catch (_: Exception) {}
        }
    }


    /** Schedule/cancel the real Android alarm at 04:40. */
    @JavascriptInterface fun setMorningAlarm(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("morningAlarmEnabled", enabled).apply()
        if (enabled) {
            MorningAlarmReceiver.scheduleNext(activity, 4, 40)
            val alarmManager = activity.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                call("onMorningAlarmStatus", "true", JSONObject.quote("Alarm saved. Allow exact alarms in the Android screen that opens so it can ring at 04:40 rather than approximately."))
                main.post {
                    try {
                        val settingsIntent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        settingsIntent.data = Uri.parse("package:" + activity.packageName)
                        activity.startActivity(settingsIntent)
                    } catch (_: Exception) {}
                }
            } else {
                call("onMorningAlarmStatus", "true", JSONObject.quote("Daily 04:40 exact alarm scheduled."))
            }
        } else {
            MorningAlarmReceiver.cancel(activity)
            call("onMorningAlarmStatus", "false", JSONObject.quote("Morning alarm disabled."))
        }
    }
    fun refreshScheduledAlarm() {
        if (isMorningAlarmEnabled()) MorningAlarmReceiver.scheduleNext(activity, 4, 40)
    }

    @JavascriptInterface fun isMorningAlarmEnabled(): Boolean =
        activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE).getBoolean("morningAlarmEnabled", false)

    @JavascriptInterface fun isHotwordEnabled(): Boolean =
        activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE).getBoolean("hotwordEnabled", false)

    /** Start hotword only from the visible app after the user explicitly enables it. */
    @JavascriptInterface fun setHotwordEnabled(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
        if (!enabled) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            activity.stopService(Intent(activity, HotwordListeningService::class.java))
            call("onHotwordStatus", "false", JSONObject.quote("Voice activation stopped."))
            return
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            call("onHotwordStatus", "false", JSONObject.quote("Microphone permission is required. Grant it in Android app settings, then enable voice activation again."))
            return
        }
        try {
            prefs.edit().putBoolean("hotwordEnabled", true).apply()
            val intent = Intent(activity, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            call("onHotwordStatus", "true", JSONObject.quote("Voice activation is on. A persistent notification will remain visible while the microphone is active."))
        } catch (e: Exception) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote(e.message ?: "Could not start voice activation. Keep English AI open and try again."))
        }
    }

    /** Shown in Settings so you can confirm which build is installed. */
    @JavascriptInterface fun getInfo(): String {
        val v = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "v$v · RAM " + String.format(Locale.US, "%.1f", totalRam() / 1073741824.0) + " GB"
    }
    @Volatile private var unloaded = false
    @Volatile private var foreground = true
    private val lifecycleLock = Any()
    /** Keep lifecycle transitions ordered with the delayed background-unload task. */
    fun setForeground(isForeground: Boolean) {
        // Never make Android's main thread wait for engine.close() during app resume.
        foreground = isForeground
        if (isForeground) worker.execute {
            val shouldReload = synchronized(lifecycleLock) {
                if (unloaded) { unloaded = false; true } else false
            }
            if (shouldReload) loadPath()
        }
    }
    /** Called after the app stayed in the background: free RAM only if it is still backgrounded. */
    fun unloadForBackground() { worker.execute {
        synchronized(lifecycleLock) {
            if (!foreground && modelFile.exists() && modelLoadFailure == null) {
                engine.close()
                unloaded = true
                modelLoadFailure = "The model is reloading. Wait for Model ready."
            }
        }
        // The user may have returned while engine.close() was running. Recover on the worker.
        if (foreground) {
            val shouldReload = synchronized(lifecycleLock) {
                if (unloaded) { unloaded = false; true } else false
            }
            if (shouldReload) loadPath()
        }
    } }
    fun reloadIfUnloaded() = setForeground(true)
    fun trimMemory() { worker.execute { engine.trimConversation() } }
    @JavascriptInterface fun copyText(text: String) { main.post { try { val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("English Ai", text)) } catch (_: Exception) {} } }
    @JavascriptInterface fun openUrl(url: String) { main.post { try { if (url.startsWith("https://")) activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} } }
    /** Text of the last crash / system kill (low memory, native crash...), or "". Read once at startup by the error log. */
    @JavascriptInterface fun getLastCrash(): String {
        val sb = StringBuilder()
        try { val f = File(activity.filesDir, "last_crash.txt"); if (f.exists()) { sb.append(f.readText()); f.delete() } } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 30) try {
            val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = activity.getSharedPreferences("ea", android.content.Context.MODE_PRIVATE)
            val seen = prefs.getLong("exitTs", 0L)
            am.getHistoricalProcessExitReasons(activity.packageName, 0, 3).firstOrNull { it.timestamp > seen }?.let { r ->
                prefs.edit().putLong("exitTs", r.timestamp).apply()
                val bad = listOf(android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_SIGNALED,
                    android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
                if (r.reason in bad) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append("Previous run ended: reason code ${r.reason} (${r.description ?: "no description"}) at ${java.util.Date(r.timestamp)}") }
            }
        } catch (_: Throwable) {}
        return sb.toString().trim()
    }
    @JavascriptInterface fun pickModel() = main.post { choose() }
    /** Safe import: copy to a temp file, check size/space, swap files, and ROLL BACK to the previous model if the new one fails. */
    fun importModel(uri: Uri) { worker.execute {
        val temp = File(activity.filesDir, "model.tmp"); val backup = File(activity.filesDir, "model.bak")
        var swapped = false
        try {
            val meta = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            var expected = -1L
            meta.use { c -> if (c != null && c.moveToFirst()) { if (!c.isNull(1)) expected = c.getLong(1) } }
            val free = android.os.StatFs(activity.filesDir.absolutePath).availableBytes
            val need = (if (expected > 0) expected else 0L) + 256L * 1024 * 1024
            require(free >= need) { "Not enough storage: about ${need / 1048576} MB free is needed, only ${free / 1048576} MB available. Free some space and try again." }
            temp.delete(); backup.delete()
            activity.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Could not open selected file." }.use { src -> temp.outputStream().use { dst -> val buf = ByteArray(1024 * 1024); var n: Int; var total = 0L; var lastPct = -1; while (src.read(buf).also { n = it } > 0) { dst.write(buf, 0, n); total += n; if (expected > 0) { val pct = (total * 100 / expected).toInt(); if (pct != lastPct) { lastPct = pct; call("onModelProgress", (pct / 100.0).toString()) } } } } } }
            if (expected > 0) require(temp.length() == expected) { "The copy was interrupted (got ${temp.length() / 1048576} of ${expected / 1048576} MB). Try again." }
            require(temp.length() > 1024 * 1024) { "The selected file is too small to be a language model." }
            // The new file is complete. Now swap, keeping the old model as a backup until the new one loads.
            engine.close()
            val hadOld = modelFile.exists()
            if (hadOld) check(modelFile.renameTo(backup)) { "Could not prepare the model swap." }
            swapped = true
            check(temp.renameTo(modelFile)) { "Could not save the model in private app storage." }
            modelLoadFailure = "The model is still loading. Wait for the Model ready status before sending a message."
            status(false, "Loading model…", "New model saved (${modelFile.length() / 1048576} MB). Initializing…")
            val err = doLoad()
            if (err == null) { backup.delete(); reportLoad(null, "New model loaded.") }
            else {
                modelFile.delete()
                if (hadOld && backup.renameTo(modelFile)) {
                    val again = doLoad()
                    if (again == null) reportLoad(null, "The new file could not be used ($err). Your previous model was restored and is working.")
                    else reportLoad(again, "")
                } else reportLoad(err, "")
            }
        } catch (e: Throwable) {
            temp.delete()
            if (swapped && backup.exists()) {
                try { engine.close() } catch (_: Exception) {}
                modelFile.delete()
                backup.renameTo(modelFile)
            }
            if (modelFile.exists()) {
                val again = doLoad()
                if (again == null) reportLoad(null, "Import failed (${e.message}). Your previous model was kept and is working.")
                else reportLoad(again, "")
            } else {
                modelLoadFailure = e.message
                status(false, "Import failed", e.message ?: "Could not import model.")
            }
        }
    } }
    @JavascriptInterface fun removeModel() { worker.execute { engine.close(); modelFile.delete(); File(activity.filesDir, "model.tmp").delete(); modelLoadFailure = "The local model was removed. Import a compatible .litertlm file in Settings before starting an AI conversation."; status(false, "Demo mode", "Model removed from this phone.") } }
    @JavascriptInterface fun generate(json: String) { cancelled = false; worker.execute { try {
        if (modelLoadFailure != null) throw IllegalStateException(modelLoadFailure)
        val root = JSONObject(json); val arr = root.optJSONArray("messages") ?: JSONArray(); val messages = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) { val m = arr.getJSONObject(i); messages += Pair(m.optString("role"), m.optString("content")) }
        engine.generate(root.optString("chat"), root.optInt("count", 0), root.optString("system"), messages) { piece -> if (!cancelled) call("onToken", JSONObject.quote(piece)) }
        call("onGenDone")
    } catch (e: Exception) { if (cancelled) call("onGenDone") else call("onGenError", JSONObject.quote(e.message ?: "Model generation failed.")) } } }
    @JavascriptInterface fun cancelGenerate() { cancelled = true; engine.cancel() }
    private fun getPtTranslator(): Translator = synchronized(ptLock) {
        ptTranslator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        ).also { ptTranslator = it }
    }

    private fun withPtTranslator(onReady: (Translator?, String?) -> Unit) {
        val translator = try { getPtTranslator() } catch (e: Exception) {
            onReady(null, e.message ?: "Could not initialize Portuguese translation.")
            return
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { onReady(translator, null) }
            .addOnFailureListener { e -> onReady(null, e.message ?: "Portuguese translation model download failed.") }
    }

    @JavascriptInterface fun ptEnsure() {
        withPtTranslator { translator, error ->
            call("onPtReady", (translator != null).toString(),
                JSONObject.quote(error ?: "Portuguese translation is ready on this device."))
        }
    }

    @JavascriptInterface fun translate(id: String, text: String) {
        if (text.isBlank()) {
            call("onTranslation", JSONObject.quote(id), JSONObject.quote(""))
            return
        }
        withPtTranslator { translator, error ->
            if (translator == null) {
                call("onTranslationError", JSONObject.quote(id),
                    JSONObject.quote(error ?: "Portuguese translation is unavailable."))
            } else {
                translator.translate(text)
                    .addOnSuccessListener { result ->
                        call("onTranslation", JSONObject.quote(id), JSONObject.quote(result))
                    }
                    .addOnFailureListener { e ->
                        call("onTranslationError", JSONObject.quote(id),
                            JSONObject.quote(e.message ?: "Could not translate this reply."))
                    }
            }
        }
    }

    @JavascriptInterface fun getVoices() { refreshVoices() }
    fun refreshVoices() { val arr = JSONArray(); tts?.voices?.filter { it.locale.language == "en" }?.forEach { v -> arr.put(JSONObject().put("name", v.name).put("lang", v.locale.toLanguageTag())) }; call("onVoices", JSONObject.quote(arr.toString())) }
    @JavascriptInterface fun speak(id: String, text: String, rate: String, voiceName: String) { main.post { try { val clean = EnglishTtsManager.sanitizeText(text); if (clean.isBlank()) { call("onTtsDone", JSONObject.quote(id)); return@post }; tts?.language = Locale.US; tts?.setSpeechRate(rate.toFloatOrNull() ?: 1f); tts?.voices?.firstOrNull { it.name == voiceName && it.locale.language == "en" }?.let { tts?.voice = it }; tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id) } catch (_: Exception) { call("onTtsDone", JSONObject.quote(id)) } } }
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
                // Answer sooner after you stop talking (default waits ~2 s)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
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
    fun close() { try { recognizer?.destroy() } catch (_: Exception) {}; try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}; try { ptTranslator?.close() } catch (_: Exception) {}; ptTranslator = null; worker.shutdownNow(); engine.close() }
}
