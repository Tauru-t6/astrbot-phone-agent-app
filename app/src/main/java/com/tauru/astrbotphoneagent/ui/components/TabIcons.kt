package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 底部 5 个 tab 的手绘风图标。
 * 用 Canvas 直接画 —— 描边、点画、带曲率，不用 Material Icons。
 *
 * 每个图标接受 size + tint。线宽统一 1.6dp，描边统一。
 */

@Composable
fun HandDrawnChatIcon(size: Dp = 24.dp, tint: Color = Color.Unspecified) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 圆形气泡
        drawCircle(
            color = tint,
            center = Offset(cx, cy - s * 0.08f),
            radius = s * 0.32f,
            style = Stroke(width = stroke),
        )
        // 尾巴
        val tail = Path().apply {
            moveTo(cx - s * 0.10f, cy + s * 0.18f)
            lineTo(cx - s * 0.20f, cy + s * 0.38f)
            lineTo(cx + s * 0.04f, cy + s * 0.20f)
        }
        drawPath(tail, tint, style = Stroke(width = stroke))
        // 三个点
        listOf(-0.13f, 0f, 0.13f).forEach { dx ->
            drawCircle(
                color = tint,
                center = Offset(cx + dx * s, cy - s * 0.08f),
                radius = s * 0.04f,
            )
        }
    }
}

@Composable
fun HandDrawnExploreIcon(size: Dp = 24.dp, tint: Color = Color.Unspecified) {
    // 望远镜
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 主体椭圆（横放）
        val w = s * 0.55f
        val h = s * 0.22f
        drawOval(
            color = tint,
            topLeft = Offset(cx - w / 2f, cy - h / 2f),
            size = Size(w, h),
            style = Stroke(width = stroke),
        )
        // 短接目镜（右）
        drawLine(
            color = tint,
            start = Offset(cx + w / 2f, cy - h / 2f),
            end = Offset(cx + w / 2f + s * 0.10f, cy - h / 2f - s * 0.05f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(cx + w / 2f, cy + h / 2f),
            end = Offset(cx + w / 2f + s * 0.10f, cy + h / 2f + s * 0.05f),
            strokeWidth = stroke,
        )
        // 中心亮点（看到的星）
        drawCircle(
            color = tint,
            center = Offset(cx + s * 0.05f, cy),
            radius = s * 0.05f,
        )
    }
}

@Composable
fun HandDrawnLifeIcon(size: Dp = 24.dp, tint: Color = Color.Unspecified) {
    // 一弯月 + 几颗点（夜里小窝）
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 主体月牙（两个圆相减，用两条弧线）
        // 外弧
        val moonPath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    offset = Offset(cx - s * 0.30f, cy - s * 0.30f),
                    size = Size(s * 0.60f, s * 0.60f),
                )
            )
            // 切除（同方向 = 偶奇规则变奇偶 = 切成月牙）
            addOval(
                androidx.compose.ui.geometry.Rect(
                    offset = Offset(cx - s * 0.10f, cy - s * 0.30f),
                    size = Size(s * 0.55f, s * 0.60f),
                )
            )
        }
        drawPath(moonPath, tint, style = Stroke(width = stroke))
        // 几颗星（小点）
        drawCircle(color = tint, center = Offset(cx + s * 0.34f, cy - s * 0.10f), radius = s * 0.04f)
        drawCircle(color = tint, center = Offset(cx + s * 0.40f, cy + s * 0.18f), radius = s * 0.03f)
        drawCircle(color = tint, center = Offset(cx + s * 0.20f, cy + s * 0.34f), radius = s * 0.025f)
    }
}

@Composable
fun HandDrawnMarketIcon(size: Dp = 24.dp, tint: Color = Color.Unspecified) {
    // 一只小市集箱：手绘边框 + 顶部斜棚
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 箱体
        val boxTop = cy - s * 0.18f
        val boxBottom = cy + s * 0.30f
        drawRect(
            color = tint,
            topLeft = Offset(cx - s * 0.32f, boxTop),
            size = Size(s * 0.64f, boxBottom - boxTop),
            style = Stroke(width = stroke),
        )
        // 棚顶（梯形）
        val awning = Path().apply {
            moveTo(cx - s * 0.42f, boxTop)
            lineTo(cx - s * 0.32f, boxTop - s * 0.14f)
            lineTo(cx + s * 0.32f, boxTop - s * 0.14f)
            lineTo(cx + s * 0.42f, boxTop)
            close()
        }
        drawPath(awning, tint, style = Stroke(width = stroke))
        // 棚顶竖纹
        drawLine(color = tint, start = Offset(cx - s * 0.14f, boxTop - s * 0.14f), end = Offset(cx - s * 0.14f, boxTop), strokeWidth = stroke)
        drawLine(color = tint, start = Offset(cx + s * 0.14f, boxTop - s * 0.14f), end = Offset(cx + s * 0.14f, boxTop), strokeWidth = stroke)
    }
}

@Composable
fun HandDrawnNoteIcon(size: Dp = 24.dp, tint: Color = Color.Unspecified) {
    // 一本翻开的笔记本：两页 + 中线 + 顶部夹子
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 外轮廓（轻微圆角矩形）
        val w = s * 0.78f
        val h = s * 0.62f
        val left = cx - w / 2f
        val top = cy - h / 2f
        // 主体
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f, s * 0.04f),
            style = Stroke(width = stroke),
        )
        // 中线（书脊）
        drawLine(
            color = tint,
            start = Offset(cx, top + s * 0.04f),
            end = Offset(cx, top + h - s * 0.04f),
            strokeWidth = stroke,
        )
        // 左页横线
        drawLine(
            color = tint,
            start = Offset(cx - s * 0.30f, cy - s * 0.06f),
            end = Offset(cx - s * 0.06f, cy - s * 0.06f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(cx - s * 0.30f, cy + s * 0.06f),
            end = Offset(cx - s * 0.06f, cy + s * 0.06f),
            strokeWidth = stroke,
        )
        // 右页横线
        drawLine(
            color = tint,
            start = Offset(cx + s * 0.06f, cy - s * 0.06f),
            end = Offset(cx + s * 0.30f, cy - s * 0.06f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(cx + s * 0.06f, cy + s * 0.06f),
            end = Offset(cx + s * 0.30f, cy + s * 0.06f),
            strokeWidth = stroke,
        )
    }
}
