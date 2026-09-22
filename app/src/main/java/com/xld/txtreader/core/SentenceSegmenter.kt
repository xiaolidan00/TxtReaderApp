package com.xld.txtreader.core

data class Sentence(val text: String, val start: Int, val end: Int)

object SentenceSegmenter {
    private val sentenceEnders = listOf('。', '！', '？', '；', '：', '!', '?', ',', '，')
    private const val MAX_CHUNK_SIZE = 200

    fun split(text: String): List<Sentence> {
        if (text.isEmpty()) return emptyList()
        val rawSentences = splitRaw(text)
        return rawSentences
        // return buildChunks(rawSentences)
    }

    private fun splitRaw(text: String): List<Sentence> {
        val sentences = ArrayList<Sentence>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in sentenceEnders || c == '\n') {
                if (i > start) {
                    val seg = text.substring(start, i).trim()
                    if (seg.isNotEmpty()) {
                        sentences += Sentence(seg, start, i)
                    }
                }
                start = i + 1
                while (start < text.length && (text[start] == '\n')) {
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
