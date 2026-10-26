package com.englishai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import kotlin.math.max
import kotlin.math.min

/** User-invoked, transparent system overlay. Requires the Android "display over other apps" grant. */
class AssistantOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var orb: VoiceOrb? = null
    private val main = Handler(Looper.getMainLooper())
    private var amplitude = 0f

    override fun onCreate() {
        super.onCreate()
        // Visual-only overlay. It must not start a microphone foreground service
        // from a background hotword callback on Android 14+.
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) { removeOverlay(); stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (root == null) showOverlay()
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 85)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 125)
            main.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 220)
        } catch (_: Exception) {}
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val container = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val sphere = VoiceOrb(this) { amplitude }.also { orb = it }
        val sphereParams = FrameLayout.LayoutParams(320, 360, Gravity.CENTER)
        container.addView(sphere, sphereParams)

        val title = TextView(this).apply {
            text = "I'm ready!"
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        container.addView(title, FrameLayout.LayoutParams(-1, 54, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })

        val close = TextView(this).apply {
            text = "×"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.argb(65, 255, 255, 255), 28)
            setOnClickListener { removeOverlay(); stopSelf() }
        }
        container.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(34); rightMargin = dp(22) })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8)); background = rounded(Color.argb(210, 24, 24, 35), 30)
        }
        val input = EditText(this).apply {
            hint = "Type in English…"; setHintTextColor(Color.LTGRAY); setTextColor(Color.WHITE)
            textSize = 16f; setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setBackgroundColor(Color.TRANSPARENT); setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        val send = Button(this).apply {
            text = "Send"; setTextColor(Color.WHITE); textSize = 14f
            background = rounded(Color.rgb(116, 91, 255), 24)
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) {
                    val launch = Intent(this@AssistantOverlayService, MainActivity::class.java).apply {
                        action = ACTION_OVERLAY_TEXT
                        putExtra(EXTRA_OVERLAY_TEXT, message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try { startActivity(launch) } catch (_: Exception) {}
                    removeOverlay(); stopSelf()
                }
            }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(76), dp(46)))
        container.addView(composer, FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM).apply { leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(26) })

        root = container
        val type = if (Build.VERSION.SDK_INT >= 26) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE
        val params = LayoutParams(-1, -1, type,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        try { windowManager?.addView(container, params) } catch (_: Exception) { root = null; stopSelf() }
    }



    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.argb(70,255,255,255)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun removeOverlay() {
        try { root?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        root = null; orb = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        removeOverlay()
        val prefs = getSharedPreferences("english_ai_user_settings", MODE_PRIVATE)
        if (prefs.getBoolean("hotwordEnabled", false)) {
            try {
                val restart = Intent(this, HotwordListeningService::class.java).setAction(HotwordListeningService.ACTION_RESUME)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private class VoiceOrb(context: Context, private val level: () -> Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val pulse = 1f + level() * .25f + kotlin.math.sin(phase) * .025f
            val r = min(width, height) * .31f * pulse
            paint.shader = RadialGradient(cx-r*.18f, cy-r*.24f, r*1.45f,
                intArrayOf(Color.rgb(220,205,255), Color.rgb(114,80,255), Color.rgb(29,13,76), Color.TRANSPARENT),
                floatArrayOf(0f,.28f,.72f,1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r*1.5f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f + level()*7f
            paint.color = Color.argb((75+level()*140).toInt().coerceIn(0,220), 185,160,255)
            canvas.drawCircle(cx, cy, r*(1.22f + level()*.3f), paint)
            paint.style = Paint.Style.FILL
            phase += .045f + level()*.06f
            postInvalidateDelayed(32)
        }
    }
    companion object {
        const val ACTION_SHOW = "com.englishai.app.OVERLAY_SHOW"
        const val ACTION_CLOSE = "com.englishai.app.OVERLAY_CLOSE"
        const val ACTION_OVERLAY_TEXT = "com.englishai.app.OVERLAY_TEXT"
        const val EXTRA_OVERLAY_TEXT = "com.englishai.app.OVERLAY_TEXT_VALUE"
    }
}
