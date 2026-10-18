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
import android.widget.Button
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

    /** Last failure reason, shown in the autonomy sheet so it is not silent. */
    @Volatile
    var lastError: String = ""
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

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F2202A38"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(10).toFloat()
        }

        card.addView(TextView(context).apply {
            text = "有一件事要你点头"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(context).apply {
            text = "${approval.actions.joinToString("、") { ApprovalLabels.action(it) }} · ${secondsLeft} 秒内有效"
            setTextColor(Color.parseColor("#C9D3E4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val allow = Button(context).apply {
            text = "允许"
            setOnClickListener {
                dismiss()
                onApprove()
            }
        }
        val reject = Button(context).apply {
            text = "拒绝"
            setOnClickListener {
                dismiss()
                onReject()
            }
        }
        row.addView(allow)
        row.addView(reject)
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
        return runCatching { windowManager.addView(card, params) }.fold(
            onSuccess = {
                overlay = card
                shownApproval = approval.approvalId
                lastError = ""
                true
            },
            onFailure = { error ->
                lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
                false
            },
        )
    }

    fun dismiss() {
        val view = overlay ?: return
        runCatching {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }
        overlay = null
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
