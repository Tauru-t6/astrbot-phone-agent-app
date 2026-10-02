package com.tauru.astrbotphoneagent.logic

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** HTTP framing operates in bytes: Content-Length is never a character count. */
internal object HttpCommandRequest {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    class Invalid(val status: Int, message: String) : Exception(message)

    fun read(input: InputStream): Request {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) throw EOFException("incomplete headers")
                if (++headerBytes > 16_384) throw Invalid(431, "headers too large")
                if (byte == 10) break
                bytes.write(byte)
            }
            return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
        }
        val parts = line().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw Invalid(400, "invalid request line")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            val colon = value.indexOf(':')
            if (colon <= 0) throw Invalid(400, "invalid header")
            val name = value.substring(0, colon).lowercase(java.util.Locale.ROOT)
            if (headers.containsKey(name)) throw Invalid(400, "duplicate header")
            headers[name] = value.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw Invalid(400, "chunked encoding not supported")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw Invalid(400, "invalid content length") } ?: 0
        if (length < 0) throw Invalid(400, "negative content length")
        if (length > 1_048_576) throw Invalid(413, "request too large")
        val body = ByteArray(length)
        var received = 0
        while (received < length) {
            val count = input.read(body, received, length - received)
            if (count < 0) throw EOFException("incomplete request body")
            received += count
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }
}
