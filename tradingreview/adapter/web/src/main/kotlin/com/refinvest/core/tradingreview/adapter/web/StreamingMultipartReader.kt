package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.TradingFailure
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/** Reads a two-part multipart request without handing plaintext bytes to servlet temporary storage. */
internal class StreamingMultipartReader(source: InputStream, contentType: String?) {
    private val boundary: String
    private val input = BufferedInputStream(source, 64 * 1024)
    private var finished = false
    private var active = false

    init {
        val type = contentType ?: throw TradingFailure("REQUEST_INVALID", 400)
        if (!type.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true))
            throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", 415)
        val parameter = type.split(';').drop(1).map { it.trim() }.firstOrNull { it.startsWith("boundary=", true) }
            ?: throw TradingFailure("REQUEST_INVALID", 400)
        boundary = parameter.substringAfter('=').trim().removeSurrounding("\"")
        if (boundary.length !in 1..70 || boundary.any { it.code !in 33..126 || it == '/' || it == '\\' })
            throw TradingFailure("REQUEST_INVALID", 400)
        if (line() != "--$boundary") throw TradingFailure("REQUEST_INVALID", 400)
    }

    data class Part(val name: String, val contentType: String?, val body: InputStream)

    fun nextPart(): Part? {
        if (active) throw IllegalStateException("Multipart part is not consumed")
        if (finished) return null
        var headersSize = 0
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            headersSize += header.length + 2
            if (headersSize > 8_192) throw TradingFailure("REQUEST_INVALID", 400)
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) throw TradingFailure("REQUEST_INVALID", 400)
            val name = header.substring(0, colon).lowercase()
            if (name in headers) throw TradingFailure("REQUEST_INVALID", 400)
            headers[name] = header.substring(colon + 1).trim()
        }
        val disposition = headers["content-disposition"] ?: throw TradingFailure("REQUEST_INVALID", 400)
        if (!disposition.startsWith("form-data;", true)) throw TradingFailure("REQUEST_INVALID", 400)
        val name = Regex("(?:^|;)\\s*name=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
            ?: throw TradingFailure("REQUEST_INVALID", 400)
        if (name != "metadata" && name != "file") throw TradingFailure("REQUEST_INVALID", 400)
        active = true
        return Part(name, headers["content-type"], BoundaryStream())
    }

    private fun line(): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0) throw TradingFailure("REQUEST_INVALID", 400)
            if (b == 13) {
                if (input.read() != 10) throw TradingFailure("REQUEST_INVALID", 400)
                return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            if (b == 10 || bytes.size >= 8_192) throw TradingFailure("REQUEST_INVALID", 400)
            bytes.add(b.toByte())
        }
    }

    private inner class BoundaryStream : InputStream() {
        private val marker = ("\r\n--" + boundary).toByteArray(StandardCharsets.US_ASCII)
        private val pending = ArrayDeque<Int>()
        private val ready = ArrayDeque<Int>()
        private var done = false

        override fun read(): Int {
            while (ready.isEmpty() && !done) {
                val value = input.read()
                if (value < 0) throw IOException("Incomplete multipart body")
                pending.addLast(value)
                flushNonPrefix()
                if (pending.size == marker.size && isPrefix()) {
                    val first = input.read()
                    val second = input.read()
                    if (first < 0 || second < 0) throw IOException("Incomplete multipart boundary")
                    if (first == 45 && second == 45) {
                        finished = true
                        done = true
                        pending.clear()
                        active = false
                    } else if (first == 13 && second == 10) {
                        done = true
                        pending.clear()
                        active = false
                    } else {
                        pending.addLast(first)
                        pending.addLast(second)
                        flushNonPrefix()
                    }
                }
            }
            return if (ready.isEmpty()) -1 else ready.removeFirst()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset + length > buffer.size) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            var count = 0
            while (count < length) {
                val value = read()
                if (value < 0) break
                buffer[offset + count] = value.toByte()
                count++
            }
            return if (count == 0) -1 else count
        }

        override fun close() { /* request lifecycle owns the transport stream */ }

        private fun isPrefix(): Boolean {
            if (pending.size > marker.size) return false
            var index = 0
            for (value in pending) {
                if (value != marker[index++].toInt().and(0xff)) return false
            }
            return true
        }

        private fun flushNonPrefix() {
            while (pending.isNotEmpty() && !isPrefix()) ready.addLast(pending.removeFirst())
        }
    }
}
