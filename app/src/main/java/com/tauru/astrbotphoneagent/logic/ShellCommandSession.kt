package com.tauru.astrbotphoneagent.logic

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** One shell process. A submitted command is never replayed after an uncertain result. */
internal class ShellCommandSession(
    private val process: Process,
    private val outputLimit: Int = 8_000_000,
) : AutoCloseable {
    sealed interface Result {
        data class Completed(val output: String, val exitCode: Int) : Result
        data class Failed(val code: String, val message: String) : Result
    }

    private sealed interface ReadEvent {
        data class Chunk(val value: String) : ReadEvent
        data object End : ReadEvent
    }

    private val writer = process.outputStream.bufferedWriter()
    private val lines = ArrayBlockingQueue<ReadEvent>(64)
    private val lock = ReentrantLock()
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    @Volatile private var readFailure: String? = null
    private val reader = Thread({
        try {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) lines.put(ReadEvent.Chunk(String(buffer, 0, count)))
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: IOException) {
            readFailure = error.message
        } finally {
            // Do not mark closed until queued output has been consumed.
            if (!closed) runCatching { lines.put(ReadEvent.End) }
        }
    }, "phone-agent-shell-reader").apply { isDaemon = true; start() }

    fun run(command: String, timeoutMs: Long): Result {
        // Waiting for another probe must remain cancellable too.
        lock.lockInterruptibly()
        try {
            if (closed) return Result.Failed("shell_closed", "shell 会话已结束")
            val marker = "__DSH_${UUID.randomUUID()}"
            // The leading newline also frames commands whose output has no final newline.
            writer.write(command + " 2>&1; printf '\\n" + marker + ":%s\\n' \"\$?\"\n")
            writer.flush()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val output = StringBuilder()
            val line = StringBuilder()
            while (true) {
                val remaining = deadline - System.nanoTime()
                val event = if (remaining > 0) lines.poll(remaining, TimeUnit.NANOSECONDS) else null
                when (event) {
                    null -> return fail("timeout", "手机命令执行超时")
                    ReadEvent.End -> return fail("shell_closed", readFailure ?: "shell 会话已结束，执行结果未知")
                    is ReadEvent.Chunk -> {
                        for (char in event.value) {
                            if (char != '\n') {
                                // Permit the framing marker even with a very small output budget.
                                if (output.length + line.length > outputLimit + marker.length + 16) {
                                    return fail("output_too_large", "手机命令输出过大")
                                }
                                line.append(char)
                                continue
                            }
                            val value = line.toString().removeSuffix("\r")
                            line.setLength(0)
                            if (value.startsWith("$marker:")) {
                                val code = value.substringAfter(':').toIntOrNull()
                                    ?: return fail("shell_error", "无法读取命令结束状态")
                                return Result.Completed(output.toString().trim(), code)
                            }
                            if (output.length + value.length + 1 > outputLimit) {
                                return fail("output_too_large", "手机命令输出过大")
                            }
                            output.appendLine(value)
                        }
                    }
                }
            }
        } catch (cancelled: InterruptedException) {
            close()
            throw cancelled
        } catch (_: IOException) {
            return fail("shell_error", "shell 连接中断，执行结果未知")
        } finally {
            lock.unlock()
        }
    }

    private fun fail(code: String, message: String): Result.Failed {
        close()
        return Result.Failed(code, message)
    }

    override fun close() {
        closed = true
        runCatching { process.destroy() }
        reader.interrupt()
        // Wake a command waiting in poll when another owner closes the session.
        lines.clear()
        lines.offer(ReadEvent.End)
    }
}
