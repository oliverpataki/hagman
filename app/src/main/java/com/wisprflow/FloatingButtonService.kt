package com.wisprflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var resultView: View
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val floatingParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 120
        }
    }

    private val resultParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 200
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupFloatingButton()
        setupResultBubble()
        setupSpeechRecognizer()
    }

    private fun startForegroundNotification() {
        val channelId = "wispr_service"
        val channel = NotificationChannel(channelId, "Wispr Flow", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun setupFloatingButton() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        val micButton = floatingView.findViewById<ImageView>(R.id.ivMic)

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        floatingParams.x = initialX - dx
                        floatingParams.y = initialY - dy
                        windowManager.updateViewLayout(floatingView, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) startRecording()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, floatingParams)
    }

    private fun setupResultBubble() {
        resultView = LayoutInflater.from(this).inflate(R.layout.result_bubble, null)
        resultView.visibility = View.GONE

        resultView.findViewById<View>(R.id.btnInject).setOnClickListener {
            val text = resultView.findViewById<TextView>(R.id.tvResult).text.toString()
            WisprAccessibilityService.instance?.appendText(text)
            hideResult()
        }
        resultView.findViewById<View>(R.id.btnCopy).setOnClickListener {
            val text = resultView.findViewById<TextView>(R.id.tvResult).text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("wispr", text))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        resultView.findViewById<View>(R.id.btnDismiss).setOnClickListener { hideResult() }

        windowManager.addView(resultView, resultParams)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.speech_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setMicState(recording = true)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                setMicState(recording = false)
                processResult(text)
            }
            override fun onError(error: Int) {
                setMicState(recording = false)
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(this@FloatingButtonService, errorMessage(error), Toast.LENGTH_SHORT).show()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startRecording() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processResult(text: String) {
        if (prefs.aiCleanupEnabled && prefs.claudeApiKey.isNotBlank()) {
            showResult(getString(R.string.cleaning_up), loading = true)
            scope.launch {
                try {
                    val cleaned = ClaudeClient(prefs.claudeApiKey).cleanUpText(text)
                    prefs.addHistoryEntry(cleaned)
                    showResult(cleaned, loading = false)
                    sendHistoryUpdate()
                } catch (e: Exception) {
                    showResult(text, loading = false)
                }
            }
        } else {
            prefs.addHistoryEntry(text)
            showResult(text, loading = false)
            sendHistoryUpdate()
        }
    }

    private fun showResult(text: String, loading: Boolean) {
        resultView.visibility = View.VISIBLE
        resultView.findViewById<TextView>(R.id.tvResult).text = text
        val btnInject = resultView.findViewById<View>(R.id.btnInject)
        val btnCopy = resultView.findViewById<View>(R.id.btnCopy)
        btnInject.isEnabled = !loading
        btnCopy.isEnabled = !loading
    }

    private fun hideResult() {
        resultView.visibility = View.GONE
    }

    private fun setMicState(recording: Boolean) {
        val mic = floatingView.findViewById<ImageView>(R.id.ivMic)
        mic.setImageResource(
            if (recording) android.R.drawable.presence_audio_busy
            else android.R.drawable.ic_btn_speak_now
        )
    }

    private fun sendHistoryUpdate() {
        sendBroadcast(Intent("com.wisprflow.HISTORY_UPDATED"))
    }

    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> getString(R.string.error_audio)
        SpeechRecognizer.ERROR_NETWORK -> getString(R.string.error_network)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.error_timeout)
        else -> getString(R.string.error_generic)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        speechRecognizer?.destroy()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        if (::resultView.isInitialized) windowManager.removeView(resultView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
