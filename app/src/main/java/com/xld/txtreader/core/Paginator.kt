package com.xld.txtreader.core

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

class Paginator(
    private val widthPx: Int,
    private val heightPx: Int,
    private val textSizePx: Float,
    private val lineHeightPx: Float,
) {
    private val linesPerPage: Int = (heightPx / lineHeightPx).toInt().coerceAtLeast(1)

    fun paginate(text: String): List<IntRange> {
        if (text.isEmpty()) return listOf(0..0)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            typeface = Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val pageCount = layout.lineCount / linesPerPage + 1
        val pages = ArrayList<IntRange>(pageCount)
        var line = 0
        val lineCount = layout.lineCount
        while (line < lineCount) {
            val last = (line + linesPerPage - 1).coerceAtMost(lineCount - 1)
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(last)
            // start == end 仅出现在文末空行（start 可能等于 text.length），此时跳过，
            // 避免生成越界区间（原实现 start until start+1 会超出文本长度导致 substring 崩溃）
            if (start < end) pages += start until end
            line = last + 1
        }
        return pages.ifEmpty { listOf(0 until text.length) }
    }
}