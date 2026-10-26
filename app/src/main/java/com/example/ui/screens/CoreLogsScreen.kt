package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.CoreLogEntry
import com.example.data.LogLevel
import com.example.data.NodeHealthStatus
import com.example.data.NodeTrafficSnapshot
import com.example.data.ProbeFailureReason
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun CoreLogsScreen(
    nodeSnapshots: List<NodeTrafficSnapshot>,
    logs: List<CoreLogEntry>,
    isProbingAll: Boolean,
    onRetestAllNodes: () -> Unit,
    onClearLogs: () -> Unit,
    onNavigateBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBackHome)
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("core_logs_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Balancer Diagnostics & Node Health",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Multi-observation health checks, hysteresis weights & auto-recovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = onRetestAllNodes,
                    enabled = !isProbingAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("retest_nodes_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retest All Nodes",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isProbingAll) "Testing..." else "Retest",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val allText = logs.joinToString("\n") {
                            val repeatSuffix = if (it.repeatCount > 1) " (×${it.repeatCount})" else ""
                            "${it.timestamp} [${it.level.name}] ${it.tag}: ${it.message}$repeatSuffix"
                        }
                        clipboard.setText(AnnotatedString(allText))
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Logs",
                        modifier = Modifier.size(16.dp)
                    )
                }
                OutlinedButton(
                    onClick = onClearLogs,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Logs",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (nodeSnapshots.isNotEmpty()) {
                item(key = "node_matrix_header") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CyberSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Imported Nodes (${nodeSnapshots.count { it.isHealthy }}/${nodeSnapshots.size} Active)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Hysteresis: 3-Strike Exclusion",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }

                            nodeSnapshots.forEach { node ->
                                NodeDiagnosticRow(node = node)
                            }
                        }
                    }
                }
            }

            item(key = "event_stream_header") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Diagnostic Event Stream (${logs.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            items(items = logs, key = { it.id }) { entry ->
                val levelColor = when (entry.level) {
                    LogLevel.INFO -> NeonEmerald
                    LogLevel.HEALTH -> ElectricCyan
                    LogLevel.WEIGHTS -> ElectricCyan
                    LogLevel.BALANCER -> ElectricCyan
                    LogLevel.RECOVERY -> NeonEmerald
                    LogLevel.FAILOVER -> AmberPulse
                    LogLevel.DNS -> AmberPulse
                    LogLevel.WARN -> AmberPulse
                    LogLevel.ERROR -> CrimsonAlert
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberSurface.copy(alpha = 0.85f))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = entry.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "[${entry.level.name}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = levelColor,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = entry.tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (entry.repeatCount > 1) {
                            Text(
                                text = "×${entry.repeatCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberPulse,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeDiagnosticRow(node: NodeTrafficSnapshot) {
    val badgeColor = when (node.healthStatus) {
        NodeHealthStatus.HEALTHY -> NeonEmerald
        NodeHealthStatus.DEGRADED -> AmberPulse
        NodeHealthStatus.UNHEALTHY -> CrimsonAlert
        NodeHealthStatus.UNTESTED -> TextMuted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CyberSurfaceElevated.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = node.remarks,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        node.latencyMs > 0L -> "${node.latencyMs} ms"
                        node.emaLatencyMs > 0L && node.healthStatus == NodeHealthStatus.DEGRADED ->
                            "~${node.emaLatencyMs} ms"
                        node.latencyMs == -2L -> "Offline"
                        else -> "Untested"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "W:${node.weight}/10",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(Locale.US, "%.0f%%", node.sharePercentage),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusSubtext = buildString {
                append(node.healthStatus.label)
                if (node.consecutiveFailures > 0) {
                    append(" • Fail streak: ${node.consecutiveFailures}/3 (${node.failureReason.diagnosticLabel})")
                } else if (node.emaLatencyMs > 0L) {
                    append(" • EMA: ${node.emaLatencyMs}ms")
                }
                append(" • OK:${node.successCount} / Fail:${node.failureCount}")
            }
            Text(
                text = statusSubtext,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = { (node.sharePercentage / 100f).coerceIn(0f, 1f) },
            color = badgeColor,
            trackColor = CyberBackground,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    }
}
