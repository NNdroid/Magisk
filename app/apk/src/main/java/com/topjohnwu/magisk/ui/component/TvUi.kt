package com.topjohnwu.magisk.ui.component

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

fun Context.isTelevisionDevice(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@Composable
fun isTelevision(): Boolean = LocalContext.current.isTelevisionDevice()

@Composable
fun Modifier.tvFocusFrame(
    enabled: Boolean = isTelevision(),
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier {
    if (!enabled) return this

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.035f else 1f,
        label = "tvFocusScale",
    )
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = if (focused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
    } else {
        Color.Transparent
    }

    return this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(backgroundColor, shape)
        .border(2.dp, borderColor, shape)
}
