package com.xld.txtreader.tts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class TtsController(private val context: Context) {

    fun play(
        text: String,
        startIndex: Int = 0,
        bookTitle: String = "",
        chapterNumber: Int = 0,
        totalChapters: Int = 0,
        filePath: String = "",
    ) {
        startService(
            ACTION_PLAY,
            text = text,
            sentenceIndex = startIndex,
            bookTitle = bookTitle,
            chapterNumber = chapterNumber,
            totalChapters = totalChapters,
            filePath = filePath,
        )
    }

    fun toggle() = startService(ACTION_TOGGLE)
    fun pause() = startService(ACTION_PAUSE)
    fun prevPage() = startService(ACTION_PREV_PAGE)
    fun nextPage() = startService(ACTION_NEXT_PAGE)
    fun prevChapter() = startService(ACTION_PREV_CHAPTER)
    fun nextChapter() = startService(ACTION_NEXT_CHAPTER)
    fun speed(value: Float) = startService(ACTION_SPEED, speed = value)
    fun stop() = startNormalService(ACTION_STOP)

    private fun startService(
        action: String,
        text: String? = null,
        speed: Float? = null,
        sentenceIndex: Int = 0,
        bookTitle: String = "",
        chapterNumber: Int = 0,
        totalChapters: Int = 0,
        filePath: String = "",
    ) {
        try {
            val intent = Intent(context, TtsService::class.java).setAction(action)
            text?.let { intent.putExtra(EXTRA_TEXT, it) }
            speed?.let { intent.putExtra(EXTRA_SPEED, it) }
            if (sentenceIndex > 0) intent.putExtra(EXTRA_SENTENCE_INDEX, sentenceIndex)
            if (bookTitle.isNotBlank()) intent.putExtra(EXTRA_BOOK_TITLE, bookTitle)
            if (chapterNumber > 0) intent.putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            if (totalChapters > 0) intent.putExtra(EXTRA_TOTAL_CHAPTERS, totalChapters)
            if (filePath.isNotBlank()) intent.putExtra(EXTRA_FILE_PATH, filePath)
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

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_SENTENCE_INDEX = "extra_sentence_index"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_CHAPTER_NUMBER = "extra_chapter_number"
        const val EXTRA_TOTAL_CHAPTERS = "extra_total_chapters"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
