package com.xld.txtreader.tts

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.xld.txtreader.TxtReaderApplication
import com.xld.txtreader.appSettings
import com.xld.txtreader.eventEmitter
import com.xld.txtreader.EVENT_TTS_DONE
import com.xld.txtreader.EVENT_TTS_START
import com.xld.txtreader.EVENT_TTS_READY
import com.xld.txtreader.EVENT_TTS_STOPPED
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var ready = false
    private var pendingText: String? = null
    private var currentText: String? = null
    var isSpeaking = false

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        return START_NOT_STICKY
    }

    private fun speak(text: String?) {
        if (text.isNullOrBlank()) return
        isSpeaking = true
        Log.d("TtsService", "speaking textLen=${text.length}")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_page")
    }

    private fun onUtteranceDone() {
        isSpeaking = false
        eventEmitter.emit(EVENT_TTS_DONE)
    }

    private fun pause() {
        isSpeaking = false
        runCatching { tts.stop() }
        stopSelf()
    }

    private fun stopService() {
        isSpeaking = false
        runCatching { tts.stop() }
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
        runCatching { tts.stop() }
        eventEmitter.emit(EVENT_TTS_STOPPED)
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

    override fun onDestroy() {
        ready = false
        runCatching { unregisterReceiver(noisyReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { tts.shutdown() }
    }
}
