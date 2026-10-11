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

    fun execute(action: String, args: Map<String, String>): CommandOutcome {
        if (action !in COMMAND_ACTIONS) {
            return CommandOutcome.Fail("unsupported_action", "unknown action: " + action)
        }
        if (action == "ping") return CommandOutcome.Ok("""{"pong":true}""")
        if (action == "status") return status()
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

    private fun shell(command: String): CommandOutcome {
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
            if (!process.waitFor(25, TimeUnit.SECONDS)) return CommandOutcome.Fail("timeout", "手机命令执行超时")
            reader.join(1000)
            if (reader.isAlive) return CommandOutcome.Fail("timeout", "命令输出未完成")
            val output = bytes.toString(Charsets.UTF_8.name()).trim()
            return if (process.exitValue() == 0) CommandOutcome.Ok(output.ifEmpty { "ok" })
            else CommandOutcome.Fail("shell_failed", output.take(200))
        } finally { process.destroy() }
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
            if (!process.waitFor(30, TimeUnit.SECONDS)) return null
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
    private fun newProcess(command: String): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    } catch (_: Exception) {
        null
    }

    // ---------- read-only inspection ----------

    private fun status(): CommandOutcome {
        val model = android.os.Build.MODEL
        val battery = batteryState()
        val foreground = if (shizukuReady()) foregroundPackageOnly() else ""
        val screenToday = screenOnSecondsToday()
        val output = JSONObject().put("model", model)
            .put("battery_percent", if (battery.level >= 0) battery.level else JSONObject.NULL)
            .put("charging", battery.charging).put("foreground_package", foreground)
            .put("screen_on_seconds_today", screenToday).put("shizuku_ready", shizukuReady()).toString()
        return CommandOutcome.Ok(output)
    }

    private class BatteryState(val level: Int, val charging: Boolean)

    private fun batteryState(): BatteryState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return BatteryState(level, charging)
    }

    private fun foregroundPackageOnly(): String {
        val dump = (shell("dumpsys activity activities | grep -E 'mResumedActivity|mCurrentFocus' | head -2") as? CommandOutcome.Ok)?.output ?: return ""
        return PACKAGE_REGEX.find(dump)?.value.orEmpty()
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

    private fun hasUsageAccess(): Boolean {
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
