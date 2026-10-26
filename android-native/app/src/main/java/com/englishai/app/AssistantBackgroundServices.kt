package com.englishai.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.PowerManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

private const val ASSISTANT_CHANNEL = "english_ai_assistant"
private const val ALARM_CHANNEL = "english_ai_alarm"
private const val ALARM_REQUEST = 4040
private const val HOTWORD_NOTIFICATION = 4041

private fun ensureAssistantChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ASSISTANT_CHANNEL, "English AI voice assistant", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "English AI morning alarm", NotificationManager.IMPORTANCE_HIGH))
    }
}

private fun openAssistantIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(context, 4042, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Daily 04:40 alarm. The alarm receiver never tries to open an Activity from the background. */
class MorningAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        scheduleNext(context, 4, 40)
        val service = Intent(context, AlarmGreetingService::class.java)
        try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service) } catch (_: Exception) { /* Keep the notification available if Android blocks background service start. */ }
        ensureAssistantChannels(context)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, ALARM_CHANNEL) else Notification.Builder(context)
        context.getSystemService(NotificationManager::class.java).notify(
            ALARM_REQUEST,
            notification.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Good morning!")
                .setContentText("English AI is ready. Tap to start your English practice.")
                .setContentIntent(openAssistantIntent(context))
                .setAutoCancel(true).build()
        )
    }

    companion object {
        fun scheduleNext(context: Context, hour: Int = 4, minute: Int = 40) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = PendingIntent.getBroadcast(context, ALARM_REQUEST, Intent(context, MorningAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, intent)
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, intent)
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, next.timeInMillis, intent)
            }
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = PendingIntent.getBroadcast(context, ALARM_REQUEST, Intent(context, MorningAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(intent)
        }
    }
}

/** Speaks a short English-only greeting after the scheduled alarm, then releases its service. */
class AlarmGreetingService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    override fun onCreate() {
        super.onCreate()
        ensureAssistantChannels(this)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ALARM_CHANNEL) else Notification.Builder(this)
        startForeground(ALARM_REQUEST, n.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("English AI").setContentText("Starting your morning greeting").build())
        tts = TextToSpeech(this, this)
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.92f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { stopSelf() }
                override fun onError(id: String?) { stopSelf() }
            })
            val name = getSharedPreferences("english_ai_user_settings", MODE_PRIVATE).getString("userName", "").orEmpty().take(24)
            val greeting = if (name.isBlank()) "Good morning! It's 4:40. Ready for a little English practice?" else "Good morning, $name! It's 4:40. Ready for a little English practice?"
            tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, "english-ai-morning-alarm")
        } else stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}; super.onDestroy() }
}

/**
 * User-enabled, visible foreground microphone service. Android speech recognition is used
 * as the local recognizer where available; the hotword is never listened for covertly.
 */
class HotwordListeningService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var active = false
    private var restarting = false
    private var activationHandled = false
    @Volatile private var voskActive = false
    @Volatile private var voskRecorder: AudioRecord? = null
    @Volatile private var voskModel: Model? = null
    @Volatile private var voskRecognizer: Recognizer? = null
    @Volatile private var voskThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureAssistantChannels(this)
        val stopIntent = PendingIntent.getService(this, 4043, Intent(this, HotwordListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ASSISTANT_CHANNEL) else Notification.Builder(this)
        startForeground(HOTWORD_NOTIFICATION, n.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("English AI is listening")
            .setContentText("Say “Hi, English AI!” · Tap Stop to disable the microphone")
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopIntent)
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseRecognition(); return START_STICKY }
            ACTION_RESUME, ACTION_START, null -> {
                activationHandled = false
                restarting = false
                active = true
                acquireHotwordWakeLock()
                startRecognition()
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    private fun acquireHotwordWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:Hotword").apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (_: Exception) { /* Foreground service remains active even if a device forbids wake locks. */ }
    }

    private fun releaseHotwordWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun pauseRecognition() {
        active = false
        restarting = false
        stopVoskHotword()
        releaseHotwordWakeLock()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /**
     * Prefer the app-managed Vosk model for hotword detection. Its constrained grammar
     * explicitly contains the activation phrase and runs entirely on-device.
     */
    private fun startVoskHotword() {
        if (!active || voskActive) return
        val modelDir = java.io.File(filesDir, "vosk-model-en-us")
        if (!java.io.File(modelDir, "am").isDirectory ||
            !java.io.File(modelDir, "conf").isDirectory ||
            !java.io.File(modelDir, "graph").isDirectory) return
        voskActive = true
        voskThread = Thread({
            var audio: AudioRecord? = null
            var model: Model? = null
            var grammarRecognizer: Recognizer? = null
            try {
                model = Model(modelDir.absolutePath)
                voskModel = model
                val grammar = """["hi english ai","hey english ai","hello english ai","hi english eye","hey english eye","hello english eye","[unk]"]"""
                grammarRecognizer = Recognizer(model, 16000.0f, grammar)
                voskRecognizer = grammarRecognizer
                val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "Could not initialize offline microphone audio." }
                audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 8192))
                voskRecorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Offline microphone could not be initialized." }
                audio.startRecording()
                val buffer = ShortArray(4096)
                while (active && voskActive) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val sample = buffer[i].toInt()
                        bytes[i * 2] = (sample and 0xff).toByte()
                        bytes[i * 2 + 1] = (sample shr 8).toByte()
                    }
                    val isFinal = grammarRecognizer.acceptWaveForm(bytes, bytes.size)
                    val json = if (isFinal) grammarRecognizer.result else grammarRecognizer.partialResult
                    val text = JSONObject(json).optString(if (isFinal) "text" else "partial", "").trim()
                    if (text.isNotBlank()) {
                        val bundle = android.os.Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        }
                        checkPhrase(bundle, isFinal)
                    }
                }
            } catch (e: Exception) {
                // Never fail silently or switch to a network-capable recognizer.
                if (active && voskActive) {
                    postOfflineSpeechUnavailable(e.message ?: "O Vosk não conseguiu iniciar o microfone ou carregar o modelo.")
                    active = false
                    stopSelf()
                }
            } finally {
                voskActive = false
                try { audio?.stop() } catch (_: Exception) {}
                try { audio?.release() } catch (_: Exception) {}
                try { grammarRecognizer?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
                if (voskRecorder === audio) voskRecorder = null
                if (voskRecognizer === grammarRecognizer) voskRecognizer = null
                if (voskModel === model) voskModel = null
                voskThread = null
            }
        }, "EnglishAI-Vosk-Hotword").apply { isDaemon = true; start() }
    }

    private fun stopVoskHotword() {
        voskActive = false
        try { voskRecorder?.stop() } catch (_: Exception) {}
    }

    /**
     * Hotword recognition is deliberately Vosk-only. Do not silently fall back to Android's
     * SpeechRecognizer (which may use a network provider and hides a missing Vosk model).
     */
    private fun startRecognition() {
        if (!active || restarting || voskActive) return
        val modelDir = java.io.File(filesDir, "vosk-model-en-us")
        val complete = java.io.File(modelDir, "am").isDirectory &&
            java.io.File(modelDir, "conf").isDirectory &&
            java.io.File(modelDir, "graph").isDirectory
        if (!complete) {
            postOfflineSpeechUnavailable("O modelo Vosk de inglês não está instalado. Abra o English AI, baixe o mecanismo de voz offline e ative o gatilho novamente.")
            stopSelf()
            return
        }
        startVoskHotword()
    }

    private fun checkPhrase(bundle: android.os.Bundle?, final: Boolean) {
        if (!active || activationHandled) return
        val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val match = phrases.any { phrase ->
            var p = phrase.lowercase(Locale.US)
                .replace(Regex("[^a-z ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            // The activation phrase "hi english ai" can be transcribed as "a i", "eye", or "I".
            p = p.replace(Regex("\\ba\\s+i\\b"), "ai")
                .replace(Regex("\\benglish\\s+i\\b"), "english ai")
                .replace(Regex("\\b(eye|ay)\\b"), "ai")
                .replace(Regex("\\s+"), " ")
                .trim()
            Regex("(?:^|\\s)(hi|hey|hello) english ai(?:\\s|$)").containsMatchIn(p)
        }
        if (!match) return

        // Handle the phrase once, stop recognition before opening the app, and let the
        // foreground activity own the microphone while the user speaks.
        activationHandled = true
        // When the display is off, give a brief, low-volume confirmation tone only.
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isInteractive) {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
                android.os.Handler(mainLooper).postDelayed({ tone.release() }, 220)
            }
        } catch (_: Exception) {}
        restarting = true
        stopVoskHotword()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        postHotwordNotification()

        val launch = Intent(this, MainActivity::class.java).apply {
            action = ACTION_HOTWORD_DETECTED
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try { startActivity(launch) } catch (_: Exception) {
            // The notification remains the reliable fallback on Android versions that restrict
            // starting an activity from the background.
        }
        android.os.Handler(mainLooper).postDelayed({
            // If Android blocked the activity launch, keep the hands-free trigger available.
            if (active) {
                activationHandled = false
                restarting = false
                startRecognition()
            }
        }, 4000)
    }

    private fun postHotwordNotification() {
        ensureAssistantChannels(this)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ALARM_CHANNEL) else Notification.Builder(this)
        val openIntent = PendingIntent.getActivity(
            this, 4044,
            Intent(this, MainActivity::class.java).setAction(ACTION_HOTWORD_DETECTED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(4044, n.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("English AI heard you")
            .setContentText("Tap to open the assistant and continue.")
            .setContentIntent(openIntent).setAutoCancel(true).build())
    }

    private fun postOfflineSpeechUnavailable(detail: String = "O modelo Vosk de inglês não está disponível.") {
        ensureAssistantChannels(this)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ASSISTANT_CHANNEL) else Notification.Builder(this)
        getSystemService(NotificationManager::class.java).notify(
            4045,
            n.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("English AI · gatilho offline indisponível")
                .setContentText(detail.take(180))
                .setContentIntent(openAssistantIntent(this))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun restartSoon() {
        if (!active || restarting) return
        restarting = true
        // Give Android time to release the previous offline recognizer before reopening it.
        android.os.Handler(mainLooper).postDelayed({ restarting = false; if (active) startRecognition() }, 1200)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        active = false
        getSharedPreferences("english_ai_user_settings", MODE_PRIVATE).edit().putBoolean("hotwordEnabled", false).apply()
        stopVoskHotword()
        releaseHotwordWakeLock()
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_START = "com.englishai.app.HOTWORD_START"
        const val ACTION_STOP = "com.englishai.app.HOTWORD_STOP"
        const val ACTION_PAUSE = "com.englishai.app.HOTWORD_PAUSE"
        const val ACTION_RESUME = "com.englishai.app.HOTWORD_RESUME"
        const val ACTION_HOTWORD_DETECTED = "com.englishai.app.HOTWORD_DETECTED"
    }
}


/** Restore the user's opted-in daily alarm after Android restarts. */
class AssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("english_ai_user_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("morningAlarmEnabled", false)) MorningAlarmReceiver.scheduleNext(context, 4, 40)
    }
}
