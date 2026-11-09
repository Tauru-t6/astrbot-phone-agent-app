package com.tauru.astrbotphoneagent.logic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ShellCommandSessionTest {
    /** Simulates a remote process without Android or a locally installed shell. */
    private class FakeProcess(private val respond: (String, PipedOutputStream) -> Unit) : Process() {
        private val pipe = PipedInputStream()
        private val remote = PipedOutputStream(pipe)
        val started = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val submitted = mutableListOf<String>()
        private val stdin = object : ByteArrayOutputStream() {
            override fun flush() {
                val command = toString(Charsets.UTF_8.name())
                reset()
                submitted.add(command)
                started.countDown()
                respond(command, remote)
            }
        }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream() = pipe
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() {
            destroyed.set(true)
            runCatching { remote.close() }
            runCatching { pipe.close() }
        }
    }

    private fun marker(command: String) = Regex("__DSH_[a-f0-9-]+").find(command)!!.value

    @Test fun acceptsOutputWithoutTrailingNewlineAndPreservesExitCode() {
        val process = FakeProcess { command, output ->
            assertTrue(command.contains("printf '\\n"))
            output.write("hello\n${marker(command)}:7\n".toByteArray())
            output.flush()
        }
        ShellCommandSession(process).use { session ->
            assertEquals(ShellCommandSession.Result.Completed("hello", 7), session.run("printf hello", 1000))
        }
        assertTrue(process.destroyed.get())
    }

    @Test fun brokenProcessDoesNotReplaySubmittedCommand() {
        val process = FakeProcess { _, output -> output.close() }
        ShellCommandSession(process).use { session ->
            assertEquals("shell_closed", (session.run("input tap 1 2", 1000) as ShellCommandSession.Result.Failed).code)
            assertEquals("shell_closed", (session.run("input tap 1 2", 1000) as ShellCommandSession.Result.Failed).code)
        }
        assertEquals(1, process.submitted.size)
        assertTrue(process.destroyed.get())
    }

    @Test fun timeoutDestroysRemoteProcess() {
        val process = FakeProcess { _, _ -> }
        ShellCommandSession(process).use { session ->
            assertEquals("timeout", (session.run("sleep 300", 20) as ShellCommandSession.Result.Failed).code)
        }
        assertTrue(process.destroyed.get())
    }

    @Test fun cancellationPropagatesAndDestroysRemoteProcess() {
        val process = FakeProcess { _, _ -> }
        val interrupted = AtomicBoolean(false)
        ShellCommandSession(process).use { session ->
            val worker = Thread {
                try { session.run("sleep 300", 60_000) }
                catch (_: InterruptedException) { interrupted.set(true) }
            }.apply { start() }
            assertTrue(process.started.await(1, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertTrue(interrupted.get())
        }
        assertTrue(process.destroyed.get())
    }

    @Test fun cancellingQueuedCommandDoesNotKillRunningCommand() {
        val process = FakeProcess { _, _ -> }
        ShellCommandSession(process).use { session ->
            val running = Thread {
                try { session.run("first", 60_000) } catch (_: InterruptedException) { }
            }.apply { start() }
            assertTrue(process.started.await(1, TimeUnit.SECONDS))
            val interrupted = AtomicBoolean(false)
            val waiting = Thread {
                try { session.run("second", 60_000) }
                catch (_: InterruptedException) { interrupted.set(true) }
            }.apply { start() }
            try {
                waiting.interrupt()
                waiting.join(1000)
                assertFalse(waiting.isAlive)
                assertTrue(interrupted.get())
                assertFalse(process.destroyed.get())
                assertEquals(1, process.submitted.size)
            } finally {
                running.interrupt()
                running.join(1000)
            }
        }
    }

    @Test fun outputLimitFailsInsteadOfGrowingWithoutBound() {
        val process = FakeProcess { _, output ->
            output.write("123456789\n123456789\n".toByteArray())
            output.flush()
        }
        ShellCommandSession(process, outputLimit = 12).use { session ->
            assertEquals("output_too_large", (session.run("dumpsys activity", 1000) as ShellCommandSession.Result.Failed).code)
        }
        assertTrue(process.destroyed.get())
    }
}
