package com.tauru.astrbotphoneagent.logic

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.app.AppOpsManager
import android.media.MediaScannerConnection
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Executes the closed command set through Shizuku's shell. Each [execute]
 * call is synchronous and must run off the main thread.
 *
 * Verification rule (inherited from the plugin's policy): pm suspend /
 * unsuspend results are only successful when the phone confirms the new
 * state. Everything else reports the shell exit code.
 */
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
            return CommandOutcome.Fail("unsupported_action", "unknown action: $action")
        }
        if (action == "ping") return CommandOutcome.Ok("""{"pong":true}""")
        if (action == "status") return status()
        if (!shizukuReady()) {
            return CommandOutcome.Fail("shizuku_unavailable", "Shizuku is not running or permission not granted")
        }
        return try {
            when (action) {
                "ping" -> CommandOutcome.Ok("""{"pong":true}""")
                "status" -> status()
                "open_app" -> shell("monkey -p ${requirePackage(args)} 1")
                "close_app" -> shell("am force-stop ${requirePackage(args)}")
                "foreground_app" -> CommandOutcome.Ok("""{"package":"${foregroundPackageOnly()}"}""")
                "lock_screen" -> shell("input keyevent 223")
                "wake_screen" -> shell("input keyevent 224")
                "home" -> shell("input keyevent 3")
                "back" -> shell("input keyevent 4")
                "screen_text" -> screenText()
                "suspend_app" -> suspendOne(requirePackage(args))
                "unsuspend_app" -> unsuspendOne(requirePackage(args))
                "suspend_video_apps" -> suspendMany(args["packages"])
                "unsuspend_video_apps" -> unsuspendMany(args["packages"])
                "location" -> CommandOutcome.Fail("internal", "location requires the platform API, use LocationCommandRunner")
                "usage_stats" -> usageStats(args["days"]?.toIntOrNull() ?: 1)
                "send_notification" -> CommandOutcome.Fail("internal", "notification is posted locally by the caller")
                "screenshot" -> screenshot()
                else -> CommandOutcome.Fail("unsupported_action", "unhandled action: $action")
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

    private fun shell(command: String): CommandOutcome {
        val process = newProcess("$command 2>&1") ?: return CommandOutcome.Fail("shizuku_unavailable", "Shizuku 无法创建命令进程")
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() < 2_000_000) bytes.write(buffer, 0, minOf(n, 2_000_000 - bytes.size()))
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

    /**
     * Shizuku.newProcess is package-private in the SDK but is the only
     * programmatic shell entry without defining a UserService AIDL. Runtime
     * access works because the SDK and this app share no package check on
     * the reflective path. If a future Shizuku release locks this down,
     * migrate to a UserService (IShell.aidl) implementation.
     */
    private fun newProcess(command: String): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    } catch (e: Exception) {
        null
    }

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

    private fun screenshot(): CommandOutcome {
        val filename = "phone_agent_${System.currentTimeMillis()}.png"
        val path = "/sdcard/Pictures/PhoneAgent/$filename"
        val result = shell("mkdir -p /sdcard/Pictures/PhoneAgent && screencap -p $path")
        if (result is CommandOutcome.Fail) return result
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/png"), null)
        return CommandOutcome.Ok(JSONObject().put("saved_to", "Pictures/PhoneAgent/$filename").put("message", "截图已保存到相册").toString())
    }

    private fun suspendOne(pkg: String): CommandOutcome {
        shell("pm suspend --user 0 $pkg")
        return verifiedState(pkg, expectedSuspended = true)
    }

    private fun unsuspendOne(pkg: String): CommandOutcome {
        shell("pm unsuspend --user 0 $pkg")
        return verifiedState(pkg, expectedSuspended = false)
    }

    private fun suspendMany(packagesCsv: String?): CommandOutcome {
        val packages = parsePackages(packagesCsv)
        if (packages.isEmpty()) throw CommandException("bad_args", "no packages to suspend")
        val joined = packages.joinToString(" ")
        val result = shell("pm suspend --user 0 $joined")
        if (result is CommandOutcome.Fail) return result
        val allVerified = packages.all { verifiedState(it, true) is CommandOutcome.Ok }
        return if (allVerified) CommandOutcome.Ok("suspended ${packages.size} apps") else
            CommandOutcome.Fail("verify_failed", "some packages did not reach suspended state")
    }

    private fun unsuspendMany(packagesCsv: String?): CommandOutcome {
        val packages = parsePackages(packagesCsv)
        if (packages.isEmpty()) throw CommandException("bad_args", "no packages to unsuspend")
        val joined = packages.joinToString(" ")
        val result = shell("pm unsuspend --user 0 $joined")
        if (result is CommandOutcome.Fail) return result
        val allVerified = packages.all { verifiedState(it, false) is CommandOutcome.Ok }
        return if (allVerified) CommandOutcome.Ok("unsuspended ${packages.size} apps") else
            CommandOutcome.Fail("verify_failed", "some packages did not reach unsuspended state")
    }

    private fun verifiedState(pkg: String, expectedSuspended: Boolean): CommandOutcome {
        val dump = (shell("dumpsys package $pkg") as? CommandOutcome.Ok)?.output ?: ""
        val suspended = Regex("suspended[= ]+?(true|false)", RegexOption.IGNORE_CASE).findAll(dump)
            .lastOrNull()?.groupValues?.get(1)?.lowercase()
        return when {
            suspended == null -> CommandOutcome.Fail("verify_failed", "could not read suspended state for $pkg")
            suspended.toBoolean() != expectedSuspended ->
                CommandOutcome.Fail("verify_failed", "$pkg state is $suspended, expected $expectedSuspended")
            else -> CommandOutcome.Ok(
                """{"package":"$pkg","state":"${if (expectedSuspended) "suspended" else "unsuspended"}","verified":true}"""
            )
        }
    }

    private fun parsePackages(csv: String?): List<String> =
        csv.orEmpty().split(",", " ").map { it.trim() }.filter { PACKAGE_REGEX.matches(it) }

    private fun usageStats(days: Int): CommandOutcome {
        if (!hasUsageAccess()) return CommandOutcome.Fail("denied", "请在权限管理中开启使用情况访问")
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usage.queryAndAggregateUsageStats(now - days.coerceIn(1, 30) * 86_400_000L, now)
        val items = stats.entries
            .filter { it.value.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.value.totalTimeInForeground }
            .joinToString(",") { (pkg, s) ->
                "\"$pkg\":${(s.totalTimeInForeground / 60_000)}" // minutes
            }
        return CommandOutcome.Ok("{$items}")
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
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val NODE_TEXT_REGEX = Regex("""text="([^"]{1,120})"""")
    }
}
