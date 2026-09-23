package com.xld.txtreader.core

data class Chapter(val title: String, val start: Int, val end: Int, val content: String = "") {
    val titleIndex get() = title
}

object ChapterParser {

    fun parse(text: String, regexSource: String): List<Chapter> {
        if (text.isEmpty()) {
            return listOf(Chapter("正文", 0, 0, ""))
        }
        val lines = text.lines()
        val cleanedLines = lines.filter { it.trim().isNotBlank() }
        val cleanedText = cleanedLines.joinToString("\n")
        val regex = runCatching { Regex(regexSource) }.getOrNull()
        val boundaries = ArrayList<Pair<Int, String>>()
        if (regex != null && regexSource.isNotBlank()) {
            var pos = 0
            while (pos <= cleanedText.length) {
                val nl = cleanedText.indexOf('\n', pos)
                val lineEnd = if (nl == -1) cleanedText.length else nl
                var line = cleanedText.substring(pos, lineEnd)
                if (line.endsWith('\r')) line = line.dropLast(1)
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && trimmed.length <= 80) {
                    val match = regex.find(line)
                    if (match != null && match.range.first == 0) {
                        boundaries += pos to trimmed
                    }
                }
                if (nl == -1) break
                pos = nl + 1
            }
        }
        if (boundaries.isEmpty()) {
            return listOf(Chapter(mainTitle(cleanedText), 0, cleanedText.length, cleanedText))
        }

        val chapters = ArrayList<Chapter>(boundaries.size + 1)
        var cursor = 0
        var title = "正文"
        for ((start, name) in boundaries) {
            if (chapters.isEmpty()) {
                if (start > 0) {
                    chapters += Chapter("正文", 0, start, cleanedText.substring(0, start))
                }
                cursor = start
                title = name
            } else {
                chapters += Chapter(title, cursor, start, cleanedText.substring(cursor, start))
                cursor = start
                title = name
            }
        }
        chapters += Chapter(title, cursor, cleanedText.length, cleanedText.substring(cursor))
        return chapters
    }

    private fun mainTitle(text: String): String {
        val head = text.trim().substring(0, minOf(30, text.trim().length)).replace('\n', ' ')
        return head.ifBlank { "正文" }
    }
}
