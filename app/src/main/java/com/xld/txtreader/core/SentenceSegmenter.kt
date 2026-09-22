package com.xld.txtreader.core

data class Sentence(val text: String, val start: Int, val end: Int)

object SentenceSegmenter {
    private val sentenceEnders = listOf('。', '！', '？', '；', '：', '!', '?', ',', '，','#','"','“','”','(',')','（','）','[',']','【','】')

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
                    val rawSeg = text.substring(start, i)
                    val seg = rawSeg.trim()
                    if (seg.isNotEmpty()) {
                        val leading = rawSeg.takeWhile { it.isWhitespace() }.length
                        sentences += Sentence(seg, start + leading, start + leading + seg.length)
                    }
                }
                start = i + 1
                while (start < text.length && text[start].isWhitespace()) {
                    start++
                }
                i = start
            } else {
                i++
            }
        }
        if (start < text.length) {
            val rawSeg = text.substring(start)
            val seg = rawSeg.trim()
            if (seg.isNotEmpty()) {
                val leading = rawSeg.takeWhile { it.isWhitespace() }.length
                sentences += Sentence(seg, start + leading, start + leading + seg.length)
            }
        }
        if (sentences.isEmpty()) {
            sentences += Sentence(text, 0, text.length)
        }
        return sentences
    }
}
