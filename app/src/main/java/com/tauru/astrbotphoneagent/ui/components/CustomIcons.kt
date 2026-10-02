package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 手绘简约图标集
 *
 * 设计原则:
 * - 极简几何形状
 * - 圆润的线条 (StrokeCap.Round)
 * - 统一的线宽 (2dp)
 * - 柔和的视觉语言
 */

// 屏幕使用图标 - 简化的手机轮廓
@Composable
fun ScreenTimeIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // 手机外框 (圆角矩形)
        drawRoundRect(
            color = tint,
            topLeft = Offset(padding, padding),
            size = androidx.compose.ui.geometry.Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.15f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 屏幕内容 (小矩形代表内容)
        val contentPadding = width * 0.2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(padding + contentPadding, padding + contentPadding),
            size = androidx.compose.ui.geometry.Size(width - contentPadding * 2, height * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.08f),
            style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
        )
    }
}

// 电池图标 - 简化的电池轮廓
@Composable
fun BatteryIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
    level: Float = 0.7f, // 0.0 to 1.0
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // 电池主体
        val batteryWidth = width * 0.75f
        val batteryHeight = height * 0.6f
        val batteryX = padding + (width - batteryWidth) / 2
        val batteryY = padding + (height - batteryHeight) / 2

        drawRoundRect(
            color = tint,
            topLeft = Offset(batteryX, batteryY),
            size = androidx.compose.ui.geometry.Size(batteryWidth, batteryHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(batteryHeight * 0.15f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 电池头 (小凸起)
        val headWidth = batteryWidth * 0.15f
        val headHeight = batteryHeight * 0.35f
        val headX = batteryX + batteryWidth
        val headY = batteryY + (batteryHeight - headHeight) / 2

        drawRoundRect(
            color = tint,
            topLeft = Offset(headX, headY),
            size = androidx.compose.ui.geometry.Size(headWidth, headHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(headWidth * 0.3f),
        )

        // 电量指示 (填充)
        val fillPadding = strokeWidth * 1.5f
        val fillWidth = (batteryWidth - fillPadding * 2) * level
        if (fillWidth > 0) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(batteryX + fillPadding, batteryY + fillPadding),
                size = androidx.compose.ui.geometry.Size(fillWidth, batteryHeight - fillPadding * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(batteryHeight * 0.1f),
            )
        }
    }
}

// 日期图标 - 简化的日历
@Composable
fun CalendarIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // 日历主体
        drawRoundRect(
            color = tint,
            topLeft = Offset(padding, padding + height * 0.15f),
            size = androidx.compose.ui.geometry.Size(width, height * 0.85f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.12f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 顶部装订环
        val ringWidth = width * 0.12f
        val ringSpacing = width * 0.3f
        drawCircle(
            color = tint,
            radius = ringWidth / 2,
            center = Offset(padding + ringSpacing, padding + height * 0.15f),
        )
        drawCircle(
            color = tint,
            radius = ringWidth / 2,
            center = Offset(padding + width - ringSpacing, padding + height * 0.15f),
        )

        // 日期分隔线
        drawLine(
            color = tint,
            start = Offset(padding + width * 0.15f, padding + height * 0.4f),
            end = Offset(padding + width * 0.85f, padding + height * 0.4f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

// 心率/状态图标 - 简化的心形
@Composable
fun HeartIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        val path = Path().apply {
            val centerX = padding + width / 2
            val topY = padding + height * 0.3f

            // 左半心
            moveTo(centerX, topY + height * 0.25f)
            cubicTo(
                centerX - width * 0.25f, topY,
                centerX - width * 0.45f, topY + height * 0.15f,
                centerX - width * 0.45f, topY + height * 0.35f
            )
            cubicTo(
                centerX - width * 0.45f, topY + height * 0.5f,
                centerX, topY + height * 0.65f,
                centerX, padding + height
            )

            // 右半心
            moveTo(centerX, topY + height * 0.25f)
            cubicTo(
                centerX + width * 0.25f, topY,
                centerX + width * 0.45f, topY + height * 0.15f,
                centerX + width * 0.45f, topY + height * 0.35f
            )
            cubicTo(
                centerX + width * 0.45f, topY + height * 0.5f,
                centerX, topY + height * 0.65f,
                centerX, padding + height
            )
        }

        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// 专注模式图标 - 简化的莲花/打坐姿势
@Composable
fun FocusIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2
        val centerX = padding + width / 2
        val centerY = padding + height / 2

        // 中心圆
        drawCircle(
            color = tint,
            radius = width * 0.15f,
            center = Offset(centerX, centerY),
        )

        // 外圈 (简化的莲花花瓣)
        val petalCount = 6
        val outerRadius = width * 0.4f
        for (i in 0 until petalCount) {
            val angle = (i * 60f - 90f) * Math.PI / 180f
            val x = centerX + outerRadius * kotlin.math.cos(angle).toFloat()
            val y = centerY + outerRadius * kotlin.math.sin(angle).toFloat()

            drawCircle(
                color = tint,
                radius = width * 0.08f,
                center = Offset(x, y),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

// 截屏图标 - 简化的相机
@Composable
fun ScreenshotIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // 相机主体
        drawRoundRect(
            color = tint,
            topLeft = Offset(padding, padding + height * 0.2f),
            size = androidx.compose.ui.geometry.Size(width, height * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.12f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 镜头
        drawCircle(
            color = tint,
            radius = width * 0.22f,
            center = Offset(padding + width / 2, padding + height * 0.55f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 取景器凸起
        drawRoundRect(
            color = tint,
            topLeft = Offset(padding + width * 0.3f, padding),
            size = androidx.compose.ui.geometry.Size(width * 0.4f, height * 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.08f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// 夜间模式图标 - 简化的月亮
@Composable
fun NightIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2
        val centerX = padding + width / 2
        val centerY = padding + height / 2
        val radius = kotlin.math.min(width, height) * 0.4f

        val path = Path().apply {
            // 新月形状
            addArc(
                oval = Rect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius
                ),
                startAngleDegrees = 45f,
                sweepAngleDegrees = 270f
            )

            addArc(
                oval = Rect(
                    centerX - radius * 0.6f,
                    centerY - radius * 0.6f,
                    centerX + radius * 0.6f,
                    centerY + radius * 0.6f
                ),
                startAngleDegrees = 225f,
                sweepAngleDegrees = -270f
            )
        }

        drawPath(
            path = path,
            color = tint,
        )
    }
}

// 定位图标 - 简化的定位针
@Composable
fun LocationIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2
        val centerX = padding + width / 2
        val topY = padding

        // 定位针主体 (水滴形)
        val path = Path().apply {
            moveTo(centerX, topY)
            cubicTo(
                centerX + width * 0.35f, topY + height * 0.2f,
                centerX + width * 0.35f, topY + height * 0.5f,
                centerX, topY + height * 0.85f
            )
            cubicTo(
                centerX - width * 0.35f, topY + height * 0.5f,
                centerX - width * 0.35f, topY + height * 0.2f,
                centerX, topY
            )
        }

        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 中心点
        drawCircle(
            color = tint,
            radius = width * 0.12f,
            center = Offset(centerX, topY + height * 0.3f),
        )
    }
}

// 锁定图标 - 简化的挂锁
@Composable
fun LockIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val padding = strokeWidth * 2
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // 锁体
        val lockBodyWidth = width * 0.7f
        val lockBodyHeight = height * 0.5f
        val lockBodyX = padding + (width - lockBodyWidth) / 2
        val lockBodyY = padding + height - lockBodyHeight

        drawRoundRect(
            color = tint,
            topLeft = Offset(lockBodyX, lockBodyY),
            size = androidx.compose.ui.geometry.Size(lockBodyWidth, lockBodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(lockBodyWidth * 0.15f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 锁环
        val shackleWidth = width * 0.45f
        val shackleHeight = height * 0.4f
        val shackleX = padding + (width - shackleWidth) / 2
        val shackleY = padding

        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleX, shackleY),
            size = androidx.compose.ui.geometry.Size(shackleWidth, shackleHeight * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}
