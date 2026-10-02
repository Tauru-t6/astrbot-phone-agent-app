package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.R

/** Wallpaper-aligned frosted surface. Only the background is softened. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    alpha: Float = .62f,
    borderWidth: Dp = 1.dp,
    elevation: Dp = 4.dp,
    blur: GlassCardBlur = GlassCardBlur.Light,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    val photo = LocalFrostedWallpaper.current ?: ImageBitmap.imageResource(
        if (backdrop.dark) R.drawable.water_night_frost else R.drawable.water_day_frost,
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    val tint = when {
        backdrop.dark -> Color(0xFF1C303C)
        backdrop.mood == BackdropMood.Paper -> Color(0xFFFFFCF1)
        else -> Color(0xFFF0FBFF)
    }
    val opacity = (alpha + if (blur == GlassCardBlur.Heavy) .12f else 0f).coerceIn(0f, 1f)
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Color(0xFF14394B), spotColor = Color(0xFF14394B).copy(alpha = .18f))
            .clip(shape)
            .onGloballyPositioned { origin = it.positionInRoot() - backdrop.origin }
            .border(borderWidth, Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (backdrop.dark) .20f else .86f),
                Color.White.copy(alpha = if (backdrop.dark) .06f else .30f),
            )), shape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (blur != GlassCardBlur.None) drawBackdrop(photo, backdrop, origin)
            drawRect(Brush.verticalGradient(listOf(
                tint.copy(alpha = (opacity + .09f).coerceAtMost(1f)),
                tint.copy(alpha = opacity),
            )))
        }
        content()
    }
}

enum class GlassCardBlur { None, Light, Heavy }
