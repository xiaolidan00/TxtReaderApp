package com.xld.txtreader.tts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class TtsController(private val context: Context) {

    fun play(path: String, chapter: Int, pageInChapter: Int) {
        val intent = base(ACTION_PLAY)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_CHAPTER, chapter)
            .putExtra(EXTRA_PAGE, pageInChapter)
        start(intent)
    }

    fun toggle() = start(base(ACTION_TOGGLE))
    fun pause() = start(base(ACTION_PAUSE))
    fun prevPage() = start(base(ACTION_PREV_PAGE))
    fun nextPage() = start(base(ACTION_NEXT_PAGE))
    fun prevChapter() = start(base(ACTION_PREV_CHAPTER))
    fun nextChapter() = start(base(ACTION_NEXT_CHAPTER))
    fun speed(value: Float) = start(base(ACTION_SPEED).putExtra(EXTRA_SPEED, value))
    fun stop() = start(base(ACTION_STOP))

    private fun base(action: String): Intent =
        Intent(context, TtsService::class.java).setAction(action)

    private fun start(intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("TtsController", "startForegroundService failed", e)
        }
    }

    companion object {
        const val ACTION_PLAY = "com.xld.txtreader.tts.PLAY"
        const val ACTION_TOGGLE = "com.xld.txtreader.tts.TOGGLE"
        const val ACTION_PAUSE = "com.xld.txtreader.tts.PAUSE"
        const val ACTION_PREV_PAGE = "com.xld.txtreader.tts.PREV_PAGE"
        const val ACTION_NEXT_PAGE = "com.xld.txtreader.tts.NEXT_PAGE"
        const val ACTION_PREV_CHAPTER = "com.xld.txtreader.tts.PREV_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.xld.txtreader.tts.NEXT_CHAPTER"
        const val ACTION_SPEED = "com.xld.txtreader.tts.SPEED"
        const val ACTION_STOP = "com.xld.txtreader.tts.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PAGE = "page"
        const val EXTRA_SPEED = "speed"
    }
}