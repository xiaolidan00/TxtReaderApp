package com.xld.txtreader.core

data class Chapter(val title: String, val start: Int, val end: Int) {
    val titleIndex get() = title
}

object ChapterParser {

    fun parse(text: String, regexSource: String): List<Chapter> {
        if (text.isEmpty()) {
            return listOf(Chapter("正文", 0, 0))
        }
        val regex = runCatching { Regex(regexSource) }.getOrNull()
        val boundaries = ArrayList<Pair<Int, String>>()
        if (regex != null && regexSource.isNotBlank()) {
            var pos = 0
            while (pos <= text.length) {
                val nl = text.indexOf('\n', pos)
                val lineEnd = if (nl == -1) text.length else nl
                var line = text.substring(pos, lineEnd)
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
            return listOf(Chapter(mainTitle(text), 0, text.length))
        }

        val chapters = ArrayList<Chapter>(boundaries.size + 1)
        var cursor = 0
        var title = "正文"
        for ((start, name) in boundaries) {
            if (chapters.isEmpty()) {
                if (start > 0) {
                    chapters += Chapter("正文", 0, start)
                }
                cursor = start
                title = name
            } else {
                chapters += Chapter(title, cursor, start)
                cursor = start
                title = name
            }
        }
        chapters += Chapter(title, cursor, text.length)
        return chapters
    }

    private fun mainTitle(text: String): String {
        val head = text.trim().substring(0, minOf(30, text.trim().length)).replace('\n', ' ')
        return head.ifBlank { "正文" }
    }
}