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
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Calendar
import java.util.Locale

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
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
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
        active = true
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        startRecognition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun startRecognition() {
        if (!active || restarting) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return }
        try {
            recognizer?.destroy()
            recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(results: android.os.Bundle?) { checkPhrase(results, false) }
                override fun onResults(results: android.os.Bundle?) { checkPhrase(results, true); restartSoon() }
                override fun onError(error: Int) { restartSoon() }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) { restartSoon() }
    }

    private fun checkPhrase(bundle: android.os.Bundle?, final: Boolean) {
        val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val match = phrases.any { phrase ->
            val p = phrase.lowercase(Locale.US).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            p.contains("hi english ai") || p.contains("hey english ai") || p.contains("hello english ai") ||
                p.contains("hi english a i") || p.contains("hey english a i")
        }
        if (match) {
            postHotwordNotification()
            try { tts?.speak("I'm here. Tap the notification to start our conversation.", TextToSpeech.QUEUE_FLUSH, null, "hotword-ack") } catch (_: Exception) {}
            // Avoid repeated activations while the user hears the acknowledgement.
            restarting = true
            android.os.Handler(mainLooper).postDelayed({ restarting = false; startRecognition() }, 2500)
        }
    }

    private fun postHotwordNotification() {
        ensureAssistantChannels(this)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ALARM_CHANNEL) else Notification.Builder(this)
        getSystemService(NotificationManager::class.java).notify(4044, n.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("English AI heard you")
            .setContentText("Tap to open the assistant and continue.")
            .setContentIntent(openAssistantIntent(this)).setAutoCancel(true).build())
    }

    private fun restartSoon() {
        if (!active || restarting) return
        restarting = true
        android.os.Handler(mainLooper).postDelayed({ restarting = false; if (active) startRecognition() }, 700)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        active = false
        getSharedPreferences("english_ai_user_settings", MODE_PRIVATE).edit().putBoolean("hotwordEnabled", false).apply()
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val ACTION_START = "com.englishai.app.HOTWORD_START"; const val ACTION_STOP = "com.englishai.app.HOTWORD_STOP" }
}


/** Restore the user's opted-in daily alarm after Android restarts. */
class AssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("english_ai_user_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("morningAlarmEnabled", false)) MorningAlarmReceiver.scheduleNext(context, 4, 40)
    }
}
