package com.tauru.astrbotphoneagent.logic

import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal class UiFailure(val code: String, message: String) : Exception(message)

internal data class UiNode(
    val id: String, val resourceId: String, val text: String, val description: String,
    val className: String, val packageName: String, val bounds: List<Int>,
    val clickable: Boolean, val editable: Boolean, val focusable: Boolean,
    val focused: Boolean, val password: Boolean, val enabled: Boolean, val scrollable: Boolean,
    val contentFingerprint: String = "",
) {
    fun wire(): Map<String, Any> = linkedMapOf(
        "id" to id, "resource_id" to resourceId, "text" to text, "description" to description,
        "class_name" to className, "package" to packageName, "bounds" to bounds,
        "clickable" to clickable, "editable" to editable, "focusable" to focusable,
        "focused" to focused, "password" to password, "enabled" to enabled, "scrollable" to scrollable,
    )
    fun targetSignature() = listOf(resourceId, text, description, className, packageName, bounds,
        clickable, editable, focusable, focused, password, enabled, scrollable, contentFingerprint).joinToString("\u0000")
    fun structure() = listOf(resourceId, className, packageName, bounds, clickable, editable,
        focusable, focused, password, enabled, scrollable).joinToString("\u0000")
}

internal data class UiSnapshot(
    val id: String, val capturedAt: Double, val capturedMonotonicMs: Long, val packageName: String,
    val width: Int, val height: Int, val nodes: List<UiNode>, val fingerprint: String,
    val obscuredBounds: List<List<Int>> = emptyList(),
) {
    fun wire(): Map<String, Any> = linkedMapOf(
        "schema_version" to 1, "snapshot_id" to id, "captured_at" to capturedAt,
        "package" to packageName, "width" to width, "height" to height,
        "nodes" to nodes.map(UiNode::wire), "truncated" to false,
        "input_method_required" to true,
    )
}

internal object UiSnapshotParser {
    const val MAX_BYTES = 262_144
    const val MAX_NODES = 160
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    private val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    fun validPackage(value: String) = value.length <= 255 && packagePattern.matches(value)

    fun parse(raw: String, expectedPackage: String, width: Int, height: Int,
              monotonicMs: Long, capturedAt: Double = System.currentTimeMillis() / 1000.0,
              inputMethodPackage: String = ""): UiSnapshot {
        if (!validPackage(expectedPackage) || width !in 100..10_000 || height !in 100..10_000)
            throw UiFailure("bad_args", "无效应用或屏幕尺寸")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES || raw.contains("<!DOCTYPE", true) || raw.contains("<!ENTITY", true))
            throw UiFailure("ui_snapshot_invalid", "界面数据过大或格式不受支持")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val document = try {
            factory.newDocumentBuilder().apply {
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun error(e: org.xml.sax.SAXParseException) { throw e }
                    override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
                })
            }.parse(InputSource(StringReader(raw)))
        }
        catch (_: Exception) { throw UiFailure("ui_snapshot_invalid", "无法解析本次界面结构") }
        if (document.documentElement.tagName != "hierarchy") throw UiFailure("ui_snapshot_invalid", "界面结构无效")
        val rotation = document.documentElement.getAttribute("rotation").toIntOrNull()
        if (rotation !in 0..3) throw UiFailure("ui_snapshot_invalid", "界面旋转状态未知")
        val effectiveWidth = if (rotation == 1 || rotation == 3) height else width
        val effectiveHeight = if (rotation == 1 || rotation == 3) width else height
        val all = document.getElementsByTagName("node")
        if (all.length > 1000) throw UiFailure("ui_snapshot_too_large", "界面节点过多，请缩小任务范围")
        val nodes = mutableListOf<UiNode>()
        val obscuredBounds = mutableListOf<List<Int>>()
        var otherVisibleWindow = false
        for (index in 0 until all.length) {
            val element = all.item(index) as? Element ?: continue
            val bounds = boundsPattern.matchEntire(element.getAttribute("bounds"))?.groupValues?.drop(1)?.map { it.toIntOrNull() }
                ?: continue
            if (bounds.any { it == null }) continue
            val b = bounds.map { it!! }
            if (b[0] < 0 || b[1] < 0 || b[2] > effectiveWidth || b[3] > effectiveHeight || b[0] >= b[2] || b[1] >= b[3]) continue
            val pkg = element.getAttribute("package")
            if (pkg != expectedPackage) {
                // Keyboard/system chrome is omitted. Any other app's visible
                // nodes invalidate the capture rather than authorizing a tap.
                if (pkg.isNotEmpty() && pkg != "com.android.systemui" && pkg != inputMethodPackage)
                    otherVisibleWindow = true
                else if (pkg.isNotEmpty()) obscuredBounds += b
                continue
            }
            fun flag(name: String) = element.getAttribute(name) == "true"
            val password = flag("password") || generateSequence(element.parentNode) { it.parentNode }
                .filterIsInstance<Element>().any { it.getAttribute("password") == "true" }
            fun text(name: String) = if (password) "" else element.getAttribute(name).filter { it.code >= 0x20 }.take(160)
            val klass = element.getAttribute("class").take(160)
            val editable = klass.endsWith("EditText") || flag("editable")
            val label = text("text")
            val description = text("content-desc")
            if (label.isBlank() && description.isBlank() && !flag("clickable") && !flag("scrollable") && !editable) continue
            if (nodes.size >= MAX_NODES) throw UiFailure("ui_snapshot_too_large", "可操作节点过多，请缩小任务范围")
            nodes += UiNode("n${nodes.size}", element.getAttribute("resource-id").take(200), label, description,
                klass, pkg, b, flag("clickable"), editable, flag("focusable"), flag("focused"), password,
                flag("enabled"), flag("scrollable"), contentFingerprint(element))
        }
        if (otherVisibleWindow) throw UiFailure("ui_foreground_changed", "界面包含其它应用窗口，请重新观察")
        if (nodes.isEmpty()) throw UiFailure("ui_snapshot_unavailable", "目标应用未提供可用界面节点")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest((expectedPackage + "|$effectiveWidth|$effectiveHeight|" + nodes.joinToString("\n", transform = UiNode::structure)).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return UiSnapshot(UUID.randomUUID().toString(), capturedAt, monotonicMs, expectedPackage, effectiveWidth, effectiveHeight, nodes, fingerprint, obscuredBounds)
    }

    // A clickable parent often has no own label. Bind its visible descendants
    // too, so a child changing from "搜索" to "付款" invalidates the action.
    private fun contentFingerprint(root: Element): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(element: Element) {
            val password = generateSequence<org.w3c.dom.Node>(element) { it.parentNode }
                .filterIsInstance<Element>().any { it.getAttribute("password") == "true" }
            if (password) return
            for (field in listOf("text", "content-desc")) {
                digest.update(element.getAttribute(field).toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
        }
        append(root)
        val children = root.getElementsByTagName("node")
        for (index in 0 until children.length) (children.item(index) as? Element)?.let(::append)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** One in-memory observation authorizes at most one action; nothing is persisted. */
internal class UiSnapshotLease {
    private var current: UiSnapshot? = null
    @Synchronized fun remember(snapshot: UiSnapshot) { current = snapshot }
    @Synchronized fun invalidate() { current = null }
    @Synchronized fun take(snapshotId: String, expectedPackage: String, nowMs: Long): UiSnapshot {
        val old = current
        current = null // even rejected actions cannot probe/reuse a token
        if (old == null || old.id != snapshotId) throw UiFailure("ui_snapshot_expired", "请重新读取界面")
        if (old.packageName != expectedPackage) throw UiFailure("ui_foreground_changed", "目标应用已变化")
        if (nowMs < old.capturedMonotonicMs || nowMs - old.capturedMonotonicMs > 30_000)
            throw UiFailure("ui_snapshot_expired", "界面快照已过期，请重新读取")
        return old
    }
}

internal object UiActionPolicy {
    val kinds = setOf("tap", "input_text", "scroll", "back")
    fun validate(args: Map<String, String>) {
        val kind = args["kind"]
        if (kind !in kinds || !UiSnapshotParser.validPackage(args["expected_package"].orEmpty()) ||
            !Regex("[a-fA-F0-9-]{36}").matches(args["snapshot_id"].orEmpty())) throw UiFailure("bad_args", "界面动作参数无效")
        val allowed = setOf("kind", "expected_package", "snapshot_id") + when (kind) {
            "tap" -> setOf("node_id")
            "input_text" -> setOf("node_id", "text")
            "scroll" -> setOf("node_id", "direction")
            else -> emptySet()
        }
        if (args.keys.any { it !in allowed }) throw UiFailure("bad_args", "界面动作含未支持参数")
        if (kind != "back" && !Regex("n[0-9]{1,3}").matches(args["node_id"].orEmpty())) throw UiFailure("bad_args", "缺少界面节点")
        if (kind == "input_text") {
            val text = args["text"].orEmpty()
            if (text.isEmpty() || text.length > 200 || text.any { it.code < 0x20 || it.code == 0x7f }) throw UiFailure("bad_args", "输入文字长度或字符无效")
        }
        if (kind == "scroll" && args["direction"] !in setOf("up", "down", "left", "right")) throw UiFailure("bad_args", "滚动方向无效")
    }
    fun target(before: UiSnapshot, fresh: UiSnapshot, args: Map<String, String>): UiNode? {
        if (fresh.packageName != before.packageName || fresh.fingerprint != before.fingerprint)
            throw UiFailure("ui_snapshot_changed", "界面已改变，请重新观察后决定动作")
        if (args["kind"] == "back") return null
        val node = before.nodes.singleOrNull { it.id == args["node_id"] }
            ?: throw UiFailure("ui_node_missing", "目标节点不存在")
        val current = fresh.nodes.singleOrNull { it.id == node.id }
        if (current == null || current.targetSignature() != node.targetSignature()) throw UiFailure("ui_snapshot_changed", "目标节点已改变")
        if (!node.enabled || node.password) throw UiFailure("ui_action_blocked", "不能操作禁用或密码输入节点")
        if (args["kind"] == "tap" && !(node.clickable || node.editable)) throw UiFailure("ui_action_blocked", "目标节点不可点击")
        if (args["kind"] == "input_text" && (!node.editable || !node.focused)) throw UiFailure("ui_input_not_focused", "请先点击并重新观察输入框")
        if (args["kind"] == "scroll" && !node.scrollable) throw UiFailure("ui_action_blocked", "目标节点不可滚动")
        val b = node.bounds
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        val gesture = if (args["kind"] == "scroll") {
            if (args["direction"] in setOf("up", "down")) listOf(cx, cy - (b[3] - b[1]) / 4, cx + 1, cy + (b[3] - b[1]) / 4 + 1)
            else listOf(cx - (b[2] - b[0]) / 4, cy, cx + (b[2] - b[0]) / 4 + 1, cy + 1)
        } else listOf(cx, cy, cx + 1, cy + 1)
        if (fresh.obscuredBounds.any { it[0] < gesture[2] && it[2] > gesture[0] && it[1] < gesture[3] && it[3] > gesture[1] })
            throw UiFailure("ui_target_obscured", "目标被系统窗口或输入法遮挡，请重新观察")
        val same = fresh.nodes.count { it.structure() == node.structure() }
        if (same != 1) throw UiFailure("ui_target_ambiguous", "目标节点存在重叠，请重新观察")
        return node
    }
}
