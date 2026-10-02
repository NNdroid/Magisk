package com.topjohnwu.magisk.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun Modifier.tvFocusFrame(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier {
    if (!enabled) return this

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.065f else 1f,
        label = "tvFocusScale",
    )
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = if (focused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
    } else {
        Color.Transparent
    }

    return this
        .onFocusChanged { focused = it.hasFocus }
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(backgroundColor, shape)
        .border(if (focused) 3.dp else 2.dp, borderColor, shape)
}
