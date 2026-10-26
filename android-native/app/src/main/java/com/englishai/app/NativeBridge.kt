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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class NativeBridge(private val activity: Activity, private val web: WebView, private val choose: () -> Unit, private val requestMic: () -> Unit, private val exportTsv: (String, String) -> Unit, private val requestHotwordPermissions: () -> Unit, private val requestVoiceTrainingPermissions: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = LiteRtEngine(activity)
    private val userSettings = UserSettings(activity)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val offlineSpeech = VoskOfflineSpeech(
        activity,
        { ok, message -> call("onOfflineSpeechModelStatus", ok.toString(), JSONObject.quote(message)) },
        { call("onSpeechReady") },
        { call("onSpeechStarted") },
        { text -> call("onSpeechPartial", JSONObject.quote(text)) },
        { text -> call("onSpeechEnd", JSONObject.quote(text)) },
        { message -> call("onSpeechError", JSONObject.quote(message)) }
    )
    @Volatile private var ptTranslator: Translator? = null
    private val ptLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var listening = false
    @Volatile private var listeningStartupPending = false
    // Retry one transient service disconnect without switching away from the offline provider.
    @Volatile private var speechDisconnectRetries = 0
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
    @JavascriptInterface fun exitApp() { main.post { activity.finish() } }
    /** Forward the English sentence currently spoken by the in-app tutor to the visual overlay. */
    @JavascriptInterface fun setOverlayCaption(text: String, charIndex: Int) {
        val clean = text.trim().take(400)
        if (clean.isEmpty()) return
        val intent = Intent(AssistantOverlayService.ACTION_TRANSCRIPT).setPackage(activity.packageName)
            .putExtra(AssistantOverlayService.EXTRA_TRANSCRIPT, clean)
            .putExtra(AssistantOverlayService.EXTRA_TRANSCRIPT_SOURCE, AssistantOverlayService.SOURCE_TUTOR)
            .putExtra(AssistantOverlayService.EXTRA_TRANSCRIPT_INDEX, charIndex.coerceIn(-1, clean.length))
            .putExtra(AssistantOverlayService.EXTRA_TRANSCRIPT_FINAL, charIndex < 0)
        try { activity.sendBroadcast(intent) } catch (_: Exception) {}
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
        if (isMorningAlarmEnabled()) {
            MorningAlarmReceiver.scheduleNext(activity, 4, 40)
            val alarmManager = activity.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                call("onMorningAlarmStatus", "true", JSONObject.quote("Daily 04:40 exact alarm scheduled."))
            }
        }
    }

    @JavascriptInterface fun isMorningAlarmEnabled(): Boolean =
        activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE).getBoolean("morningAlarmEnabled", false)

    @JavascriptInterface fun isHotwordEnabled(): Boolean =
        activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE).getBoolean("hotwordEnabled", false)

    /** Restore a previously opted-in listener when the user opens the app, never directly from BOOT_COMPLETED. */
    fun restoreHotwordIfEnabled() {
        val prefs = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hotwordEnabled", false)) return
        val micGranted = activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted || !notificationsGranted) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote("Voice activation was paused because microphone or notification permission is missing. Re-enable it in settings."))
            return
        }
        if (!offlineSpeech.isInstalled()) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote("Voice activation was paused because the Vosk model is missing. Download the offline speech engine and enable the trigger again."))
            return
        }
        try {
            val intent = Intent(activity, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            call("onHotwordStatus", "true", JSONObject.quote("Vosk offline voice activation restored while the app is open."))
        } catch (e: Exception) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote(e.message ?: "Could not restore the Vosk voice activation service."))
        }
    }

    /** Start the Vosk-only hotword service only after runtime permissions and its model are ready. */
    @JavascriptInterface fun setHotwordEnabled(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
        if (!enabled) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            activity.stopService(Intent(activity, HotwordListeningService::class.java))
            call("onHotwordStatus", "false", JSONObject.quote("Voice activation stopped."))
            return
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            call("onHotwordStatus", "false", JSONObject.quote("Android will ask for microphone and notification permissions before enabling the offline trigger."))
            main.post { requestHotwordPermissions() }
            return
        }
        if (!offlineSpeech.isInstalled()) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote("The “Hi, English AI” trigger requires the Vosk offline English model. Tap “Download offline speech engine” first (one-time download, about 40 MB), then enable the trigger."))
            return
        }
        try {
            prefs.edit().putBoolean("hotwordEnabled", true).apply()
            val intent = Intent(activity, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            call("onHotwordStatus", "true", JSONObject.quote("Vosk offline trigger enabled. Keep the persistent notification visible; microphone audio stays on this device."))
        } catch (e: Exception) {
            prefs.edit().putBoolean("hotwordEnabled", false).apply()
            call("onHotwordStatus", "false", JSONObject.quote(e.message ?: "Could not start the Vosk hotword service. Keep English AI open and try again."))
        }
    }

    fun voiceTrainingPermissionDenied() {
        call("onVoiceCalibrationStatus", "false", JSONObject.quote("Permissão de microfone/notificações negada. Autorize nas configurações do Android e tente novamente."))
    }

    fun hotwordPermissionDenied() {
        activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("hotwordEnabled", false).apply()
        call("onHotwordStatus", "false", JSONObject.quote("Voice activation was not enabled because the required microphone/notification permission was denied."))
    }

    /** JS entry point for the Train Voice button. */
    @JavascriptInterface fun startVoiceTraining() = startVoiceCalibration()

    /** Starts on-device trigger calibration; the permission callback resumes this flow automatically. */
    @JavascriptInterface fun startVoiceCalibration() {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            call("onVoiceCalibrationStatus", "false", JSONObject.quote("Solicitando permissões de microfone/notificações…"))
            main.post { requestVoiceTrainingPermissions() }
            return
        }
        startVoiceCalibrationGranted()
    }

    fun startVoiceCalibrationGranted() {
        android.util.Log.d("EnglishAI-VoiceTraining", "Permission granted; checking Vosk model and overlay permission")
        if (!offlineSpeech.isInstalled()) {
            android.util.Log.e("EnglishAI-VoiceTraining", "Vosk model missing in internal storage and no bundled asset could be installed")
            call("onVoiceCalibrationStatus", "false", JSONObject.quote("Modelo Vosk ausente. Baixe o mecanismo offline primeiro; se o modelo estiver empacotado nos assets, ele será instalado automaticamente."))
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(activity)) {
            call("onVoiceCalibrationStatus", "false", JSONObject.quote("Enable Display over other apps for English AI, return here, and tap Train again."))
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
                activity.startActivity(intent)
            } catch (_: Exception) {}
            return
        }
        try {
            val prefs = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("hotwordEnabled", true).apply()
            android.util.Log.d("EnglishAI-VoiceTraining", "Starting HotwordListeningService ACTION_CALIBRATE")
            val intent = Intent(activity, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_CALIBRATE)
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            call("onVoiceCalibrationStatus", "false", JSONObject.quote("Listening for “Hi English AI”… say the phrase clearly."))
        } catch (e: Exception) {
            android.util.Log.e("EnglishAI-VoiceTraining", "Could not start calibration service", e)
            call("onVoiceCalibrationStatus", "false", JSONObject.quote(e.message ?: "Could not start calibration."))
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
    /** Private on-device persistence for vocabulary and spaced-repetition progress. */
    @JavascriptInterface fun getVocabularyData(): String {
        return activity.getSharedPreferences("english_ai_vocabulary", Activity.MODE_PRIVATE)
            .getString("cards", "[]") ?: "[]"
    }
    @JavascriptInterface fun saveVocabularyData(json: String) {
        if (json.length > 1_500_000) return
        try {
            JSONArray(json)
            activity.getSharedPreferences("english_ai_vocabulary", Activity.MODE_PRIVATE)
                .edit().putString("cards", json).apply()
        } catch (_: Exception) { /* Never overwrite a previously valid saved deck with malformed data. */ }
    }
    @JavascriptInterface fun exportAnkiTsv(tsv: String, filename: String) {
        if (tsv.length > 5_000_000 || !filename.matches(Regex("english-ai-anki-[0-9-]+\\.tsv"))) {
            call("onAnkiExportStatus", "false", JSONObject.quote("O arquivo TSV é inválido ou excede o limite permitido."))
            return
        }
        main.post { try { exportTsv(tsv, filename) } catch (e: Exception) {
            call("onAnkiExportStatus", "false", JSONObject.quote(e.message ?: "Não foi possível abrir o salvamento do TSV."))
        } }
    }
    fun onAnkiExportResult(success: Boolean, message: String) {
        call("onAnkiExportStatus", success.toString(), JSONObject.quote(message))
    }
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
    /** Download only pinned, publicly accessible LiteRT-LM bundles. SHA-256 is checked before the existing safe model swap. */
    @JavascriptInterface fun downloadModel(modelId: String) { worker.execute {
        data class CatalogModel(val name: String, val url: String, val sha256: String, val minBytes: Long)
        val catalog = mapOf(
            "gemma4-e2b" to CatalogModel(
                "Gemma 4 E2B",
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                2_300_000_000L
            ),
            "gemma4-e4b" to CatalogModel(
                "Gemma 4 E4B",
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
                3_300_000_000L
            )
        )
        val item = catalog[modelId]
        if (item == null) {
            status(false, "Download unavailable", "This model is not in the verified direct-download catalog. Open its official model card and check license/compatibility first.")
            return@execute
        }
        val temp = File(activity.filesDir, "model.tmp")
        val backup = File(activity.filesDir, "model.bak")
        var connection: HttpURLConnection? = null
        var swapped = false
        try {
            val free = android.os.StatFs(activity.filesDir.absolutePath).availableBytes
            val existingBytes = if (modelFile.exists()) modelFile.length() else 0L
            val required = item.minBytes + existingBytes + 256L * 1024 * 1024
            require(free >= required) {
                "Not enough storage for ${item.name}. Need approximately ${required / 1_000_000_000.0} GB free; available ${free / 1_000_000_000.0} GB."
            }
            temp.delete()
            status(false, "Downloading model…", "Downloading ${item.name}. Keep the app open and connected to the internet.")
            connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EnglishAI-Android/1.3.4")
            }
            connection.connect()
            val code = connection.responseCode
            require(code in 200..299) { "Model server returned HTTP $code. Check your connection and try again." }
            val total = connection.contentLengthLong
            if (total > 0) require(total >= item.minBytes) { "The server returned a file smaller than the verified model. Download cancelled." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPct = -1
            connection.inputStream.use { input ->
                temp.outputStream().buffered(1024 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        if (total > 0) {
                            val pct = ((received * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                call("onModelDownloadProgress", (pct / 100.0).toString(), received.toString(), total.toString(), JSONObject.quote(item.name))
                            }
                        } else if (received % (8L * 1024 * 1024) < buffer.size) {
                            call("onModelDownloadProgress", "0", received.toString(), "-1", JSONObject.quote(item.name))
                        }
                    }
                }
            }
            require(received >= item.minBytes) { "Download incomplete: received ${received / 1_000_000} MB. Your previous model was not changed. Please retry." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(item.sha256, ignoreCase = true)) {
                "Integrity check failed (SHA-256 mismatch). The downloaded file was discarded and your previous model was kept."
            }
            call("onModelDownloadProgress", "1", received.toString(), total.toString(), JSONObject.quote(item.name))
            // Commit only after the whole download and hash have been verified.
            try { engine.close() } catch (_: Exception) {}
            backup.delete()
            val hadOld = modelFile.exists()
            if (hadOld) check(modelFile.renameTo(backup)) { "Could not protect the existing model before installing the download." }
            swapped = true
            check(temp.renameTo(modelFile)) { "Could not commit the downloaded model to private app storage." }
            modelLoadFailure = "The model is still loading. Wait for Model ready before sending a message."
            status(false, "Loading model…", "${item.name} downloaded and verified. Initializing LiteRT-LM…")
            val loadError = doLoad()
            if (loadError == null) {
                backup.delete()
                modelLoadFailure = null
                reportLoad(null, "${item.name} downloaded, SHA-256 verified and loaded for offline use.")
            } else {
                try { engine.close() } catch (_: Exception) {}
                modelFile.delete()
                if (hadOld && backup.renameTo(modelFile)) {
                    val restoreError = doLoad()
                    if (restoreError == null) reportLoad(null, "The new model could not load ($loadError). The previous working model was restored.")
                    else reportLoad(restoreError, "")
                } else {
                    modelLoadFailure = loadError
                    status(false, "Model could not load", "$loadError")
                }
            }
        } catch (e: Throwable) {
            temp.delete()
            if (swapped && backup.exists()) {
                try { engine.close() } catch (_: Exception) {}
                modelFile.delete()
                if (backup.renameTo(modelFile)) {
                    val restoreError = doLoad()
                    if (restoreError == null) reportLoad(null, "Download/install failed (${e.message}). Your previous model was restored.")
                    else reportLoad(restoreError, "")
                } else {
                    status(false, "Download failed", "${e.message ?: "Could not download model."} The backup could not be restored automatically.")
                }
            } else {
                status(false, "Download failed", e.message ?: "The connection was interrupted. Your current model was not changed; please retry.")
            }
        } finally {
            connection?.disconnect()
            if (!swapped) temp.delete()
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
    @JavascriptInterface fun downloadOfflineSpeechModel() { offlineSpeech.downloadModel() }
    @JavascriptInterface fun isOfflineSpeechModelInstalled(): Boolean = offlineSpeech.isInstalled()
    @JavascriptInterface fun startListening() { main.post { requestMic() } }
    fun startListeningGranted() {
        main.post {
            val hotwordEnabled = activity.getSharedPreferences("english_ai_user_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("hotwordEnabled", false)
            if (hotwordEnabled) {
                // Let the service process ACTION_PAUSE and release AudioRecord before opening Vosk
                // for the user's actual utterance. Opening both recorders concurrently loses the mic.
                try { activity.startService(Intent(activity, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_PAUSE)) } catch (_: Exception) {}
                main.postDelayed({ startListeningAfterHotwordPause() }, 350)
                return@post
            }
            startListeningAfterHotwordPause()
        }
    }

    private fun startListeningAfterHotwordPause() {
        if (listening || listeningStartupPending) return
        speechDisconnectRetries = 0
        // Prefer the separately installed Vosk engine. It does not use Android's speech service.
        if (offlineSpeech.isInstalled()) {
            try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            try { tts?.stop() } catch (_: Exception) {}
            listeningStartupPending = false
            offlineSpeech.start()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            call("onSpeechError", JSONObject.quote("No speech recognition service is available. Check Android's Speech Recognition settings."))
            return
        }
        try { tts?.stop() } catch (_: Exception) {}
        listeningStartupPending = true
        main.postDelayed({
            if (listening) { listeningStartupPending = false; return@postDelayed }
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                beginListening(useOnDevice = true)
            } else {
                beginListening(useOnDevice = false)
            }
        }, 180)
    }

    private fun beginListening(useOnDevice: Boolean) {
        if (listening) { listeningStartupPending = false; return }
        try {
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            listeningStartupPending = false
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
                // Android normally delivers onResults/onError after onEndOfSpeech. Keep the lock
                // until that terminal callback so a second recognizer cannot overlap the first.
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // Android speech services can disconnect transiently during startup.
                    // Recreate the SAME recognizer once; never switch to a cloud-capable provider.
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED &&
                        speechDisconnectRetries < 1 && !cancelled) {
                        speechDisconnectRetries++
                        listening = false
                        listeningStartupPending = true
                        main.postDelayed({
                            if (listeningStartupPending && !listening) beginListening(useOnDevice)
                        }, 350)
                        return
                    }
                    listening = false
                    listeningStartupPending = false
                    val detail = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android denied microphone access."
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition is unavailable on this phone. Install/update the Google app and Speech Recognition service, then download English (United States) speech/language data in their settings. You can still type your answer."
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android's speech recognition service disconnected. Check or update the Speech Recognition service in system settings. You can still type your answer."
                            SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that phrase. Move closer to the microphone, speak clearly in English after the listening screen appears, and try again. You can also type your answer."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear speech. Tap the microphone and start speaking when the listening screen appears. You can also type your answer."
                            SpeechRecognizer.ERROR_AUDIO -> "The microphone audio could not be captured. Check microphone permission and make sure another app isn't using the microphone. You can also type your answer."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Wait a moment and try the microphone again, or type your answer."
                        else -> "Speech recognition couldn't complete (code $error). Check microphone permission and the English (United States) offline speech data, then try again or type your answer."
                    }
                    call("onSpeechError", JSONObject.quote(detail))
                }
                override fun onResults(results: android.os.Bundle?) {
                    listening = false
                    listeningStartupPending = false
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
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // Answer sooner after you stop talking (default waits ~2 s)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            listeningStartupPending = false
            call("onSpeechError", JSONObject.quote(e.message ?: "Could not start microphone."))
        }
    }
    @JavascriptInterface fun stopListening() { main.post {
        if (offlineSpeech.isInstalled()) offlineSpeech.stop(false)
        else try { recognizer?.stopListening() } catch (_: Exception) {}
    } }
    @JavascriptInterface fun cancelListening() { main.post {
        listening = false; listeningStartupPending = false
        if (offlineSpeech.isInstalled()) offlineSpeech.stop(true)
        try { recognizer?.cancel() } catch (_: Exception) {}
    } }
    fun speechError(message: String) = call("onSpeechError", JSONObject.quote(message))
    fun close() { offlineSpeech.close(); try { recognizer?.destroy() } catch (_: Exception) {}; try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}; try { ptTranslator?.close() } catch (_: Exception) {}; ptTranslator = null; worker.shutdownNow(); engine.close() }
}
