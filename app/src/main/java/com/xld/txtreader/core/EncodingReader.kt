package com.xld.txtreader.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object EncodingReader {

    fun readText(file: File, charsetName: String): String =
        decode(file.readBytes(), charsetName)

    fun readText(context: Context, uri: Uri, charsetName: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
        return decode(bytes, charsetName)
    }

    fun decode(bytes: ByteArray, charsetName: String): String {
        val size = bytes.size
        if (size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, size - 3, StandardCharsets.UTF_8)
        }
        if (size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE) {
            return String(bytes, 2, size - 2, StandardCharsets.UTF_16LE)
        }
        if (size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
            return String(bytes, 2, size - 2, StandardCharsets.UTF_16BE)
        }
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(StandardCharsets.UTF_8)
        return String(bytes, charset)
    }

    fun detectCharset(file: File): String = detectCharset(file.readBytes())

    fun detectCharset(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
        return detectCharset(bytes)
    }

    fun detectCharset(bytes: ByteArray): String {
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if ('\uFFFD' !in text) return "UTF-8"
        } catch (_: CharacterCodingException) {
            // fall through
        }
        return "GBK"
    }
}