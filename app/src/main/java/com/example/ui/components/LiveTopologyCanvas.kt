package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.NodeTrafficSnapshot
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveBalancerTopologyCard(
    snapshots: List<NodeTrafficSnapshot>,
    isConnected: Boolean,
    strategyTitle: String,
    aggregateDownloadBps: Long,
    aggregateUploadBps: Long,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topology_flow")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "packet_phase"
    )
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hub_pulse"
    )

    val healthyCount = snapshots.count { it.isHealthy }
    val droppedCount = snapshots.count { it.isDropped }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = if (isConnected) {
                        listOf(ElectricCyan.copy(alpha = 0.65f), NeonEmerald.copy(alpha = 0.65f))
                    } else {
                        listOf(BorderSubtle, BorderSubtle)
                    }
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = CyberSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ElectricCyan.copy(alpha = if (isConnected) 0.08f else 0.02f),
                            CyberSurface,
                            CyberBackground
                        )
                    )
                )
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !isConnected -> TextMuted
                                    healthyCount > 0 -> NeonEmerald
                                    else -> CrimsonAlert
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) {
                            "MULTI-OUTBOUND STREAM MATRIX • LIVE"
                        } else {
                            "MULTI-OUTBOUND TOPOLOGY • STANDBY"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isConnected) ElectricCyan else TextSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (droppedCount > 0) {
                        AmberPulse.copy(alpha = 0.16f)
                    } else {
                        NeonEmerald.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = if (droppedCount > 0) {
                            "$healthyCount Active • $droppedCount Failover"
                        } else {
                            "$healthyCount/${snapshots.size} Nodes Healthy"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (droppedCount > 0) AmberPulse else NeonEmerald,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Topology Canvas + Node Labels
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CyberBackground.copy(alpha = 0.85f))
                    .border(1.dp, BorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            ) {
                val visibleNodes = snapshots.take(6)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Subtle cyber grid lines
                    val gridCols = 8
                    val gridRows = 4
                    for (c in 1 until gridCols) {
                        val x = w * (c.toFloat() / gridCols)
                        drawLine(
                            color = BorderSubtle.copy(alpha = 0.25f),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1f
                        )
                    }
                    for (r in 1 until gridRows) {
                        val y = h * (r.toFloat() / gridRows)
                        drawLine(
                            color = BorderSubtle.copy(alpha = 0.25f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    val clientPos = Offset(w * 0.11f, h * 0.5f)
                    val balancerPos = Offset(w * 0.42f, h * 0.5f)

                    // Draw Client -> Balancer trunk line
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(ElectricCyan, NeonEmerald)
                        ),
                        start = clientPos,
                        end = balancerPos,
                        strokeWidth = if (isConnected) 7f else 3.5f,
                        cap = StrokeCap.Round
                    )

                    // Flowing packets on Client -> Balancer trunk
                    if (isConnected && healthyCount > 0) {
                        for (i in 0..2) {
                            val t = (phase + i / 3f) % 1f
                            val px = clientPos.x + (balancerPos.x - clientPos.x) * t
                            drawCircle(
                                color = Color.White,
                                radius = 5.5f,
                                center = Offset(px, clientPos.y)
                            )
                        }
                    }

                    // Draw curves from Balancer Hub to each Outbound Server Node
                    val count = visibleNodes.size.coerceAtLeast(1)
                    visibleNodes.forEachIndexed { idx, snap ->
                        val fractionY = if (count == 1) {
                            0.5f
                        } else {
                            0.16f + (0.68f * idx.toFloat() / (count - 1).toFloat())
                        }
                        val nodePos = Offset(w * 0.84f, h * fractionY)

                        val path = Path().apply {
                            moveTo(balancerPos.x, balancerPos.y)
                            cubicTo(
                                balancerPos.x + (nodePos.x - balancerPos.x) * 0.45f,
                                balancerPos.y,
                                balancerPos.x + (nodePos.x - balancerPos.x) * 0.55f,
                                nodePos.y,
                                nodePos.x,
                                nodePos.y
                            )
                        }

                        val strokeColor = when {
                            snap.isDropped -> CrimsonAlert.copy(alpha = 0.7f)
                            !isConnected -> ElectricCyan.copy(alpha = 0.45f)
                            else -> NeonEmerald.copy(alpha = 0.85f)
                        }

                        val strokeWidth = if (snap.isDropped) {
                            2.5f
                        } else {
                            (2.5f + (snap.sharePercentage / 100f) * 6.5f).coerceIn(2.5f, 8.5f)
                        }

                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round,
                                pathEffect = if (snap.isDropped) {
                                    PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                } else null
                            )
                        )

                        // Animated packet dots along active healthy server streams
                        if (isConnected && snap.isHealthy && snap.sharePercentage > 0f) {
                            val packetCount = if (snap.sharePercentage >= 35f) 3 else 2
                            for (p in 0 until packetCount) {
                                val t = (phase + (p.toFloat() / packetCount) + idx * 0.17f) % 1f
                                val bezierPoint = evaluateCubicBezier(
                                    p0 = balancerPos,
                                    p1 = Offset(balancerPos.x + (nodePos.x - balancerPos.x) * 0.45f, balancerPos.y),
                                    p2 = Offset(balancerPos.x + (nodePos.x - balancerPos.x) * 0.55f, nodePos.y),
                                    p3 = nodePos,
                                    t = t
                                )
                                drawCircle(
                                    color = ElectricCyan,
                                    radius = 5f,
                                    center = bezierPoint
                                )
                            }
                        }

                        // Draw Outbound Server Node circle
                        drawCircle(
                            color = when {
                                snap.isDropped -> CrimsonAlert.copy(alpha = 0.25f)
                                isConnected -> NeonEmerald.copy(alpha = 0.25f)
                                else -> ElectricCyan.copy(alpha = 0.2f)
                            },
                            radius = 15f,
                            center = nodePos
                        )
                        drawCircle(
                            color = when {
                                snap.isDropped -> CrimsonAlert
                                isConnected -> NeonEmerald
                                else -> ElectricCyan
                            },
                            radius = 7.5f,
                            center = nodePos
                        )
                    }

                    // Draw Client Node
                    drawCircle(
                        color = ElectricCyan.copy(alpha = 0.22f),
                        radius = 20f,
                        center = clientPos
                    )
                    drawCircle(
                        color = ElectricCyan,
                        radius = 9f,
                        center = clientPos
                    )

                    // Draw Center Balancer Hub
                    if (isConnected) {
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.18f),
                            radius = 26f * pulseRadius,
                            center = balancerPos
                        )
                    }
                    drawCircle(
                        color = CyberSurface,
                        radius = 18f,
                        center = balancerPos
                    )
                    drawCircle(
                        color = if (isConnected) NeonEmerald else ElectricCyan,
                        radius = 18f,
                        center = balancerPos,
                        style = Stroke(width = 3.5f)
                    )
                    // Inner prism hexagon
                    val hexPath = Path()
                    for (k in 0..5) {
                        val angle = (k * 60.0) * (PI / 180.0)
                        val hx = balancerPos.x + (8f * cos(angle)).toFloat()
                        val hy = balancerPos.y + (8f * sin(angle)).toFloat()
                        if (k == 0) hexPath.moveTo(hx, hy) else hexPath.lineTo(hx, hy)
                    }
                    hexPath.close()
                    drawPath(
                        path = hexPath,
                        color = if (isConnected) NeonEmerald else ElectricCyan
                    )
                }

                // Overlay Labels for TUN Client, Balancer Hub, and Right-Side Share Badges
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        Text(
                            text = "TUN / SOCKS5",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = strategyTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Per-node share list on the right edge
                    Column(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        visibleNodes.forEach { snap ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CyberSurface.copy(alpha = 0.88f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = snap.remarks.take(14),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (snap.isDropped) CrimsonAlert else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (snap.isDropped) {
                                            "FAILOVER"
                                        } else {
                                            "${snap.sharePercentage.toInt()}%"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (snap.isDropped) CrimsonAlert else NeonEmerald
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer Real-Time Speed & Strategy Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AGGREGATE DOWNLINK",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = formatSpeedBps(aggregateDownloadBps),
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "AGGREGATE UPLINK",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = formatSpeedBps(aggregateUploadBps),
                        style = MaterialTheme.typography.titleMedium,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "FAILOVER PROTECTION",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = if (healthyCount >= 2) "ZERO-DROP ACTIVE" else "SINGLE-NODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (healthyCount >= 2) NeonEmerald else AmberPulse,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun evaluateCubicBezier(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    t: Float
): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t

    val x = uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x
    val y = uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y
    return Offset(x, y)
}

fun formatSpeedBps(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0.0 KB/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB/s", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB/s", mb)
}

fun formatTotalBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
