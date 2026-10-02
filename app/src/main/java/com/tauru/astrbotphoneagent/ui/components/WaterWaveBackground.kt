package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tauru.astrbotphoneagent.R
import kotlin.math.max
import kotlin.math.roundToInt

enum class BackdropMood { Water, Mist, Paper }

internal data class GlassBackdrop(
    val mood: BackdropMood = BackdropMood.Water,
    val dark: Boolean = false,
    val size: IntSize = IntSize.Zero,
    val origin: Offset = Offset.Zero,
)
internal val LocalGlassBackdrop = staticCompositionLocalOf { GlassBackdrop() }
internal val LocalFrostedWallpaper = staticCompositionLocalOf<ImageBitmap?> { null }

/** One stationary photograph behind all pages, including the floating navigation. */
@Composable
fun WaterWaveBackground(
    mood: BackdropMood = BackdropMood.Water,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = GlassBackdrop(mood, dark, viewport, origin)
    val photo = ImageBitmap.imageResource(if (dark) R.drawable.water_night else R.drawable.water_day)
    val frosted = ImageBitmap.imageResource(if (dark) R.drawable.water_night_frost else R.drawable.water_day_frost)
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalFrostedWallpaper provides frosted) {
        Box(Modifier.fillMaxSize().onGloballyPositioned {
            viewport = it.size
            origin = it.positionInRoot()
        }) {
            Canvas(Modifier.fillMaxSize()) {
                drawBackdrop(photo, backdrop.copy(size = IntSize(size.width.roundToInt(), size.height.roundToInt())))
            }
            content()
        }
    }
}

/**
 * Crop a shared bitmap in window coordinates. Glass panels sample a pre-blurred
 * copy of this exact photograph, so text stays sharp and Android 8 also works.
 * This is a static wallpaper blur, not a blur of foreground UI or other cards.
 */
internal fun DrawScope.drawBackdrop(
    photo: ImageBitmap,
    backdrop: GlassBackdrop,
    offset: Offset = Offset.Zero,
) {
    if (backdrop.mood == BackdropMood.Paper) {
        drawRect(if (backdrop.dark) Color(0xFF252726) else Color(0xFFECECE5))
        return
    }
    val frame = backdrop.size
    if (frame.width <= 0 || frame.height <= 0) return
    val scale = max(frame.width.toFloat() / photo.width, frame.height.toFloat() / photo.height)
    val width = (photo.width * scale).roundToInt()
    val height = (photo.height * scale).roundToInt()
    drawImage(
        image = photo,
        dstSize = IntSize(width, height),
        dstOffset = IntOffset(
            ((frame.width - width) / 2f - offset.x).roundToInt(),
            ((frame.height - height) / 2f - offset.y).roundToInt(),
        ),
    )
    val haze = if (backdrop.mood == BackdropMood.Mist) .64f else .24f
    drawRect(Brush.verticalGradient(
        0f to (if (backdrop.dark) Color(0xFF10212D) else Color(0xFFEEF7F8)).copy(alpha = .88f),
        .30f to (if (backdrop.dark) Color(0xFF10212D) else Color(0xFFE3F3F6)).copy(alpha = haze),
        1f to (if (backdrop.dark) Color(0xFF10212D) else Color(0xFFD7EDF1)).copy(alpha = haze + .10f),
        startY = -offset.y,
        endY = frame.height.toFloat() - offset.y,
    ))
}
