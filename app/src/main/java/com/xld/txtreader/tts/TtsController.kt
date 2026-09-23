package com.xld.txtreader.tts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class TtsController(private val context: Context) {

    fun play(text: String, startIndex: Int = 0) {
        startService(ACTION_PLAY, text = text, sentenceIndex = startIndex)
    }

    fun toggle() = startService(ACTION_TOGGLE)
    fun pause() = startService(ACTION_PAUSE)
    fun prevPage() = startService(ACTION_PREV_PAGE)
    fun nextPage() = startService(ACTION_NEXT_PAGE)
    fun prevChapter() = startService(ACTION_PREV_CHAPTER)
    fun nextChapter() = startService(ACTION_NEXT_CHAPTER)
    fun speed(value: Float) = startService(ACTION_SPEED, speed = value)
    fun stop() = startNormalService(ACTION_STOP)

    private fun startService(action: String, text: String? = null, speed: Float? = null, sentenceIndex: Int = 0) {
        try {
            val intent = Intent(context, TtsService::class.java).setAction(action)
            text?.let { intent.putExtra("extra_text", it) }
            speed?.let { intent.putExtra("extra_speed", it) }
            if (sentenceIndex > 0) intent.putExtra("extra_sentence_index", sentenceIndex)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("TtsController", "startService failed", e)
        }
    }

    private fun startNormalService(action: String) {
        val intent = Intent(context, TtsService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("TtsController", "startService failed", e)
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
    }
}
