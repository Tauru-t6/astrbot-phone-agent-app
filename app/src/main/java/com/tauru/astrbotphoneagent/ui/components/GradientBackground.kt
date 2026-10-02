package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.tauru.astrbotphoneagent.ui.theme.GradientBottom
import com.tauru.astrbotphoneagent.ui.theme.GradientMid
import com.tauru.astrbotphoneagent.ui.theme.GradientTop

/**
 * App-wide background: soft pastel gradient, glass cards float on top.
 * Use this as the root container of every screen.
 */
@Composable
fun GradientBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientTop, GradientMid, GradientBottom),
                ),
            ),
    ) {
        content()
    }
}
