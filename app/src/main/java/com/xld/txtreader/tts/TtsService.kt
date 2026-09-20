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
import com.xld.txtreader.core.BookContent
import com.xld.txtreader.core.ChapterParser
import com.xld.txtreader.core.EncodingReader
import com.xld.txtreader.core.Paginator
import com.xld.txtreader.core.RegexPresets
import com.xld.txtreader.core.BookContentHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var content: BookContent? = null
    private var path: String = ""
    private var chapter = 0
    private var page = 0
    private var playing = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            publish()
            if (ok && playing) {
                Log.d("TtsService", "init complete, speaking chapter=$chapter page=$page")
                speakText(chapter, page)
            }
        }
        tts = TextToSpeech(this, initListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TtsService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            TtsController.ACTION_PLAY -> {
                path = intent.getStringExtra(TtsController.EXTRA_PATH) ?: appSettings.lastTtsPath
                chapter = intent.getIntExtra(TtsController.EXTRA_CHAPTER, 0)
                page = intent.getIntExtra(TtsController.EXTRA_PAGE, 0)
                Log.d("TtsService", "PLAY path=$path chapter=$chapter page=$page ready=$ready")
                appSettings.lastTtsPath = path
                content = null
                playing = true
                goForeground()
                speakIfReady()
            }
            TtsController.ACTION_TOGGLE -> {
                if (playing) pause() else {
                    playing = true
                    goForeground()
                    speakIfReady()
                }
            }
            TtsController.ACTION_PAUSE -> pause()
            TtsController.ACTION_PREV_PAGE -> navigatePage(-1)
            TtsController.ACTION_NEXT_PAGE -> navigatePage(1)
            TtsController.ACTION_PREV_CHAPTER -> navigateChapter(-1)
            TtsController.ACTION_NEXT_CHAPTER -> navigateChapter(1)
            TtsController.ACTION_SPEED -> {
                val speed = intent.getFloatExtra(TtsController.EXTRA_SPEED, appSettings.ttsSpeed)
                appSettings.ttsSpeed = speed
                if (ready) runCatching { tts.setSpeechRate(speed) }
                TtsBus.update { it.copy(speed = speed) }
            }
            TtsController.ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun speakIfReady() {
        if (playing) {
            if (!ready) {
                Log.d("TtsService", "speakIfReady: not ready yet, waiting for init")
                return
            }
            speakText(chapter, page)
        }
    }

    private fun pause() {
        playing = false
        runCatching { tts.stop() }
        publish()
        refreshNotification()
    }

    private fun stop() {
        playing = false
        runCatching { tts.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        TtsBus.update { it.copy(isPlaying = false, available = ready) }
        stopSelf()
    }

    private fun navigatePage(delta: Int) {
        ensureContentSync()
        val c = content ?: return stop()
        var ch = chapter
        var pg = page + delta
        val count = paginateChapter(ch).size
        when {
            pg < 0 -> if (ch > 0) { ch--; pg = paginateChapter(ch).size - 1 } else pg = 0
            pg >= count -> if (ch + 1 < c.totalChapter) { ch++; pg = 0 } else pg = count - 1
        }
        if (pg >= 0) {
            requireText(ch, pg)
            speakText(ch, pg)
        }
    }

    private fun navigateChapter(delta: Int) {
        val c = ensureContentSync() ?: return stop()
        val target = (chapter + delta).coerceIn(0, c.totalChapter - 1)
        val pg = if (delta < 0) (paginateChapter(target).size - 1).coerceAtLeast(0) else 0
        speakText(target, pg)
    }

    private fun requireText(ch: Int, pg: Int) {
        val c = content ?: return
        if (ch in c.chapters.indices) chapter = ch
        page = pg.coerceAtLeast(0)
    }

    private fun speakText(ch: Int, pg: Int) {
        val c = ensureContentSync() ?: return stop()
        if (ch !in c.chapters.indices) return stop()
        val pages = paginateChapter(ch)
        if (pages.isEmpty()) return stop()
        val idx = pg.coerceIn(0, pages.lastIndex)
        val range = pages[idx]
        val chStart = c.chapters[ch].start
        val text = c.text.substring(chStart + range.first, (chStart + range.last + 1).coerceAtMost(c.text.length))
        if (text.isBlank()) {
            onUtteranceDone()
            return
        }
        chapter = ch
        page = idx
        playing = true
        if (ready) {
            Log.d("TtsService", "speaking ch=$ch pg=$idx textLen=${text.length}")
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId(ch, idx))
            if (result != TextToSpeech.SUCCESS) {
                Log.e("TtsService", "tts.speak failed: $result")
            }
        }
        publish()
        persist()
        refreshNotification()
    }

    private fun onUtteranceDone() {
        if (!playing) return
        val c = content ?: return stop()
        val pages = paginateChapter(chapter)
        if (page + 1 < pages.size) {
            speakText(chapter, page + 1)
        } else if (chapter + 1 < c.totalChapter) {
            speakText(chapter + 1, 0)
        } else {
            stop()
        }
    }

    private fun ensureContentSync(): BookContent? {
        content?.let { return it }
        val loaded = runBlocking {
            try {
                loadContent()
            } catch (e: Exception) {
                Log.e("TtsService", "loadContent exception", e)
                null
            }
        }
        if (loaded == null) {
            Log.e("TtsService", "loadContent returned null, path=$path")
            return null
        }
        content = loaded
        return loaded
    }

    private suspend fun loadContent(): BookContent? {
        if (path.isBlank()) {
            Log.e("TtsService", "loadContent: path is blank")
            return null
        }
        BookContentHolder?.takeIf { it.filePath == path }?.let {
            Log.d("TtsService", "loadContent: got from BookContentHolder")
            return it
        }
        Log.d("TtsService", "loadContent: loading from file path=$path")
        val record = app.repository.get(path)
        val encode = record?.encodeStr ?: "UTF-8"
        val regex = record?.regexStr ?: RegexPresets.default().value
        val file = File(path)
        if (!file.exists()) {
            Log.e("TtsService", "loadContent: file does not exist: $path")
            return null
        }
        val text = EncodingReader.readText(file, encode)
        Log.d("TtsService", "loadContent: text length=${text.length}")
        val fileName = record?.fileName ?: file.name
        val chapters = ChapterParser.parse(text, regex)
        Log.d("TtsService", "loadContent: chapters=${chapters.size}")
        return BookContent(path, fileName, text, chapters, encode, regex, record?.regexType ?: 0, record?.fileSize ?: 0L)
    }

    private fun paginateChapter(index: Int): List<IntRange> {
        val c = content ?: return emptyList()
        val chapterText = c.chapterText(index)
        if (chapterText.isEmpty()) return emptyList()
        val s = appSettings
        val w = s.contentWidthPx
        val h = s.contentHeightPx
        val density = s.density
        if (w <= 0 || h <= 0) {
            val chunk = 200
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (i < chapterText.length) {
                val end = (i + chunk).coerceAtMost(chapterText.length)
                ranges += i until end
                i = end
            }
            return ranges
        }
        val fontPx = s.fontSizeSp * density
        val linePx = s.fontSizeSp * s.lineSpacingMult * density
        return Paginator(w, h, fontPx, linePx).paginate(chapterText)
    }

    private fun publish() {
        val c = content
        TtsBus.update {
            it.copy(
                isPlaying = playing,
                available = ready,
                chapter = chapter,
                pageInChapter = page,
                totalChapter = c?.totalChapter ?: it.totalChapter,
                bookTitle = c?.fileName ?: it.bookTitle,
                chapterTitle = c?.chapterTitle(chapter) ?: it.chapterTitle,
                speed = appSettings.ttsSpeed,
            )
        }
    }

    private fun persist() {
        val c = content ?: return
        ioScope.launch {
            app.repository.updateProgress(path, chapter, page, c.totalChapter, c.text.length.toLong())
        }
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
        val c = content
        val title = c?.fileName ?: "TXT阅读器"
        val subtitle = buildString {
            c?.chapterTitle(chapter)?.let { append(it) }
            if (c != null && c.totalChapter > 0) {
                if (isNotEmpty()) append(" · ")
                append("$chapter/").append(c.totalChapter).append("章")
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
        val toggleAction = if (playing) {
            NotificationCompat.Action(R.drawable.ic_pause, "暂停", servicePi(103, TtsController.ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "播放", servicePi(103, TtsController.ACTION_TOGGLE))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(title.take(40))
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

    private fun utteranceId(ch: Int, pg: Int) = "tts_${ch}_$pg"

    override fun onDestroy() {
        ioScope.cancel()
        ready = false
        runCatching { tts.shutdown() }
        TtsBus.update { it.copy(isPlaying = false, available = false) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tts_playback"
    }
}
