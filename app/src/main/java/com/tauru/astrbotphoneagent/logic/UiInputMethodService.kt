package com.tauru.astrbotphoneagent.logic

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** No text broadcast receiver: only the app's authorized command path can call this object. */
class UiInputMethodService : InputMethodService() {
    @Volatile private var editorPackage = ""
    @Volatile private var passwordEditor = true
    @Volatile private var editorHint = ""
    @Volatile private var editorSession = 0L

    override fun onCreate() { super.onCreate(); active = this }
    override fun onDestroy() { editorSession = nextSession.incrementAndGet(); if (active === this) active = null; super.onDestroy() }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorSession = nextSession.incrementAndGet()
        editorPackage = attribute?.packageName.orEmpty()
        editorHint = attribute?.hintText?.toString().orEmpty()
        passwordEditor = attribute == null || isPasswordType(attribute.inputType)
    }
    override fun onFinishInput() {
        editorSession = nextSession.incrementAndGet()
        editorPackage = ""; editorHint = ""; passwordEditor = true
        super.onFinishInput()
    }
    override fun onCreateInputView(): View = TextView(this).apply {
        text = "Phone Control：仅输入你已批准任务中的文字"
        textSize = 14f
        setPadding(24, 20, 24, 20)
    }

    companion object {
        @Volatile private var active: UiInputMethodService? = null
        private val nextSession = AtomicLong()
        internal fun isPasswordType(type: Int): Boolean {
            val variation = type and InputType.TYPE_MASK_VARIATION
            val klass = type and InputType.TYPE_MASK_CLASS
            return (klass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
        internal fun readyFor(packageName: String): Boolean = active?.let {
            it.editorPackage == packageName && !it.passwordEditor
        } == true
        internal fun sessionFor(packageName: String): Long? = active?.let {
            it.editorSession.takeIf { _ -> it.editorPackage == packageName && !it.passwordEditor }
        }

        internal fun commitApprovedText(packageName: String, text: String, expectedText: String, expectedSession: Long): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "IME command must run off main thread" }
            val allowed = AtomicBoolean(true)
            val result = CompletableFuture<Boolean>()
            Handler(Looper.getMainLooper()).post {
                if (!allowed.get()) { result.complete(false); return@post }
                val ime = active
                val connection = ime?.currentInputConnection
                if (ime == null || connection == null || ime.editorPackage != packageName || ime.passwordEditor || ime.editorSession != expectedSession) {
                    result.complete(false); return@post
                }
                val ok = runCatching {
                    // Never put the text on a shared clipboard or in an Intent.
                    val before = connection.getTextBeforeCursor(1000, 0) ?: return@runCatching false
                    val selected = connection.getSelectedText(0)?.toString().orEmpty()
                    val after = connection.getTextAfterCursor(1000, 0) ?: return@runCatching false
                    val existing = before.toString() + selected + after
                    // Accessibility may expose an empty editor's search hint
                    // as text. Accept it only when this same editor explicitly
                    // supplied that hint and the live input connection is empty.
                    if (!editorTextMatches(existing, expectedText, ime.editorHint)) return@runCatching false
                    if (!allowed.get() || ime.editorSession != expectedSession || ime.currentInputConnection !== connection) return@runCatching false
                    if (existing.isNotEmpty() && !connection.performContextMenuAction(android.R.id.selectAll)) return@runCatching false
                    if (!allowed.get() || ime.editorSession != expectedSession) return@runCatching false
                    connection.commitText(text, 1)
                }.getOrDefault(false)
                result.complete(ok)
            }
            return try { result.get(5, TimeUnit.SECONDS) }
            catch (interrupted: InterruptedException) { throw interrupted }
            catch (_: Exception) { false }
            finally { allowed.set(false) }
        }
    }
}
