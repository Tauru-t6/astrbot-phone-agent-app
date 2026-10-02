package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WireProtocolTest {
    @Test fun chinesePayloadUsesBytesAndHandlesShortReads() {
        val json = """{"text":"两分钟后喝水","minutes":2}"""
        val payload = json.toByteArray(Charsets.UTF_8)
        val header = "POST /command HTTP/1.1\r\nContent-Length: ${payload.size}\r\nAuthorization: Bearer test\r\n\r\n".toByteArray()
        val fragmented = object : ByteArrayInputStream(header + payload) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(2, len))
        }
        val request = HttpCommandRequest.read(fragmented)
        assertEquals(json, String(request.body, Charsets.UTF_8))
        assertEquals("Bearer test", request.headers["authorization"])
    }
    @Test fun truncatedBodyFailsInsteadOfExecutingPartialJson() {
        val stream = ByteArrayInputStream("POST /command HTTP/1.1\r\nContent-Length: 12\r\n\r\n{}".toByteArray())
        assertThrows(java.io.EOFException::class.java) { HttpCommandRequest.read(stream) }
    }
    @Test fun oversizedAndDuplicateLengthsAreRejected() {
        for (headers in listOf("Content-Length: 2000000", "Content-Length: 2\r\nContent-Length: 3", "Content-Length: -1")) {
            assertThrows(HttpCommandRequest.Invalid::class.java) {
                HttpCommandRequest.read(ByteArrayInputStream("POST /command HTTP/1.1\r\n$headers\r\n\r\n{}".toByteArray()))
            }
        }
    }
    @Test fun pythonNumericAndBooleanArgsRemainReadable() {
        val command = protocolJson.decodeFromString(CommandEnvelope.serializer(), """{"command_id":"c-test","action":"location","args":{"minutes":2,"high_accuracy":false,"text":"你好"},"created_at":1000.0,"deadline":60}""")
        assertEquals(mapOf("minutes" to "2", "high_accuracy" to "false", "text" to "你好"), command.args)
    }
    @Test fun nestedArgumentIsRejected() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            protocolJson.decodeFromString(CommandEnvelope.serializer(), """{"command_id":"c-test","action":"location","args":{"bad":{}},"created_at":1000.0,"deadline":60}""")
        }
    }
    @Test fun expiredAndFutureCommandsDoNotExecute() {
        val command = CommandEnvelope(commandId="c-test", action="ping", createdAtEpochSec=1000.0, deadline=60)
        assertNull(commandValidationError(command, 1050.0))
        assertNotNull(commandValidationError(command, 1061.0))
        assertNotNull(commandValidationError(command.copy(createdAtEpochSec=2000.0), 1000.0))
        assertNotNull(commandValidationError(command.copy(createdAtEpochSec=Double.NaN), 1000.0))
        assertNotNull(commandValidationError(command.copy(deadline=-1), 1000.0))
    }
}
