package com.tauru.astrbotphoneagent.logic

/**
 * Builds the shell command line for the UI-automation actions.
 *
 * This object is deliberately free of Android dependencies so the validation
 * rules can be unit tested on the JVM. [ShizukuCommandExecutor] only supplies
 * the display size and runs the result.
 *
 * Safety: every value that reaches the command string is either a validated
 * integer from a bounded range or a single-quote-escaped literal. The escaping
 * is the only reason user text cannot terminate the argument, so it is covered
 * by tests.
 */
internal object InputCommands {

    /** Raised when an argument fails validation. [errorCode] mirrors the wire contract. */
    class Invalid(val errorCode: String, message: String) : Exception(message)

    private fun bad(message: String): Nothing = throw Invalid("bad_args", message)

    fun coordinate(args: Map<String, String>, key: String): Int {
        val raw = args[key]?.trim().orEmpty()
        if (raw.isEmpty()) bad("missing or invalid " + key)
        val value = raw.toIntOrNull() ?: bad("missing or invalid " + key)
        if (value < 0 || value > MAX_INPUT_COORDINATE) bad(key + " is out of range")
        return value
    }

    fun durationMs(args: Map<String, String>, fallback: Int, min: Int, max: Int): Int {
        val raw = args["duration_ms"]?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        val value = raw.toIntOrNull() ?: bad("invalid duration_ms")
        return value.coerceIn(min, max)
    }

    /** Wraps [value] so sh treats it as one literal argument. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun tap(args: Map<String, String>): String =
        "input tap " + coordinate(args, "x") + " " + coordinate(args, "y")

    fun longPress(args: Map<String, String>): String {
        val x = coordinate(args, "x")
        val y = coordinate(args, "y")
        val duration = durationMs(args, 500, 100, 10_000)
        return "input swipe " + x + " " + y + " " + x + " " + y + " " + duration
    }

    fun swipe(args: Map<String, String>): String {
        val x1 = coordinate(args, "x1")
        val y1 = coordinate(args, "y1")
        val x2 = coordinate(args, "x2")
        val y2 = coordinate(args, "y2")
        val duration = durationMs(args, 300, 50, 10_000)
        return "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration
    }

    /** [width] and [height] come from the device; both are validated by the caller. */
    fun scroll(args: Map<String, String>, width: Int, height: Int): String {
        val direction = args["direction"]?.trim()?.lowercase().orEmpty().ifEmpty { "down" }
        val duration = durationMs(args, 300, 50, 10_000)
        val cx = width / 2
        val cy = height / 2
        val dx = width / 4
        val dy = height / 4
        val coordinates = when (direction) {
            "down" -> intArrayOf(cx, cy + dy, cx, cy - dy)
            "up" -> intArrayOf(cx, cy - dy, cx, cy + dy)
            "right" -> intArrayOf(cx - dx, cy, cx + dx, cy)
            "left" -> intArrayOf(cx + dx, cy, cx - dx, cy)
            else -> bad("direction must be up, down, left or right")
        }
        return "input swipe " + coordinates.joinToString(" ") + " " + duration
    }

    fun inputText(args: Map<String, String>): String {
        val text = args["text"].orEmpty()
        if (text.isEmpty() || text.length > MAX_INPUT_TEXT_LENGTH) {
            bad("text must contain 1 to " + MAX_INPUT_TEXT_LENGTH + " characters")
        }
        if (text.any { it == '\n' || it == '\r' || it.code < 0x20 }) {
            bad("text must not contain control characters")
        }
        return "input text " + quote(text)
    }

    fun keyEvent(args: Map<String, String>): String {
        val raw = args["key_code"]?.trim().orEmpty()
        val code = raw.toIntOrNull() ?: bad("missing or invalid key_code")
        if (code !in ALLOWED_KEY_CODES) bad("key_code is not in the allowed set")
        return "input keyevent " + code
    }
}
