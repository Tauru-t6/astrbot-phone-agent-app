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
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.UUID
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
                "open_app" -> shellOnce(AppEntryPoint.command(requirePackage(args),args["entry_point"]?:"app_home"))
                "close_app" -> shellOnce("am force-stop " + requirePackage(args))
                "foreground_app" -> CommandOutcome.Ok("""{"package":"""" + foregroundPackageOnly() + """"}""")
                "lock_screen" -> shellOnce("input keyevent 223")
                "wake_screen" -> shellOnce("input keyevent 224")
                "home" -> shellOnce("input keyevent 3")
                "back" -> shellOnce("input keyevent 4")
                "screen_text" -> screenText()
                "ui_snapshot" -> uiSnapshot(args)
                "ui_action" -> uiAction(args)
                "ui_visual_snapshot" -> visualSnapshot(args)
                "ui_visual_action" -> visualAction(args)
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
        } catch (e: UiFailure) {
            CommandOutcome.Fail(e.code, e.message ?: "界面操作失败")
        } catch (e: CommandException) {
            CommandOutcome.Fail(e.errorCode, e.message ?: "command failed")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            CommandOutcome.Fail("shell_failed", "手机命令执行失败: " + (e.message ?: e.javaClass.simpleName).take(120))
        }    }

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

    // ---------- bound UI observations and actions ----------

    private val uiLease = UiSnapshotLease()
    private val visualReader=VisualSnapshotReader()
    private var visualFrame:VisualFrame?=null
    private val componentPattern = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.]+")

    /** A live activity probe, deliberately without the stale UsageStats fallback. */
    private fun liveUiPackage(): String {
        val value = shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -3", 3_000L)
        if (value !is CommandOutcome.Ok) throw UiFailure("ui_foreground_unavailable", "无法确认当前前台应用")
        return PACKAGE_REGEX.find(value.output)?.value ?: throw UiFailure("ui_foreground_unavailable", "无法确认当前前台应用")
    }

    private fun currentInputMethod(): String {
        val value = (shell("settings get secure default_input_method", 2_000L) as? CommandOutcome.Ok)?.output?.trim().orEmpty()
        return value.takeIf(componentPattern::matches).orEmpty()
    }

    private fun freshUi(expected: String): UiSnapshot {
        if (!UiSnapshotParser.validPackage(expected)) throw UiFailure("bad_args", "缺少有效目标应用")
        if (screenInteractiveState() != true) throw UiFailure("ui_screen_off", "屏幕未亮起，请解锁后重试")
        if (liveUiPackage() != expected) throw UiFailure("ui_foreground_changed", "前台应用不在本次任务范围")
        val path = "/data/local/tmp/phone_control_ui_${UUID.randomUUID().toString().replace("-", "")}.xml"
        val captured: String
        try {
            val dump = shell("timeout 8 uiautomator dump --compressed $path", 10_000L)
            if (dump !is CommandOutcome.Ok) throw UiFailure("ui_snapshot_unavailable", "界面读取超时或失败，请保持目标应用在前台")
            val size = (shell("wc -c < $path", 2_000L) as? CommandOutcome.Ok)?.output?.trim()?.toIntOrNull()
                ?: throw UiFailure("ui_snapshot_invalid", "本次界面文件无效")
            if (size !in 1..UiSnapshotParser.MAX_BYTES) throw UiFailure("ui_snapshot_too_large", "界面数据过大")
            val read = shell("cat $path", 2_000L)
            if (read !is CommandOutcome.Ok) throw UiFailure("ui_snapshot_invalid", "无法读取本次界面文件")
            captured = read.output
        } finally {
            // Generated path contains only a fixed prefix and hexadecimal UUID.
            shell("rm -f $path", 2_000L)
        }
        if (liveUiPackage() != expected) throw UiFailure("ui_foreground_changed", "读取期间前台应用已变化")
        val sizeText = (shell("wm size", 2_000L) as? CommandOutcome.Ok)?.output.orEmpty()
        val sizeMatch = Regex("(\\d+)\\s*x\\s*(\\d+)").findAll(sizeText).lastOrNull()
            ?: throw UiFailure("ui_display_unknown", "无法读取屏幕尺寸")
        val width = sizeMatch.groupValues[1].toIntOrNull() ?: 0
        val height = sizeMatch.groupValues[2].toIntOrNull() ?: 0
        return UiSnapshotParser.parse(captured, expected, width, height, SystemClock.elapsedRealtime(),
            inputMethodPackage = currentInputMethod().substringBefore('/'))
    }

    private fun uiSnapshot(args: Map<String, String>): CommandOutcome {
        visualFrame=null
        uiLease.invalidate()
        if (args.keys.any { it != "expected_package" }) throw UiFailure("bad_args", "界面读取含未支持参数")
        val snapshot = freshUi(args["expected_package"].orEmpty())
        uiLease.remember(snapshot)
        return CommandOutcome.Ok(JSONObject(snapshot.wire()).toString())
    }

    private fun checkedVisualBitmap(expected:String):Bitmap {
        if(!UiSnapshotParser.validPackage(expected)||expected in setOf("com.android.settings","com.android.systemui","com.google.android.permissioncontroller","com.android.permissioncontroller"))
            throw UiFailure("ui_sensitive_page","此应用不能使用截图操作，请使用结构化控件或手动接手")
        if(screenInteractiveState()!=true||liveUiPackage()!=expected)throw UiFailure("ui_foreground_changed","截图前目标应用不在前台")
        val window=(shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -3",3_000L) as? CommandOutcome.Ok)?.output.orEmpty()
        val focused=window.lineSequence().firstOrNull{it.contains("mCurrentFocus")}.orEmpty()
        if(PACKAGE_REGEX.find(focused)?.value!=expected)throw UiFailure("ui_target_obscured","目标窗口被遮挡，暂停截图操作")
        // When semantic information exists it is an additional sensitive-page
        // check, never a reason to bypass a password or foreground failure.
        try { if(freshUi(expected).nodes.any{it.password})throw UiFailure("ui_sensitive_page","密码页面不允许截图操作") }
        catch(error:UiFailure){if(error.code !in setOf("ui_snapshot_unavailable","ui_snapshot_invalid","ui_snapshot_too_large"))throw error}
        val path="/data/local/tmp/phone_control_visual_${UUID.randomUUID().toString().replace("-","")}.png"
        val raw=try {
            if(shell("screencap -p $path",5_000L) is CommandOutcome.Fail)throw UiFailure("ui_snapshot_unavailable","截图失败")
            shellBytes("cat $path")?:throw UiFailure("ui_snapshot_unavailable","截图读取失败")
        }finally{shell("rm -f $path",2_000L)}
        if(raw.size>16*1024*1024)throw UiFailure("too_large","截图过大")
        if(liveUiPackage()!=expected)throw UiFailure("ui_foreground_changed","截图期间目标应用已变化")
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(raw,0,raw.size,options)
        if(options.outWidth !in 100..10000||options.outHeight !in 100..10000||options.outWidth.toLong()*options.outHeight>16_000_000)
            throw UiFailure("too_large","截图尺寸超出支持范围")
        return BitmapFactory.decodeByteArray(raw,0,raw.size)?:throw UiFailure("ui_snapshot_invalid","截图无法解码")
    }

    private fun visualSnapshot(args:Map<String,String>):CommandOutcome {
        // This is a read. Keep the independent semantic lease for a model
        // that uses the picture only to choose an existing semantic node;
        // uiAction still re-dumps and verifies that target before dispatch.
        visualFrame=null
        if(args.keys!=setOf("expected_package"))throw UiFailure("bad_args","截图观察参数无效")
        val expected=args.getValue("expected_package");val bitmap=checkedVisualBitmap(expected)
        val frame=try{visualReader.read(bitmap,expected)}finally{bitmap.recycle()}
        visualFrame=frame
        return CommandOutcome.Ok(visualReader.wire(frame))
    }

    private fun visualAction(args:Map<String,String>):CommandOutcome {
        UiActionPolicy.validate(args)
        val previous=visualFrame;visualFrame=null
        if(previous==null||previous.snapshot.id!=args["snapshot_id"]||previous.snapshot.packageName!=args["expected_package"])
            throw UiFailure("ui_snapshot_expired","截图授权已过期")
        val kind=args.getValue("kind")
        val target=if(kind=="tap")previous.snapshot.nodes.singleOrNull{it.id==args["node_id"]}
            ?:throw UiFailure("ui_node_missing","截图文字节点不存在")else null
        val bitmap=checkedVisualBitmap(previous.snapshot.packageName)
        val currentHash=try{visualReader.hash(bitmap)}finally{bitmap.recycle()}
        VisualTextPolicy.checkAction(kind,previous.imageHash,currentHash,SystemClock.elapsedRealtime()-previous.snapshot.capturedMonotonicMs,target?.text.orEmpty())
        if(liveUiPackage()!=previous.snapshot.packageName)throw UiFailure("ui_foreground_changed","操作前应用已变化")
        val command=if(kind=="back")"input keyevent 4"else{
            val b=target!!.bounds;"input tap ${(b[0]+b[2])/2} ${(b[1]+b[3])/2}"
        }
        if(shell(command,3_000L) is CommandOutcome.Fail)throw UiFailure("ui_command_failed","截图动作失败，请重新观察")
        return CommandOutcome.Ok(JSONObject().put("dispatched",true).put("verified",false)
            .put("snapshot_id",previous.snapshot.id).put("requires_new_observation",true).toString())
    }

    private fun uiAction(args: Map<String, String>): CommandOutcome {
        UiActionPolicy.validate(args)
        val expected = args.getValue("expected_package")
        val previous = uiLease.take(args.getValue("snapshot_id"), expected, SystemClock.elapsedRealtime())
        val fresh = freshUi(expected)
        if (SystemClock.elapsedRealtime() - previous.capturedMonotonicMs > 30_000)
            throw UiFailure("ui_snapshot_expired", "界面快照已过期，请重新观察")
        val node = UiActionPolicy.target(previous, fresh, args)
        if (liveUiPackage() != expected) throw UiFailure("ui_foreground_changed", "动作前前台应用已变化")
        val result = when (args.getValue("kind")) {
            "tap" -> shell("input tap ${(node!!.bounds[0] + node.bounds[2]) / 2} ${(node.bounds[1] + node.bounds[3]) / 2}", 3_000L)
            "scroll" -> {
                val b = node!!.bounds
                val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
                val dx = (b[2] - b[0]) / 4; val dy = (b[3] - b[1]) / 4
                if (dx < 10 || dy < 10) throw UiFailure("ui_action_blocked", "滚动目标过小")
                val points = when (args["direction"]) {
                    "down" -> listOf(cx, cy + dy, cx, cy - dy)
                    "up" -> listOf(cx, cy - dy, cx, cy + dy)
                    "right" -> listOf(cx - dx, cy, cx + dx, cy)
                    else -> listOf(cx + dx, cy, cx - dx, cy)
                }
                shell("input swipe ${points.joinToString(" ")} 300", 3_000L)
            }
            "input_text" -> uiInput(expected, node!!, args.getValue("text"))
            else -> shell("input keyevent 4", 3_000L)
        }
        if (result is CommandOutcome.Fail) return CommandOutcome.Fail(result.errorCode, "界面动作失败；请重新观察，不要自动重放")
        return CommandOutcome.Ok(JSONObject().put("dispatched", true).put("verified", false)
            .put("snapshot_id", previous.id).put("kind", args.getValue("kind"))
            .put("requires_new_observation", true).toString())
    }

    private fun uiInput(expectedPackage: String, approvedNode: UiNode, text: String): CommandOutcome {
        val ime = context.packageName + "/" + UiInputMethodService::class.java.name
        val oldIme = currentInputMethod()
        if (oldIme.isBlank()) throw UiFailure("needs_input_method", "请先在系统中启用 Phone Control 输入法")
        val enabled = (shell("ime list -s", 2_000L) as? CommandOutcome.Ok)?.output.orEmpty().lines().map(String::trim)
        val shortIme = android.content.ComponentName(context, UiInputMethodService::class.java).flattenToShortString()
        if (ime !in enabled && shortIme !in enabled) throw UiFailure("needs_input_method", "请先在系统输入法设置中启用 Phone Control 输入，随后重新发起任务")
        val switched = oldIme != ime && oldIme != shortIme
        var result: CommandOutcome = CommandOutcome.Fail("ui_input_failed", "未完成文字输入")
        try {
            if (switched && shell("ime set " + InputCommands.quote(ime), 3_000L) is CommandOutcome.Fail)
                throw UiFailure("needs_input_method", "无法切换到已启用的 Phone Control 输入法")
            val deadline = SystemClock.elapsedRealtime() + 4_000
            while (!UiInputMethodService.readyFor(expectedPackage) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(80)
            val editorSession = UiInputMethodService.sessionFor(expectedPackage)
                ?: throw UiFailure("ui_input_failed", "输入连接尚未就绪，请重新观察")
            if (liveUiPackage() != expectedPackage) throw UiFailure("ui_foreground_changed", "输入前前台应用已变化")
            // Input method switching may resize the page. Match the same one
            // focused editor by identity and visible value, never coordinates.
            val afterIme = freshUi(expectedPackage)
            val editors = afterIme.nodes.filter { it.editable && it.focused && !it.password && it.enabled }
            val editor = editors.singleOrNull()
            if (editor == null || editor.resourceId != approvedNode.resourceId || editor.className != approvedNode.className ||
                editor.text != approvedNode.text || editor.description != approvedNode.description)
                throw UiFailure("ui_snapshot_changed", "输入法切换后目标输入框已改变，请重新观察")
            result = if (UiInputMethodService.commitApprovedText(expectedPackage, text, approvedNode.text, editorSession)) CommandOutcome.Ok("ok")
                else CommandOutcome.Fail("ui_input_failed", "输入连接不可用或为敏感输入框，请重新观察")
        } finally {
            if (switched) {
                // Cancellation interrupts the worker. Clear that flag only for
                // the bounded restore, then preserve it for runInterruptible.
                val wasInterrupted = Thread.interrupted()
                val restored = try { shell("ime set " + InputCommands.quote(oldIme), 3_000L) }
                    finally { if (wasInterrupted) Thread.currentThread().interrupt() }
                if (restored is CommandOutcome.Fail && result is CommandOutcome.Ok)
                    result = CommandOutcome.Fail("ui_input_restore_failed", "文字可能已输入，但恢复原输入法失败，请手动恢复并重新观察")
            }
        }
        return result
    }

    // ---------- shell ----------

    /**
     * One long-lived `sh` behind Shizuku.
     *
     * Spawning a remote process costs seconds on this device, and every probe
     * used to pay it: a foreground check took 12.8 s because it started two
     * processes. A persistent session turns each command into a write + a read.
     */
    private var session: ShellCommandSession? = null
    private val sessionLock = Any()

    // Diagnostics: is the persistent shell in use, and how long a call took.
    @Volatile private var lastShellMillis: Long = 0
    @Volatile private var sessionError: String = ""

    private fun sessionMode(): String = if (session != null) "session" else "oneshot"

    private fun sessionOrNull(): ShellCommandSession? = synchronized(sessionLock) {
        val current = session
        if (current != null && !current.isClosed) return current
        return try {
            val process = newProcessArgv(arrayOf("sh"))
                ?: throw IllegalStateException("shizuku_shell_unavailable")
            ShellCommandSession(process, MAX_SHELL_OUTPUT_BYTES).also { session = it; sessionError = "" }
        } catch (error: Throwable) {
            // Surfaced through foreground_debug so the heartbeat carries it.
            sessionError = (error.javaClass.simpleName + ": " + (error.message ?: "")).take(90)
            null
        }
    }

    private fun dropSession(expected: ShellCommandSession) {
        synchronized(sessionLock) {
            expected.close()
            if (session === expected) session = null
        }
    }

    private fun shell(command: String, timeoutMs: Long = 25_000L): CommandOutcome {
        val started = System.nanoTime()
        try {
            val live = sessionOrNull() ?: return shellOnce(command, timeoutMs)
            return when (val outcome = live.run(command, timeoutMs)) {
                is ShellCommandSession.Result.Completed -> if (outcome.exitCode == 0) {
                    CommandOutcome.Ok(outcome.output.ifEmpty { "ok" })
                } else CommandOutcome.Fail("shell_failed", outcome.output.take(200))
                is ShellCommandSession.Result.Failed -> {
                    // A lost response does not prove the action did not run.
                    // Retire this session; only a new command may create a new one.
                    dropSession(live)
                    CommandOutcome.Fail(outcome.code, outcome.message)
                }
            }
        } finally {
            lastShellMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }
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
        waiter.join(timeoutMs)
        return !waiter.isAlive
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
        } catch (cancelled: InterruptedException) {
            throw cancelled
        } catch (error: Exception) {
            foregroundDebug = ("probe_exception:" + (error.message ?: error.javaClass.simpleName)).take(120)
            ""
        }
        val screenInteractive = screenInteractiveState()
        val debug = if (sessionError.isBlank()) foregroundDebug else (foregroundDebug + "|" + sessionError).take(160)
        val output = JSONObject().put("foreground_package", foreground)
            .put("foreground_debug", debug)
            .put("shizuku_ready", ready)
            .put("shell_mode", sessionMode())
            .put("last_shell_ms", lastShellMillis)
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
        } catch (cancelled: InterruptedException) {
            throw cancelled
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
            .put("shell_mode", sessionMode())
            .put("last_shell_ms", lastShellMillis)
            .put("foreground_debug", if (sessionError.isBlank()) foregroundDebug else (foregroundDebug + "|" + sessionError).take(160))
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
            "activity" to "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -3",
            "window" to "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -3",
        )
        var failure = ""
        for ((source, command) in commands) {
            val started = System.currentTimeMillis()
            when (val outcome = shell(command, 3_000L)) {
                is CommandOutcome.Fail -> failure = "shell_fail:" + outcome.message
                is CommandOutcome.Ok ->
                    PACKAGE_REGEX.find(outcome.output)?.value?.let {
                        return it to "src=$source ms=${System.currentTimeMillis() - started}"
                    }
            }
        }
        val fallbackStart = System.currentTimeMillis()
        val fallback = foregroundFromUsageStats()
        if (fallback.isNotBlank()) return fallback to "src=usage ms=${System.currentTimeMillis() - fallbackStart}"
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

    // Issue #1: uiautomator dump never returns on some devices (Galaxy S10,
    // Android 12), which outlives the caller's transport budget and ends as a
    // dropped connection instead of an error. The dump gets its own short
    // leash (toybox `timeout`, enforced again by the shell timeout), the temp
    // file is removed first so a stale dump is never read, and the top
    // activity's dumpsys view hierarchy is the fallback before a distinct
    // ui_dump_unavailable error is reported.
    private fun screenText(): CommandOutcome {
        shell("rm -f " + UI_DUMP_PATH, UI_DUMP_SETUP_TIMEOUT_MS)
        val dumped = shell("timeout " + UI_DUMP_LEASE_SECONDS + " uiautomator dump --compressed " + UI_DUMP_PATH,
            UI_DUMP_LEASE_SECONDS * 1000 + 4_000L)
        if (dumped is CommandOutcome.Ok) {
            val cat = shell("cat " + UI_DUMP_PATH, UI_DUMP_SETUP_TIMEOUT_MS)
            shell("rm -f " + UI_DUMP_PATH, UI_DUMP_SETUP_TIMEOUT_MS)
            if (cat is CommandOutcome.Ok) {
                return CommandOutcome.Ok(UiTextParser.parse(cat.output).joinToString("\n"))
            }
        }
        val fallback = shell("dumpsys activity top | grep -o 'text=\"[^\"]*\"' | head -100", UI_DUMP_FALLBACK_TIMEOUT_MS)
        if (fallback is CommandOutcome.Ok) {
            val nodes = UiTextParser.parse(fallback.output)
            if (nodes.isNotEmpty()) return CommandOutcome.Ok(nodes.joinToString("\n"))
        }
        return CommandOutcome.Fail("ui_dump_unavailable", "无法读取屏幕文本：uiautomator dump 未返回，dumpsys 兜底也没有内容")
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
        if (!isInstalled(pkg)) return notInstalled(pkg)
        shell("pm suspend --user 0 " + pkg)
        return verifiedState(pkg, expectedSuspended = true)
    }

    /** A missing package used to surface as "state is false, expected true". */
    private fun isInstalled(pkg: String): Boolean {
        val listed = (shell("pm list packages " + pkg) as? CommandOutcome.Ok)?.output.orEmpty()
        return listed.lineSequence().any { it.trim() == "package:$pkg" }
    }

    private fun notInstalled(pkg: String): CommandOutcome =
        CommandOutcome.Fail("not_installed", pkg + " 没有安装在这台手机上")

    private fun unsuspendOne(pkg: String): CommandOutcome {
        if (!isInstalled(pkg)) return notInstalled(pkg)
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
        private const val MAX_SHELL_OUTPUT_BYTES = 8_000_000
        private const val MAX_SCREENSHOT_EDGE = 1600
        private const val SCREENSHOT_QUALITY = 75
        private const val MAX_SCREENSHOT_BYTES = 2_200_000
        private const val DEFAULT_DISPLAY_WIDTH = 1080
        private const val DEFAULT_DISPLAY_HEIGHT = 1920
        private const val UI_DUMP_PATH = "/data/local/tmp/window.xml"
        // `timeout 8` bounds the dump inside the shell; the session leash adds
        // 4 s of margin, so screen_text answers well inside the plugin's
        // 35 s per-attempt transport window even when the dump hangs.
        private const val UI_DUMP_LEASE_SECONDS = 8
        private const val UI_DUMP_SETUP_TIMEOUT_MS = 3_000L
        private const val UI_DUMP_FALLBACK_TIMEOUT_MS = 12_000L
    }
}
