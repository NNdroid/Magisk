package com.topjohnwu.magisk.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.webui.WebUiManager
import android.graphics.Color as AndroidColor

@Composable
internal fun WebUiCard(
    modifier: Modifier = Modifier,
) {
    val state by WebUiManager.state.collectAsStateWithLifecycle()
    val accessUrl = state.accessUrl
    val qr = remember(accessUrl) {
        accessUrl?.let(::createWebUiQr)
    }
    val authText = when (state.authMode) {
        Config.Value.WEBUI_AUTH_NONE -> stringResource(R.string.webui_auth_none)
        Config.Value.WEBUI_AUTH_CUSTOM_TOKEN -> stringResource(R.string.webui_auth_custom)
        else -> stringResource(R.string.webui_auth_random)
    }
    val ipv4Status = if (state.ipv4Listening) {
        stringResource(R.string.webui_listener_on)
    } else {
        stringResource(R.string.webui_listener_off)
    }
    val ipv6Status = if (state.ipv6Listening) {
        stringResource(R.string.webui_listener_on)
    } else {
        stringResource(R.string.webui_listener_off)
    }
    val selfTestText = when (state.localSelfTest) {
        true -> stringResource(R.string.webui_self_test_ok)
        false -> stringResource(R.string.webui_self_test_failed)
        null -> stringResource(R.string.webui_self_test_pending)
    }
    val runningText = if (state.running) {
        stringResource(R.string.webui_running, Config.webUiPort)
    } else {
        stringResource(R.string.webui_stopped)
    }
    val runningColor = if (state.running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val selfTestColor = when (state.localSelfTest) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(58.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(18.dp),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.webui_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(runningText, runningColor)
                        StatusChip(selfTestText, selfTestColor)
                    }
                    Text(
                        text = stringResource(
                            R.string.webui_listener_status,
                            ipv4Status,
                            ipv6Status,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.ipv4Listening || state.ipv6Listening) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = authText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.urls.isNotEmpty()) {
                        state.urls.take(3).forEach { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = stringResource(R.string.webui_scan_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = state.error?.let {
                                stringResource(R.string.webui_error, it)
                            } ?: stringResource(R.string.webui_no_lan_address),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (qr != null) {
                Spacer(Modifier.width(26.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)),
                ) {
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.webui_qr_description),
                        modifier = Modifier
                            .padding(10.dp)
                            .size(150.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun createWebUiQr(value: String) = runCatching {
    val size = 512
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
