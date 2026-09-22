package com.xld.txtreader.core

data class Sentence(val text: String, val start: Int, val end: Int)

object SentenceSegmenter {
    private val sentenceEnders = listOf('。', '！', '？', '；', '：')

    fun split(text: String): List<Sentence> {
        if (text.isEmpty()) return emptyList()
        val sentences = ArrayList<Sentence>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in sentenceEnders || c == '\n' || c == '\r') {
                if (i > start) {
                    val seg = text.substring(start, i).trim()
                    if (seg.isNotEmpty()) {
                        sentences += Sentence(seg, start, i)
                    }
                }
                if (c == '\n' || c == '\r') {
                    sentences += Sentence(text.substring(start, i + 1).trim(), start, i + 1)
                }
                start = i + 1
                while (start < text.length && (text[start] == '\n' || text[start] == '\r')) {
                    start++
                }
                i = start
            } else {
                i++
            }
        }
        if (start < text.length) {
            val remaining = text.substring(start).trim()
            if (remaining.isNotEmpty()) {
                sentences += Sentence(remaining, start, text.length)
            }
        }
        if (sentences.isEmpty()) {
            sentences += Sentence(text, 0, text.length)
        }
        return sentences
    }
}
