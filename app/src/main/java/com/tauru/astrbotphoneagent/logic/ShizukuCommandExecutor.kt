package com.tauru.astrbotphoneagent.logic

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import rikka.shizuku.Shizuku

// Executes the closed command set through Shizuku's shell. Each execute call is
// synchronous and must run off the main thread.
//
// Verification rule (inherited from the plugin's policy): pm suspend and
// unsuspend results are only successful when the phone confirms the new state.
// Everything else reports the shell exit code.
//
// Shell safety: every value interpolated into a command is either a constant or
// passes a strict allowlist (PACKAGE_REGEX, bounded coordinates, allowed key
// codes, or a single-quote-escaped literal built by InputCommands).
class ShizukuCommandExecutor(private val context: Context) {

    sealed interface CommandOutcome {
        data class Ok(val output: String) : CommandOutcome
        data class Fail(val errorCode: String, val message: String) : CommandOutcome
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: IllegalStateException) {
        false
    }

    /** Why Shizuku is (not) usable, so the UI can say which half is missing. */
    fun shizukuDiagnostic(): String = try {
        when {
            !Shizuku.pingBinder() -> "服务未运行"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "未授权"
            else -> "可用"
        }
    } catch (_: IllegalStateException) {
        "服务未运行"
    } catch (_: Throwable) {
        "状态未知"
    }

    fun execute(action: String, args: Map<String, String>): CommandOutcome {
        if (action !in COMMAND_ACTIONS) {
            return CommandOutcome.Fail("unsupported_action", "unknown action: " + action)
        }
        if (action == "ping") return CommandOutcome.Ok("""{"pong":true}""")
        if (action == "status") return status()
        // Task verification needs only the foreground and the screen state; the
        // full status also aggregates usage stats, which takes ~10 s.
        if (action == "quick_status") return quickStatus()
        if (!shizukuReady()) {
            return CommandOutcome.Fail("shizuku_unavailable", "Shizuku is not running or permission not granted")
        }
        return try {
            when (action) {
                "open_app" -> shell("monkey -p " + requirePackage(args) + " 1")
                "close_app" -> shell("am force-stop " + requirePackage(args))
                "foreground_app" -> CommandOutcome.Ok("""{"package":"""" + foregroundPackageOnly() + """"}""")
                "lock_screen" -> shell("input keyevent 223")
                "wake_screen" -> shell("input keyevent 224")
                "home" -> shell("input keyevent 3")
                "back" -> shell("input keyevent 4")
                "screen_text" -> screenText()
                "tap" -> tap(args)
                "long_press" -> longPress(args)
                "swipe" -> swipe(args)
                "scroll" -> scroll(args)
                "input_text" -> inputText(args)
                "key_event" -> keyEvent(args)
                "suspend_app" -> suspendOne(requirePackage(args))
                "unsuspend_app" -> unsuspendOne(requirePackage(args))
                "suspend_video_apps" -> suspendMany(args["packages"])
                "unsuspend_video_apps" -> unsuspendMany(args["packages"])
                "location" -> CommandOutcome.Fail("internal", "location requires the platform API, use CommandRunner")
                "usage_stats" -> usageStats(args["days"]?.toIntOrNull() ?: 1)
                "send_notification" -> CommandOutcome.Fail("internal", "notification is posted locally by the caller")
                "screenshot" -> screenshot()
                else -> CommandOutcome.Fail("unsupported_action", "unhandled action: " + action)
            }
        } catch (e: CommandException) {
            CommandOutcome.Fail(e.errorCode, e.message ?: "command failed")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            CommandOutcome.Fail("shell_failed", "手机命令执行失败")
        }
    }

    private class CommandException(val errorCode: String, message: String) : Exception(message)

    private fun requirePackage(args: Map<String, String>): String {
        val pkg = args["package"].orEmpty()
        if (!PACKAGE_REGEX.matches(pkg)) throw CommandException("bad_args", "invalid or missing package")
        return pkg
    }

    // ---------- UI automation ----------
    // Validation and command construction live in InputCommands so the rules can
    // be unit tested on the JVM without a device.

    private inline fun build(block: () -> String): CommandOutcome = try {
        shell(block())
    } catch (invalid: InputCommands.Invalid) {
        CommandOutcome.Fail(invalid.errorCode, invalid.message ?: "invalid arguments")
    }

    private fun tap(args: Map<String, String>) = build { InputCommands.tap(args) }

    private fun longPress(args: Map<String, String>) = build { InputCommands.longPress(args) }

    private fun swipe(args: Map<String, String>) = build { InputCommands.swipe(args) }

    private fun scroll(args: Map<String, String>) = build {
        val (width, height) = displaySize()
        InputCommands.scroll(args, width, height)
    }

    private fun inputText(args: Map<String, String>) = build { InputCommands.inputText(args) }

    private fun keyEvent(args: Map<String, String>) = build { InputCommands.keyEvent(args) }

    private fun displaySize(): Pair<Int, Int> {
        val output = (shell("wm size") as? CommandOutcome.Ok)?.output.orEmpty()
        val match = Regex("(\\d+)\\s*x\\s*(\\d+)").findAll(output).lastOrNull()
            ?: return DEFAULT_DISPLAY_WIDTH to DEFAULT_DISPLAY_HEIGHT
        val width = match.groupValues[1].toIntOrNull() ?: return DEFAULT_DISPLAY_WIDTH to DEFAULT_DISPLAY_HEIGHT
        val height = match.groupValues[2].toIntOrNull() ?: return DEFAULT_DISPLAY_WIDTH to DEFAULT_DISPLAY_HEIGHT
        if (width !in 100..MAX_INPUT_COORDINATE || height !in 100..MAX_INPUT_COORDINATE) {
            return DEFAULT_DISPLAY_WIDTH to DEFAULT_DISPLAY_HEIGHT
        }
        return width to height
    }

    // ---------- shell ----------

    /**
     * One long-lived `sh` behind Shizuku.
     *
     * Spawning a remote process costs seconds on this device, and every probe
     * used to pay it: a foreground check took 12.8 s because it started two
     * processes. A persistent session turns each command into a write + a read.
     */
    private inner class ShellSession {
        private val process: Process = newProcessArgv(arrayOf("sh"))
            ?: throw IllegalStateException("shizuku_shell_unavailable")
        private val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(process.outputStream))
        private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        @Volatile private var closed = false

        init {
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line -> lines.put(line) }
                } catch (_: Exception) {
                } finally {
                    closed = true
                    runCatching { lines.put("__DSH_EOF__") }
                }
            }
            reader.isDaemon = true
            reader.start()
        }

        fun run(command: String, timeoutMs: Long): CommandOutcome {
            if (closed) return CommandOutcome.Fail("shell_closed", "shell 会话已结束")
            val marker = "DSH_" + java.util.UUID.randomUUID().toString().take(8)
            return try {
                synchronized(this) { runLocked(command, marker, timeoutMs) }
            } catch (error: Exception) {
                closed = true
                CommandOutcome.Fail("shell_error", (error.message ?: error.javaClass.simpleName).take(120))
            }
        }

        private fun runLocked(command: String, marker: String, timeoutMs: Long): CommandOutcome {
            writer.write(command + " 2>&1; echo \"__" + marker + ":\$?\"\n")
            writer.flush()
            val output = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return closeWith(CommandOutcome.Fail("timeout", "手机命令执行超时"))
                val line = lines.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: return closeWith(CommandOutcome.Fail("timeout", "手机命令执行超时"))
                if (line == "__DSH_EOF__") {
                    closed = true
                    return CommandOutcome.Fail("shell_closed", "shell 会话已结束")
                }
                if (line.startsWith("__" + marker + ":")) {
                    val code = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    val text = output.toString().trim()
                    return if (code == 0) {
                        CommandOutcome.Ok(text.ifEmpty { "ok" })
                    } else {
                        CommandOutcome.Fail("shell_failed", text.take(200))
                    }
                }
                if (output.length < MAX_SHELL_OUTPUT_BYTES) output.appendLine(line)
            }
        }

        private fun closeWith(outcome: CommandOutcome): CommandOutcome {
            closed = true
            runCatching { process.destroy() }
            return outcome
        }

        fun close() {
            closed = true
            runCatching { process.destroy() }
        }
    }

    private var session: ShellSession? = null
    private val sessionLock = Any()

    private fun sessionOrNull(): ShellSession? = synchronized(sessionLock) {
        val current = session
        if (current != null) return current
        return try {
            ShellSession().also { session = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun dropSession() {
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
    }

    private fun shell(command: String, timeoutMs: Long = 25_000L): CommandOutcome {
        val live = sessionOrNull()
        if (live != null) {
            val outcome = live.run(command, timeoutMs)
            // A broken session falls back to a one-shot process instead of
            // failing the command outright.
            if (outcome is CommandOutcome.Ok || (outcome as? CommandOutcome.Fail)?.errorCode != "shell_closed") {
                if (outcome is CommandOutcome.Fail && outcome.errorCode == "timeout") dropSession()
                return outcome
            }
            dropSession()
        }
        return shellOnce(command, timeoutMs)
    }

    private fun shellOnce(command: String, timeoutMs: Long = 25_000L): CommandOutcome {
        val process = newProcess(command + " 2>&1")
            ?: return CommandOutcome.Fail("shizuku_unavailable", "Shizuku 无法创建命令进程")
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() < MAX_SHELL_OUTPUT_BYTES) {
                            bytes.write(buffer, 0, minOf(n, MAX_SHELL_OUTPUT_BYTES - bytes.size()))
                        }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            // Shizuku's remote process throws IllegalThreadStateException
            // ("process hasn't exited") from waitFor(timeout, unit); it only
            // implements the no-arg wait. Poll exitValue() with a deadline
            // instead, or every shell action fails before it runs.
            if (!awaitExit(process, timeoutMs)) return CommandOutcome.Fail("timeout", "手机命令执行超时")
            reader.join(1000)
            if (reader.isAlive) return CommandOutcome.Fail("timeout", "命令输出未完成")
            val output = bytes.toString(Charsets.UTF_8.name()).trim()
            return if (process.exitValue() == 0) CommandOutcome.Ok(output.ifEmpty { "ok" })
            else CommandOutcome.Fail("shell_failed", output.take(200))
        } finally { process.destroy() }
    }

    /** Wait for a Shizuku remote process to exit, tolerating its limited API. */
    private fun awaitExit(process: Process, timeoutMs: Long): Boolean {
        // Shizuku implements only the no-arg waitFor(); its timed overload
        // throws and exitValue() is unreliable while the remote side has not
        // been reaped. Block on waitFor() in a daemon thread and put the
        // deadline on the join instead.
        val waiter = Thread { runCatching { process.waitFor() } }
        waiter.isDaemon = true
        waiter.start()
        return try {
            waiter.join(timeoutMs)
            !waiter.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // Binary-safe variant. Never merges stderr and never decodes as UTF-8.
    private fun shellBytes(command: String, limit: Int = MAX_SCREENSHOT_BYTES * 4): ByteArray? {
        val process = newProcess(command) ?: return null
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() < limit) bytes.write(buffer, 0, minOf(n, limit - bytes.size()))
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            if (!awaitExit(process, 30_000L)) return null
            reader.join(2000)
            if (reader.isAlive) return null
            if (process.exitValue() != 0) return null
            return bytes.toByteArray()
        } finally { process.destroy() }
    }

    // Shizuku.newProcess is package-private in the SDK but is the only
    // programmatic shell entry without defining a UserService AIDL. Runtime
    // access works because the SDK and this app share no package check on the
    // reflective path. R8 keep rules in proguard-rules.pro protect this call.
    // If a future Shizuku release locks this down, migrate to a UserService.
    private fun newProcess(command: String): Process? = newProcessArgv(arrayOf("sh", "-c", command))

    /** Same reflective call, but with an explicit argv (used for the sh session). */
    private fun newProcessArgv(argv: Array<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, argv, null, null) as? Process
    } catch (_: Exception) {
        null
    }

    // ---------- read-only inspection ----------

    /** Fast path used by task verification: no usage-stats aggregation. */
    private fun quickStatus(): CommandOutcome {
        val ready = try { shizukuReady() } catch (_: Throwable) { false }
        var foregroundDebug = ""
        val foreground = try {
            if (ready) {
                val probe = foregroundProbe()
                foregroundDebug = probe.second
                probe.first
            } else {
                foregroundDebug = "shizuku_not_ready"
                ""
            }
        } catch (error: Exception) {
            foregroundDebug = ("probe_exception:" + (error.message ?: error.javaClass.simpleName)).take(120)
            ""
        }
        val screenInteractive = screenInteractiveState()
        val output = JSONObject().put("foreground_package", foreground)
            .put("foreground_debug", foregroundDebug)
            .put("shizuku_ready", ready)
            .put("screen_interactive", if (screenInteractive == null) JSONObject.NULL else screenInteractive)
            .toString()
        return CommandOutcome.Ok(output)
    }

    private fun status(): CommandOutcome {
        // Each probe is isolated: one failing source must not blank the whole
        // snapshot. A Shizuku shell hiccup used to zero out battery, foreground
        // and screen time together, which looked like "permissions not given".
        val battery = try { batteryState() } catch (_: Exception) { BatteryState(-1, false) }
        val ready = try { shizukuReady() } catch (_: Throwable) { false }
        var foregroundDebug = ""
        val foreground = try {
            if (ready) {
                val probe = foregroundProbe()
                foregroundDebug = probe.second
                probe.first
            } else {
                foregroundDebug = "shizuku_not_ready"
                ""
            }
        } catch (error: Exception) {
            foregroundDebug = ("probe_exception:" + (error.message ?: error.javaClass.simpleName)).take(120)
            ""
        }
        val screenToday = try { screenOnSecondsToday() } catch (_: Exception) { 0L }
        val screenInteractive = screenInteractiveState()
        val output = JSONObject().put("model", android.os.Build.MODEL)
            .put("battery_percent", if (battery.level >= 0) battery.level else JSONObject.NULL)
            .put("charging", battery.charging).put("foreground_package", foreground)
            .put("screen_on_seconds_today", screenToday)
            .put("screen_interactive", if (screenInteractive == null) JSONObject.NULL else screenInteractive)
            .put("shizuku_ready", ready)
            .put("foreground_debug", foregroundDebug)
            .toString()
        return CommandOutcome.Ok(output)
    }

    /** Reads whether the screen is currently on. Returns null when the state
     *  cannot be determined, so the caller never treats unknown as on. */
    private fun screenInteractiveState(): Boolean? = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        power.isInteractive
    } catch (_: Exception) { null }

    private class BatteryState(val level: Int, val charging: Boolean)

    private fun batteryState(): BatteryState {
        // BatteryManager reads capacity without a receiver, which some Android 14
        // builds reject for a null-receiver registration.
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = try { manager?.isCharging ?: false } catch (_: Exception) { false }
        if (capacity in 0..100) return BatteryState(capacity, charging)
        val battery = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }
        val rawLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return BatteryState(level, charging || plugged)
    }

    private fun foregroundPackageOnly(): String = foregroundProbe().first

    /**
     * Foreground package plus a diagnostic string when it could not be read.
     *
     * Android 12+ renamed the resumed-activity field and some OEM builds hide
     * one dump or the other. Try both dumps, then usage stats, and keep the raw
     * failure so the server can explain an empty observation.
     */
    private fun foregroundProbe(): Pair<String, String> {
        // Two cheap queries instead of one heavy one: dumpsys activity can take
        // tens of seconds on a loaded phone, while the window dump is quick.
        // Activity first: the window dump times out on some OEMs (8 s of the
        // 12.8 s we measured), while the activity grep answers in a few seconds.
        val commands = listOf(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -3",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -3",
        )
        var failure = ""
        for (command in commands) {
            when (val outcome = shell(command, 3_000L)) {
                is CommandOutcome.Fail -> failure = "shell_fail:" + outcome.message
                is CommandOutcome.Ok ->
                    PACKAGE_REGEX.find(outcome.output)?.value?.let { return it to "" }
            }
        }
        val fallback = foregroundFromUsageStats()
        if (fallback.isNotBlank()) return fallback to "usage_stats"
        return "" to failure.ifBlank { "empty:no_package" }
    }

    /** Last app seen moving to the foreground within the usage window. */
    private fun foregroundFromUsageStats(): String {
        if (!hasUsageAccess()) return ""
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 10 * 60_000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var last = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName.orEmpty()
                }
            }
            PACKAGE_REGEX.find(last)?.value.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun screenText(): CommandOutcome {
        val dumpResult = shell("uiautomator dump --compressed /data/local/tmp/window.xml")
        if (dumpResult is CommandOutcome.Fail) return dumpResult
        val xmlResult = shell("cat /data/local/tmp/window.xml")
        if (xmlResult is CommandOutcome.Fail) return xmlResult
        val xml = (xmlResult as CommandOutcome.Ok).output
        val nodes = NODE_TEXT_REGEX.findAll(xml).mapNotNull { it.groupValues.getOrNull(1) }.take(80).toList()
        return CommandOutcome.Ok(nodes.joinToString("\n"))
    }

    // ---------- screenshot ----------

    // Returns a downscaled JPEG as base64 so the picture can travel inside the
    // result envelope over both the direct and the relay transport.
    private fun screenshot(): CommandOutcome {
        val remote = "/data/local/tmp/phone_agent_shot.png"
        val capture = shell("screencap -p " + remote)
        if (capture is CommandOutcome.Fail) return capture
        val raw = try {
            shellBytes("cat " + remote)
        } finally {
            shell("rm -f " + remote)
        }
        if (raw == null || raw.isEmpty()) return CommandOutcome.Fail("shell_failed", "读取截图失败")
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return CommandOutcome.Fail("shell_failed", "截图解码失败")
        val scaled = downscale(decoded)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, stream)
        val width = scaled.width
        val height = scaled.height
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val data = stream.toByteArray()
        if (data.isEmpty()) return CommandOutcome.Fail("shell_failed", "截图编码失败")
        if (data.size > MAX_SCREENSHOT_BYTES) {
            return CommandOutcome.Fail("too_large", "截图过大，请降低分辨率后重试")
        }
        return CommandOutcome.Ok(
            JSONObject()
                .put("format", "jpeg")
                .put("width", width)
                .put("height", height)
                .put("bytes", data.size)
                .put("data_base64", Base64.encodeToString(data, Base64.NO_WRAP))
                .toString()
        )
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_SCREENSHOT_EDGE) return source
        val ratio = MAX_SCREENSHOT_EDGE.toDouble() / longest.toDouble()
        val width = maxOf(1, (source.width * ratio).toInt())
        val height = maxOf(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    // ---------- app policy ----------

    private fun suspendOne(pkg: String): CommandOutcome {
        shell("pm suspend --user 0 " + pkg)
        return verifiedState(pkg, expectedSuspended = true)
    }

    private fun unsuspendOne(pkg: String): CommandOutcome {
        shell("pm unsuspend --user 0 " + pkg)
        return verifiedState(pkg, expectedSuspended = false)
    }

    private fun suspendMany(packagesCsv: String?): CommandOutcome {
        val packages = parsePackages(packagesCsv)
        if (packages.isEmpty()) throw CommandException("bad_args", "no packages to suspend")
        val result = shell("pm suspend --user 0 " + packages.joinToString(" "))
        if (result is CommandOutcome.Fail) return result
        val allVerified = packages.all { verifiedState(it, true) is CommandOutcome.Ok }
        return if (allVerified) CommandOutcome.Ok("suspended " + packages.size + " apps") else
            CommandOutcome.Fail("verify_failed", "some packages did not reach suspended state")
    }

    private fun unsuspendMany(packagesCsv: String?): CommandOutcome {
        val packages = parsePackages(packagesCsv)
        if (packages.isEmpty()) throw CommandException("bad_args", "no packages to unsuspend")
        val result = shell("pm unsuspend --user 0 " + packages.joinToString(" "))
        if (result is CommandOutcome.Fail) return result
        val allVerified = packages.all { verifiedState(it, false) is CommandOutcome.Ok }
        return if (allVerified) CommandOutcome.Ok("unsuspended " + packages.size + " apps") else
            CommandOutcome.Fail("verify_failed", "some packages did not reach unsuspended state")
    }

    private fun verifiedState(pkg: String, expectedSuspended: Boolean): CommandOutcome {
        val dump = (shell("dumpsys package " + pkg) as? CommandOutcome.Ok)?.output ?: ""
        val suspended = Regex("suspended[= ]+?(true|false)", RegexOption.IGNORE_CASE).findAll(dump)
            .lastOrNull()?.groupValues?.get(1)?.lowercase()
        return when {
            suspended == null -> CommandOutcome.Fail("verify_failed", "could not read suspended state for " + pkg)
            suspended.toBoolean() != expectedSuspended ->
                CommandOutcome.Fail("verify_failed", pkg + " state is " + suspended + ", expected " + expectedSuspended)
            else -> CommandOutcome.Ok(
                """{"package":"""" + pkg + """","state":"""" +
                    (if (expectedSuspended) "suspended" else "unsuspended") + """","verified":true}"""
            )
        }
    }

    private fun parsePackages(csv: String?): List<String> =
        csv.orEmpty().split(",", " ").map { it.trim() }.filter { PACKAGE_REGEX.matches(it) }

    // ---------- usage ----------

    private fun usageStats(days: Int): CommandOutcome {
        if (!hasUsageAccess()) return CommandOutcome.Fail("denied", "请在权限管理中开启使用情况访问")
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usage.queryAndAggregateUsageStats(now - days.coerceIn(1, 30) * 86_400_000L, now)
        val items = stats.entries
            .filter { it.value.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.value.totalTimeInForeground }
            .joinToString(",") { (pkg, s) -> "\"" + pkg + "\":" + (s.totalTimeInForeground / 60_000) }
        return CommandOutcome.Ok("{" + items + "}")
    }

    private fun screenOnSecondsToday(): Long {
        // Best-effort foreground usage since the phone's local midnight.
        if (!hasUsageAccess()) return 0
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = usage.queryAndAggregateUsageStats(start, now)
        return stats.values.sumOf { it.totalTimeInForeground } / 1000
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val NODE_TEXT_REGEX = Regex("""text="([^"]{1,120})"""")
        private const val MAX_SHELL_OUTPUT_BYTES = 8_000_000
        private const val MAX_SCREENSHOT_EDGE = 1600
        private const val SCREENSHOT_QUALITY = 75
        private const val MAX_SCREENSHOT_BYTES = 2_200_000
        private const val DEFAULT_DISPLAY_WIDTH = 1080
        private const val DEFAULT_DISPLAY_HEIGHT = 1920
    }
}
