package com.tauru.astrbotphoneagent.logic

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tauru.astrbotphoneagent.data.TaskApproval

/**
 * Stage 4: show a pending approval over whatever the user is doing.
 *
 * Requires the "display over other apps" permission. Without it the app still
 * shows the same approval inside the autonomy sheet, just less findable.
 */
object ApprovalOverlay {

    private var overlay: LinearLayout? = null
    private var shownApproval = ""
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Last failure reason and how often we tried; both are shown in the sheet. */
    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var attempts: Int = 0
        private set

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Show the card once per approval id; repeated polls do not stack views. */
    fun show(
        context: Context,
        approval: TaskApproval,
        secondsLeft: Int,
        onApprove: () -> Unit,
        onReject: () -> Unit,
    ): Boolean {
        if (!canDraw(context)) {
            lastError = "没有悬浮窗权限"
            return false
        }
        if (shownApproval == approval.approvalId && overlay != null) return true
        dismiss()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            lastError = "无法取得 WindowManager"
            return false
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // A small accent bar and a self-drawn action pair: the default Material
        // Button looked like a system dialog glued onto a dark card.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F2182233"))
                setStroke(dp(1), Color.parseColor("#24FFFFFF"))
            }
            elevation = dp(14).toFloat()
        }

        val accent = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(3)).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#7C9BFF"))
            }
        }
        card.addView(accent)

        card.addView(TextView(context).apply {
            text = "有一件事要你点头"
            setTextColor(Color.parseColor("#F2F6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(context).apply {
            text = "${approval.actions.joinToString("、") { ApprovalLabels.action(it) }} · ${secondsLeft} 秒内有效"
            setTextColor(Color.parseColor("#9FB0C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(16))
        })

        fun actionButton(label: String, fill: Int, stroke: Int, textColor: Int, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(fill)
                    if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
                }
                isClickable = true
                setOnClickListener { onClick() }
            }

        val allow = actionButton(
            "允许", Color.parseColor("#4A9CC0"), Color.TRANSPARENT, Color.WHITE,
        ) { dismiss(); onApprove() }
        val reject = actionButton(
            "拒绝", Color.TRANSPARENT, Color.parseColor("#33FFFFFF"), Color.parseColor("#C9D3E4"),
        ) { dismiss(); onReject() }

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(allow, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
        row.addView(reject, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = dp(72)
            width = if (context.resources.configuration.screenWidthDp < 400) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else {
                dp(360)
            }
            horizontalMargin = 0.08f
        }
        attempts += 1
        // WindowManager only accepts a view from a thread with a Looper, and the
        // caller is a background coroutine, so the add has to hop to the main
        // thread. Doing it inline threw and the exception was swallowed.
        mainHandler.post {
            try {
                // Start slightly above and transparent, then settle down: an
                // instant appear reads like a slide in a presentation.
                card.alpha = 0f
                card.translationY = -dp(28).toFloat()
                windowManager.addView(card, params)
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                overlay = card
                shownApproval = approval.approvalId
                lastError = ""
            } catch (error: Throwable) {
                lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
            }
        }
        return true
    }

    fun dismiss() {
        val view = overlay ?: return
        overlay = null
        val lift = (16 * view.resources.displayMetrics.density).toInt().toFloat()
        // Animate out before removing so tapping a button does not snap.
        view.animate()
            .alpha(0f)
            .translationY(-lift)
            .setDuration(140)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                runCatching {
                    (view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
                }
            }
            .start()
    }
}

/** Shared action labels for the overlay and the sheet. */
object ApprovalLabels {
    fun action(action: String): String = when (action) {
        "open_app" -> "打开应用"
        "close_app" -> "关闭应用"
        "suspend_app" -> "暂停应用"
        "unsuspend_app" -> "恢复应用"
        "home" -> "回到桌面"
        "back" -> "返回"
        "lock_screen" -> "锁屏"
        "tap" -> "点击"
        "input_text" -> "输入文字"
        else -> action
    }
}
