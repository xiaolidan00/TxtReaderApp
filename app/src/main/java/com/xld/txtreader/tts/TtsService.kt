package com.xld.txtreader.tts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.xld.txtreader.MainActivity
import com.xld.txtreader.R
import com.xld.txtreader.TxtReaderApplication
import com.xld.txtreader.appSettings
import com.xld.txtreader.core.Sentence
import com.xld.txtreader.core.SentenceSegmenter
import com.xld.txtreader.eventEmitter
import com.xld.txtreader.EVENT_TTS_DONE
import com.xld.txtreader.EVENT_TTS_NEXT_CHAPTER
import com.xld.txtreader.EVENT_TTS_PAUSED
import com.xld.txtreader.EVENT_TTS_PREV_CHAPTER
import com.xld.txtreader.EVENT_TTS_READY
import com.xld.txtreader.EVENT_TTS_SENTENCE_START
import com.xld.txtreader.EVENT_TTS_START
import com.xld.txtreader.EVENT_TTS_STOPPED
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var ready = false
    private var pendingText: String? = null
    private var pendingSentenceIndex: Int = 0
    private var currentText: String? = null
    var isSpeaking = false
    private var pendingStop = false
    private var paused = false
    private var sentenceList: List<Sentence> = emptyList()
    private var sentenceIndex = 0
    private lateinit var mediaSession: MediaSessionCompat
    private var bookTitle = ""
    private var chapterNumber = 0
    private var totalChapters = 0
    private var filePath: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (isSpeaking) {
                    val text = currentText
                    if (text != null && ready) {
                        pendingStop = true
                        runCatching { tts.stop() }
                        pendingStop = false
                        pendingText = text
                        pendingSentenceIndex = sentenceIndex
                        speakPage(text, sentenceIndex)
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesRemoved(removedDevices)
            if (removedDevices.any { isExternalOutputDevice(it.type) }) {
                onAudioDeviceLost()
            }
        }
    }

    private val app: TxtReaderApplication get() = application as TxtReaderApplication
    private val appSettings get() = app.appSettings

    private val notificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "txtreader_tts"
        const val NOTIFICATION_ID = 1
        private const val REQUEST_PREV = 100
        private const val REQUEST_TOGGLE = 101
        private const val REQUEST_NEXT = 102
        private const val REQUEST_OPEN = 103
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification(false))
        updatePlaybackState(false)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        registerDeviceMonitoring()
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
                val chosen =Locale.getDefault()
                tts.language = chosen
                Log.d("TtsService", "language=$chosen")
                tts.setSpeechRate(appSettings.ttsSpeed)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TtsService", "onStart utterance=$utteranceId")
                        isSpeaking = true
                        eventEmitter.emit(EVENT_TTS_START)
                        updateNotification(true)
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
                speakPage(text, pendingSentenceIndex)
            }
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TxtReaderApp::TtsWakeLock")
                .apply {
                    setReferenceCounted(false)
                }
        tts = TextToSpeech(this, initListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(isSpeaking))
        Log.d("TtsService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            TtsController.ACTION_PLAY -> {
                val text = intent.getStringExtra(TtsController.EXTRA_TEXT)
                val startIndex = intent.getIntExtra(TtsController.EXTRA_SENTENCE_INDEX, 0)
                intent.getStringExtra(TtsController.EXTRA_BOOK_TITLE)?.let { bookTitle = it }
                intent.getIntExtra(TtsController.EXTRA_CHAPTER_NUMBER, chapterNumber)
                    .takeIf { it > 0 }?.let { chapterNumber = it }
                intent.getIntExtra(TtsController.EXTRA_TOTAL_CHAPTERS, totalChapters)
                    .takeIf { it > 0 }?.let { totalChapters = it }
                intent.getStringExtra(TtsController.EXTRA_FILE_PATH)?.let { filePath = it }
                if (text != null) {
                    pendingStop = false
                    currentText = text
                    pendingSentenceIndex = startIndex
                    if (ready) {
                        speakPage(text, startIndex)
                    } else {
                        pendingText = text
                    }
                    updateNotification(isSpeaking)
                }
            }

            TtsController.ACTION_TOGGLE -> {
                if (isSpeaking) {
                    pause()
                } else {
                    resumePlayback()
                }
            }

            TtsController.ACTION_PAUSE -> pause()
            TtsController.ACTION_PREV_PAGE -> { /* 不再停止，保留 currentText 供自动续读 */
            }

            TtsController.ACTION_NEXT_PAGE -> { /* 不再停止，保留 currentText 供自动续读 */
            }

            TtsController.ACTION_PREV_CHAPTER -> {
                eventEmitter.emit(EVENT_TTS_PREV_CHAPTER)
            }

            TtsController.ACTION_NEXT_CHAPTER -> {
                eventEmitter.emit(EVENT_TTS_NEXT_CHAPTER)
            }

            TtsController.ACTION_SPEED -> {
                val speed = intent.getFloatExtra(TtsController.EXTRA_SPEED, appSettings.ttsSpeed)
                appSettings.ttsSpeed = speed
                if (ready) runCatching { tts.setSpeechRate(speed) }
            }

            TtsController.ACTION_STOP -> stopService()
        }
        return START_NOT_STICKY
    }

    private fun resumePlayback() {
        val text = currentText ?: return
        if (!ready) {
            pendingText = text
            pendingSentenceIndex = sentenceIndex
            return
        }
        speakPage(text, sentenceIndex)
    }

    private fun speakPage(text: String?, startIndex: Int = 0) {
        if (text.isNullOrBlank()) return
        pendingStop = false
        paused = false
        if (!wakeLock.isHeld) runCatching { wakeLock.acquire() }
        sentenceList = SentenceSegmenter.split(text)
        sentenceIndex = startIndex
        if (sentenceList.isEmpty()) {
            eventEmitter.emit(EVENT_TTS_DONE)
            return
        }
        speakNextChunk()
    }

    private fun speakNextChunk() {
        if (sentenceIndex >= sentenceList.size) {
            onPageDone()
            return
        }
        val sentence = sentenceList[sentenceIndex]
        Log.d("TtsService", "speaking chunk $sentenceIndex: ${sentence.text.take(30)}...")
        eventEmitter.emit(EVENT_TTS_SENTENCE_START, sentenceIndex)
        tts.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, null, "tts_sentence_${sentenceIndex}")
        sentenceIndex++
        isSpeaking = true
        updateNotification(true)
    }

    private fun onUtteranceDone() {
        if (pendingStop || paused) {
            pendingStop = false
            return
        }
        if (sentenceIndex < sentenceList.size) {
            speakNextChunk()
        } else {
            onPageDone()
        }
    }

    private fun onPageDone() {
        isSpeaking = false
        if (wakeLock.isHeld) wakeLock.release()
        sentenceList = emptyList()
        sentenceIndex = 0
        eventEmitter.emit(EVENT_TTS_DONE)
        updateNotification(false)
    }

    private fun pause() {
        paused = true
        if (isSpeaking && sentenceIndex > 0) sentenceIndex--
        isSpeaking = false
        pendingStop = true
        runCatching { tts.stop() }
        if (wakeLock.isHeld) wakeLock.release()
        eventEmitter.emit(EVENT_TTS_PAUSED)
        updateNotification(false)
    }

    private fun stopService() {
        paused = false
        isSpeaking = false
        pendingStop = true
        runCatching { tts.stop() }
        if (wakeLock.isHeld) wakeLock.release()
        eventEmitter.emit(EVENT_TTS_STOPPED)
        updateNotification(false)
        stopSelf()
    }

    private fun registerDeviceMonitoring() {
        runCatching { audioManager.registerAudioDeviceCallback(audioDeviceCallback, null) }
        runCatching {
            registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun onAudioDeviceLost() {
        Log.d("TtsService", "audio device lost, stopping playback")
        if (!isSpeaking) return
        stopService()
    }

    private fun isExternalOutputDevice(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true

        else -> false
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "TtsService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!isSpeaking) resumePlayback()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    eventEmitter.emit(EVENT_TTS_NEXT_CHAPTER)
                }

                override fun onSkipToPrevious() {
                    eventEmitter.emit(EVENT_TTS_PREV_CHAPTER)
                }
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS朗读",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "文本朗读播放控制"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun chapterProgressText(): String =
        if (totalChapters > 0) "第 $chapterNumber 章 / 共 $totalChapters 章" else "正在准备朗读"

    private fun buildNotification(playing: Boolean): Notification {
        val prevIntent =
            Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_PREV_CHAPTER }
        val toggleIntent =
            Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_TOGGLE }
        val nextIntent =
            Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_NEXT_CHAPTER }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_READER
            filePath?.let { putExtra(MainActivity.EXTRA_FILE_PATH, it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val prevPendingIntent = PendingIntent.getService(
            this,
            REQUEST_PREV,
            prevIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextPendingIntent = PendingIntent.getService(
            this,
            REQUEST_NEXT,
            nextIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession.setSessionActivity(openPendingIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(bookTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(chapterProgressText())
            .setContentIntent(openPendingIntent)
            .setSmallIcon(R.drawable.ic_speaker)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_prev, "上一章", prevPendingIntent)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                togglePendingIntent
            )
            .addAction(R.drawable.ic_skip_next, "下一章", nextPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updatePlaybackState(playing: Boolean) {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, bookTitle.ifBlank { getString(R.string.app_name) })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chapterProgressText())
                .build()
        )
    }

    private fun updateNotification(playing: Boolean) {
        updatePlaybackState(playing)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(playing))
    }

    override fun onDestroy() {
        stopForeground(2) // STOP_FOREGROUND_REMOVE
        ready = false
        pendingStop = true
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        runCatching { unregisterReceiver(noisyReceiver) }
        if (::audioManager.isInitialized) {
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        }
        if (::tts.isInitialized) runCatching { tts.shutdown() }
        if (::mediaSession.isInitialized) {
            runCatching {
                mediaSession.isActive = false
                mediaSession.release()
            }
        }
    }
}