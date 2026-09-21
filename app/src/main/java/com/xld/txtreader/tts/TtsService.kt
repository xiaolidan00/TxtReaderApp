package com.xld.txtreader.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xld.txtreader.MainActivity
import com.xld.txtreader.R
import com.xld.txtreader.TxtReaderApplication
import com.xld.txtreader.appSettings
import com.xld.txtreader.eventEmitter
import com.xld.txtreader.EVENT_TTS_DONE
import com.xld.txtreader.EVENT_TTS_START
import com.xld.txtreader.EVENT_TTS_READY
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var pendingText: String? = null
    private var currentText: String? = null
    var isSpeaking = false
    var isPlaying = false
    var bookTitle = ""
    var chapterTitle = ""
    var totalChapter = 0

    private val app: TxtReaderApplication get() = application as TxtReaderApplication
    private val appSettings get() = app.appSettings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val initListener = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            Log.d("TtsService", "TTS init status=$status ok=$ok")
            if (ok) {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val fallback = listOf(Locale.CHINA, Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.ENGLISH)
                val available = tts.availableLanguages
                Log.d("TtsService", "availableLanguages=$available")
                val chosen = fallback.firstOrNull { locale -> available?.any { it.language == locale.language && it.country == locale.country } == true }
                    ?: fallback.firstOrNull { locale -> available?.any { it.language == locale.language } == true }
                    ?: Locale.getDefault()
                tts.language = chosen
                Log.d("TtsService", "language=$chosen")
                tts.setSpeechRate(appSettings.ttsSpeed)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TtsService", "onStart utterance=$utteranceId")
                        isSpeaking = true
                        eventEmitter.emit(EVENT_TTS_START)
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d("TtsService", "onDone utterance=$utteranceId")
                        onUtteranceDone()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("TtsService", "onError utterance=$utteranceId")
                        onUtteranceDone()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e("TtsService", "onError utterance=$utteranceId code=$errorCode")
                        onUtteranceDone()
                    }
                })
            }
            ready = ok
            eventEmitter.emit(EVENT_TTS_READY)
            if (ok && pendingText != null) {
                val text = pendingText
                pendingText = null
                speak(text)
            }
        }
        tts = TextToSpeech(this, initListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TtsService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            TtsController.ACTION_PLAY -> {
                val text = intent.getStringExtra("extra_text")
                bookTitle = intent.getStringExtra("extra_book_title") ?: bookTitle
                chapterTitle = intent.getStringExtra("extra_chapter_title") ?: chapterTitle
                totalChapter = intent.getIntExtra("extra_total_chapter", totalChapter)
                if (text != null) {
                    pendingText = null
                    currentText = text
                    if (ready) {
                        speak(text)
                    } else {
                        pendingText = text
                    }
                }
            }
            TtsController.ACTION_TOGGLE -> {
                if (isSpeaking) {
                    pause()
                } else {
                    val text = currentText
                    if (text != null) speak(text)
                }
            }
            TtsController.ACTION_PAUSE -> pause()
            TtsController.ACTION_PREV_PAGE -> {
                if (isSpeaking) { isSpeaking = false; tts.stop() }
            }
            TtsController.ACTION_NEXT_PAGE -> {
                if (isSpeaking) { isSpeaking = false; tts.stop() }
            }
            TtsController.ACTION_PREV_CHAPTER -> {
                if (isSpeaking) { isSpeaking = false; tts.stop() }
            }
            TtsController.ACTION_NEXT_CHAPTER -> {
                if (isSpeaking) { isSpeaking = false; tts.stop() }
            }
            TtsController.ACTION_SPEED -> {
                val speed = intent.getFloatExtra("extra_speed", appSettings.ttsSpeed)
                appSettings.ttsSpeed = speed
                if (ready) runCatching { tts.setSpeechRate(speed) }
            }
            TtsController.ACTION_STOP -> stopService()
        }
        return START_NOT_STICKY
    }

    private fun speak(text: String?) {
        if (text.isNullOrBlank()) return
        isSpeaking = true
        isPlaying = true
        goForeground()
        Log.d("TtsService", "speaking textLen=${text.length}")
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_page")
        if (result != TextToSpeech.SUCCESS) {
            Log.e("TtsService", "tts.speak failed: $result")
        }
        refreshNotification()
    }

    private fun onUtteranceDone() {
        isSpeaking = false
        eventEmitter.emit(EVENT_TTS_DONE)
    }

    private fun pause() {
        isPlaying = false
        isSpeaking = false
        runCatching { tts.stop() }
        refreshNotification()
    }

    private fun stopService() {
        isPlaying = false
        isSpeaking = false
        runCatching { tts.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "语音朗读", NotificationManager.IMPORTANCE_LOW)
        channel.description = "朗读小说时的播放控制"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val subtitle = buildString {
            if (chapterTitle.isNotEmpty()) {
                append(chapterTitle)
            } else {
                append("尚未播放")
            }
            if (totalChapter > 0) {
                append(" · ").append("朗读中")
            }
        }

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val prevPi = servicePi(101, TtsController.ACTION_PREV_CHAPTER)
        val nextPi = servicePi(102, TtsController.ACTION_NEXT_CHAPTER)
        val toggleAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "暂停", servicePi(103, TtsController.ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "播放", servicePi(103, TtsController.ACTION_TOGGLE))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(bookTitle.ifBlank { "TXT阅读器" }.take(40))
            .setContentText(subtitle)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(NotificationCompat.Action(R.drawable.ic_skip_prev, "上一章", prevPi))
            .addAction(toggleAction)
            .addAction(NotificationCompat.Action(R.drawable.ic_skip_next, "下一章", nextPi))
            .build()
    }

    private fun servicePi(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, TtsService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onDestroy() {
        ready = false
        runCatching { tts.shutdown() }
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tts_playback"
    }
}
