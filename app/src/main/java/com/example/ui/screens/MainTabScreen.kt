package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ConnectionPhase
import com.example.ui.BalancerDashboardUiState
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanDim
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonEmeraldDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun MainTabScreen(
    uiState: BalancerDashboardUiState,
    onSubscriptionUrlChange: (String) -> Unit,
    onRefreshSubscription: () -> Unit,
    onToggleConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val isConnectedHealthy = uiState.connectionPhase == ConnectionPhase.CONNECTED && uiState.isTunnelConnected
    val isDegraded = uiState.connectionPhase == ConnectionPhase.DEGRADED && uiState.isTunnelConnected
    val isBusy = uiState.isBusyTransitioning || uiState.isImportingSubscription
    val isError = uiState.connectionPhase == ConnectionPhase.ERROR

    val statusColor by animateColorAsState(
        targetValue = when {
            isConnectedHealthy -> NeonEmerald
            isDegraded -> AmberPulse
            isBusy -> AmberPulse
            isError -> CrimsonAlert
            else -> TextSecondary
        },
        animationSpec = tween(350),
        label = "status_color"
    )

    val buttonBackgroundColor by animateColorAsState(
        targetValue = when {
            isConnectedHealthy -> NeonEmerald
            isDegraded -> CyberSurfaceElevated
            isBusy -> CyberSurfaceElevated
            else -> CyberSurfaceElevated
        },
        animationSpec = tween(400),
        label = "connect_btn_bg"
    )

    val buttonBorderColor by animateColorAsState(
        targetValue = when {
            isConnectedHealthy -> NeonEmerald
            isDegraded -> AmberPulse
            isBusy -> AmberPulse
            isError -> CrimsonAlert
            else -> ElectricCyan.copy(alpha = 0.65f)
        },
        animationSpec = tween(400),
        label = "connect_btn_border"
    )

    val buttonContentColor by animateColorAsState(
        targetValue = when {
            isConnectedHealthy -> Color(0xFF041F12)
            isDegraded -> AmberPulse
            isBusy -> AmberPulse
            else -> TextPrimary
        },
        animationSpec = tween(350),
        label = "connect_btn_content"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .testTag("main_tab_screen"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Prominent V2Tab Branding & Developer Credit
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                ),
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("v2tab_brand_title")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_author_credit),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp
                ),
                color = ElectricCyan,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("v2tab_author_subtitle")
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 2. Subscription URL Input Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Subscription URL",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (uiState.totalImportedCount > 0) {
                            Text(
                                text = "${uiState.totalImportedCount} nodes saved locally",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonEmerald
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = uiState.subscriptionUrlInput,
                        onValueChange = onSubscriptionUrlChange,
                        placeholder = {
                            Text(
                                text = "https://...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = CyberBackground,
                            unfocusedContainerColor = CyberBackground,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        trailingIcon = {
                            FilledTonalIconButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clipText.isNotEmpty()) {
                                        onSubscriptionUrlChange(clipText)
                                    }
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = CyberSurfaceElevated,
                                    contentColor = ElectricCyan
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("paste_subscription_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste Subscription URL",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("subscription_url_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.savedSubscriptionUrl.isNotBlank()) {
                                "Auto-syncs & balances all healthy nodes"
                            } else {
                                "Paste URL and tap Connect or Refresh"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = onRefreshSubscription,
                            enabled = !uiState.isImportingSubscription && uiState.subscriptionUrlInput.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyanDim,
                                contentColor = ElectricCyan,
                                disabledContainerColor = CyberSurfaceElevated,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier.testTag("refresh_subscription_button")
                        ) {
                            if (uiState.isImportingSubscription) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = ElectricCyan
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Subscription",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.totalImportedCount > 0) "Refresh" else "Import",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // 3. Prominent Connect / Disconnect Button (Turns Green ONLY when Internet is verified & working)
            val pulseTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isConnectedHealthy || isDegraded || isBusy) 1.05f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(184.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    when {
                                        isConnectedHealthy -> NeonEmerald.copy(alpha = 0.28f)
                                        isDegraded || isBusy -> AmberPulse.copy(alpha = 0.20f)
                                        else -> ElectricCyan.copy(alpha = 0.10f)
                                    },
                                    Color.Transparent
                                )
                            )
                        )
                )

                Surface(
                    shape = CircleShape,
                    color = buttonBackgroundColor,
                    shadowElevation = if (isConnectedHealthy) 16.dp else 6.dp,
                    modifier = Modifier
                        .size(154.dp)
                        .clip(CircleShape)
                        .border(3.dp, buttonBorderColor, CircleShape)
                        .clickable(enabled = !isBusy) { onToggleConnect() }
                        .testTag("connect_toggle_button")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(38.dp),
                                strokeWidth = 3.dp,
                                color = AmberPulse
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (uiState.isTunnelConnected) "Disconnect" else "Connect",
                                tint = buttonContentColor,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                isConnectedHealthy -> "CONNECTED"
                                isDegraded -> "DEGRADED"
                                isBusy -> "WORKING..."
                                else -> "CONNECT"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = buttonContentColor
                        )
                        if (uiState.isTunnelConnected) {
                            Text(
                                text = "Tap to Disconnect",
                                style = MaterialTheme.typography.labelSmall,
                                color = buttonContentColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Clear Connection Status Indicator & Compact Healthy Nodes Pill
            Surface(
                shape = RoundedCornerShape(50),
                color = when {
                    isConnectedHealthy -> NeonEmeraldDim
                    isDegraded -> AmberPulse.copy(alpha = 0.15f)
                    isBusy -> CyberSurfaceElevated
                    isError -> CrimsonAlert.copy(alpha = 0.16f)
                    else -> CyberSurface
                },
                modifier = Modifier
                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .testTag("connection_status_indicator")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            isConnectedHealthy -> Icons.Default.CheckCircle
                            isDegraded -> Icons.Default.WarningAmber
                            isBusy -> Icons.Default.Sync
                            isError -> Icons.Default.ErrorOutline
                            else -> Icons.Default.Hub
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.connectionPhase.displayLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = uiState.connectionDetailMessage,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Compact Healthy Nodes Indicator
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CyberSurface,
                modifier = Modifier
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .testTag("healthy_nodes_indicator")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.healthyNodeCount > 0) NeonEmerald else TextMuted
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Healthy Nodes: ${uiState.healthyNodeCount} / ${uiState.totalImportedCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (uiState.observatoryHttpLatencyMs > 0L && uiState.isTunnelConnected) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "• ${uiState.observatoryHttpLatencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Real-Time Data Usage & Speed Statistics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryMetricCard(
                    title = "Download Speed",
                    value = formatTransferSpeed(uiState.aggregateDownloadBps),
                    icon = Icons.Default.ArrowDownward,
                    accentColor = NeonEmerald,
                    testTag = "stat_download_speed",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCard(
                    title = "Upload Speed",
                    value = formatTransferSpeed(uiState.aggregateUploadBps),
                    icon = Icons.Default.ArrowUpward,
                    accentColor = ElectricCyan,
                    testTag = "stat_upload_speed",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryMetricCard(
                    title = "Total Downloaded",
                    value = formatByteCount(uiState.sessionDownloadBytes),
                    icon = Icons.Default.ArrowDownward,
                    accentColor = NeonEmerald.copy(alpha = 0.85f),
                    testTag = "stat_total_downloaded",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCard(
                    title = "Total Uploaded",
                    value = formatByteCount(uiState.sessionUploadBytes),
                    icon = Icons.Default.ArrowUpward,
                    accentColor = ElectricCyan.copy(alpha = 0.85f),
                    testTag = "stat_total_uploaded",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Combined Session Data Usage Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
                    .testTag("stat_combined_data")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElectricCyanDim),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DataUsage,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Combined Session Data",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = if (uiState.isTunnelConnected) {
                                    "Session Active • ${formatDuration(uiState.sessionUptimeSeconds)}"
                                } else {
                                    "Total session transfer"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = formatByteCount(uiState.totalSessionBytes),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        modifier = modifier
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun formatTransferSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    return when {
        bytesPerSec >= 1_048_576L ->
            String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024L ->
            String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}

fun formatByteCount(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes >= 1_073_741_824L ->
            String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L ->
            String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1_024L ->
            String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
