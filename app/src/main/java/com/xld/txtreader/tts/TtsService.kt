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
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xld.txtreader.R
import com.xld.txtreader.TxtReaderApplication
import com.xld.txtreader.appSettings
import com.xld.txtreader.core.SentenceSegmenter
import com.xld.txtreader.eventEmitter
import com.xld.txtreader.EVENT_TTS_DONE
import com.xld.txtreader.EVENT_TTS_START
import com.xld.txtreader.EVENT_TTS_READY
import com.xld.txtreader.EVENT_TTS_STOPPED
import com.xld.txtreader.EVENT_TTS_SENTENCE_START
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var ready = false
    private var pendingText: String? = null
    private var currentText: String? = null
    var isSpeaking = false
    private var pendingStop = false
    private var sentenceList: List<com.xld.txtreader.core.Sentence> = emptyList()
    private var sentenceIndex = 0
    private var chunkList: List<String> = emptyList()
    private var chunkSentenceIndices: List<Int> = emptyList()
    private var chunkIndex = 0

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioDeviceLost()
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
        private const val REQUEST_STOP = 103
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification(false))
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
                speakPage(text)
            }
        }
        tts = TextToSpeech(this, initListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TtsService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            TtsController.ACTION_PLAY -> {
                val text = intent.getStringExtra("extra_text")
                if (text != null) {
                    pendingStop = false
                    currentText = text
                    if (ready) {
                        speakPage(text)
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
                    if (text != null) speakPage(text)
                }
            }
            TtsController.ACTION_PAUSE -> pause()
            TtsController.ACTION_PREV_PAGE -> { /* 不再停止，保留 currentText 供自动续读 */ }
            TtsController.ACTION_NEXT_PAGE -> { /* 不再停止，保留 currentText 供自动续读 */ }
            TtsController.ACTION_PREV_CHAPTER -> { /* 不再停止，保留 currentText 供自动续读 */ }
            TtsController.ACTION_NEXT_CHAPTER -> { /* 不再停止，保留 currentText 供自动续读 */ }
            TtsController.ACTION_SPEED -> {
                val speed = intent.getFloatExtra("extra_speed", appSettings.ttsSpeed)
                appSettings.ttsSpeed = speed
                if (ready) runCatching { tts.setSpeechRate(speed) }
            }
            TtsController.ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    private fun speakPage(text: String?) {
        if (text.isNullOrBlank()) return
        pendingStop = false
        sentenceList = SentenceSegmenter.split(text)
        sentenceIndex = 0
        if (sentenceList.isEmpty()) {
            eventEmitter.emit(EVENT_TTS_DONE)
            return
        }
        val segments = createTTSSegments(sentenceList)
        chunkList = segments.first
        chunkSentenceIndices = segments.second
        chunkIndex = 0
        if (chunkList.isEmpty() || chunkList[0].isBlank()) {
            eventEmitter.emit(EVENT_TTS_DONE)
            return
        }
        speakNextChunk()
    }

    private fun createTTSSegments(sentences: List<com.xld.txtreader.core.Sentence>): Pair<List<String>, List<Int>> {
        val chunks = mutableListOf<String>()
        val firstIndices = mutableListOf<Int>()
        val current = StringBuilder()
        var firstIndex = 0
        for ((i, sentence) in sentences.withIndex()) {
            val text = sentence.text
            if (text.length > 200) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    firstIndices.add(firstIndex)
                }
                var offset = 0
                while (offset < text.length) {
                    val end = minOf(offset + 200, text.length)
                    chunks.add(text.substring(offset, end))
                    firstIndices.add(i)
                    offset = end
                }
                firstIndex = i + 1
            } else if (current.length + text.length > 200) {
                chunks.add(current.toString().trim())
                firstIndices.add(firstIndex)
                current.setLength(0)
                current.append(text)
                firstIndex = i
            } else {
                if (current.isEmpty()) firstIndex = i
                current.append(text)
            }
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
            firstIndices.add(firstIndex)
        }
        return chunks.ifEmpty { listOf("") } to firstIndices.ifEmpty { listOf(0) }
    }

    private fun speakNextChunk() {
        if (chunkIndex >= chunkList.size) {
            onPageDone()
            return
        }
        val chunkText = chunkList[chunkIndex]
        val sentenceIdx = chunkSentenceIndices.getOrNull(chunkIndex) ?: 0
        Log.d("TtsService", "speaking chunk $chunkIndex: ${chunkText.take(30)}...")
        eventEmitter.emit(EVENT_TTS_SENTENCE_START, sentenceIdx)
        tts.speak(chunkText, TextToSpeech.QUEUE_FLUSH, null, "tts_chunk_${chunkIndex}")
        chunkIndex++
        isSpeaking = true
        updateNotification(true)
    }

    private fun onUtteranceDone() {
        if (pendingStop) {
            pendingStop = false
            eventEmitter.emit(EVENT_TTS_DONE)
            return
        }
        if (chunkIndex < chunkList.size) {
            speakNextChunk()
        } else {
            onPageDone()
        }
    }

    private fun onPageDone() {
        isSpeaking = false
        sentenceList = emptyList()
        sentenceIndex = 0
        chunkList = emptyList()
        chunkSentenceIndices = emptyList()
        chunkIndex = 0
        eventEmitter.emit(EVENT_TTS_DONE)
        updateNotification(false)
    }

    private fun pause() {
        isSpeaking = false
        pendingStop = true
        runCatching { tts.stop() }
        updateNotification(false)
        stopSelf()
    }

    private fun stopService() {
        isSpeaking = false
        pendingStop = true
        runCatching { tts.stop() }
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
        isSpeaking = false
        pendingStop = true
        runCatching { tts.stop() }
        eventEmitter.emit(EVENT_TTS_STOPPED)
        updateNotification(false)
        stopSelf()
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

    private fun buildNotification(playing: Boolean): Notification {
        val prevIntent = Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_PREV_PAGE }
        val toggleIntent = Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_TOGGLE }
        val nextIntent = Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_NEXT_PAGE }
        val stopIntent = Intent(this, TtsService::class.java).apply { action = TtsController.ACTION_STOP }

        val prevPendingIntent = PendingIntent.getService(this, REQUEST_PREV, prevIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val togglePendingIntent = PendingIntent.getService(this, REQUEST_TOGGLE, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nextPendingIntent = PendingIntent.getService(this, REQUEST_NEXT, nextIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPendingIntent = PendingIntent.getService(this, REQUEST_STOP, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TxtReader")
            .setContentText(if (playing) "正在朗读" else "朗读已暂停")
            .setSmallIcon(R.drawable.ic_speaker)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_skip_prev, "上一页", prevPendingIntent)
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "暂停" else "播放", togglePendingIntent)
            .addAction(R.drawable.ic_skip_next, "下一页", nextPendingIntent)
            .addAction(R.drawable.ic_close, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(playing))
    }

    override fun onDestroy() {
        stopForeground(2) // STOP_FOREGROUND_REMOVE
        ready = false
        pendingStop = true
        runCatching { unregisterReceiver(noisyReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { tts.shutdown() }
    }
}