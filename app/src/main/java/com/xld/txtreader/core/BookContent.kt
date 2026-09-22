package com.xld.txtreader.core

import java.io.File

class BookContent(
    val filePath: String,
    val fileName: String,
    val text: String,
    val chapters: List<Chapter>,
    val encodeStr: String,
    val regexStr: String,
    val regexType: Int,
    val fileSize: Long = 0L,
) {
    val totalChapter: Int get() = chapters.size

    fun chapterTitle(index: Int): String =
        chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() } ?: "第${index + 1}章"

    fun chapterText(index: Int): String {
        val ch = chapters.getOrNull(index) ?: return ""
        return text.substring(ch.start, ch.end)
    }
}

class BookSearcher {
    data class Hit(val chapter: Int, val pageInChapter: Int, val offset: Int, val snippet: String)

    fun search(content: BookContent, pages: List<List<IntRange>>, keyword: String): List<Hit> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>(16)
        for (ci in content.chapters.indices) {
            val chText = content.chapterText(ci)
            val chapterPages = pages.getOrNull(ci) ?: continue
            if (chText.isEmpty() || chapterPages.isEmpty()) continue
            var from = 0
            var perChapter = 0
            while (true) {
                val idx = chText.indexOf(kw, from, ignoreCase = true)
                if (idx < 0) break
                val page = chapterPages.indexOfFirst { idx in it }
                if (page >= 0) {
                    hits += Hit(ci, page, content.chapters[ci].start + idx, buildSnippet(chText, idx, kw.length))
                    perChapter++
                }
                from = idx + kw.length
                if (perChapter >= 20) break
            }
            if (hits.size >= 200) break
        }
        return hits
    }

    private fun buildSnippet(ch: String, idx: Int, len: Int): String {
        val start = (idx - 14).coerceAtLeast(0)
        val end = (idx + len + 18).coerceAtMost(ch.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < ch.length) "…" else ""
        return prefix + ch.substring(start, end).replace('\n', ' ').replace('\r', ' ') + suffix
    }
}

fun fileNameSansExt(file: File): String {
    val name = file.name
    return name.substringBeforeLast('.')
}