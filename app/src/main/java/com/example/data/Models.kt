package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class V2RayProtocol(val displayName: String, val scheme: String, val badgeColorHex: Long) {
    VLESS("VLESS", "vless://", 0xFF00E5FF),
    VMESS("VMess", "vmess://", 0xFF00E676),
    TROJAN("Trojan", "trojan://", 0xFFB388FF),
    SHADOWSOCKS("Shadowsocks", "ss://", 0xFFFFB300),
    SOCKS("SOCKS5", "socks://", 0xFF40C4FF),
    WIREGUARD("WireGuard", "wireguard://", 0xFFFF8A65)
}

enum class ConnectionPhase(val displayLabel: String) {
    DISCONNECTED("Disconnected"),
    IMPORTING("Importing Subscription..."),
    TESTING_NODES("Testing Nodes & Computing Weights..."),
    CONNECTING("Starting Multi-Node Balancer..."),
    VERIFYING("VPN Connected • Verifying Internet..."),
    CONNECTED("Connected • Internet Working"),
    DEGRADED("VPN Connected • Internet Degraded"),
    ERROR("Connection Error")
}

enum class TunnelConnectivityState(val summaryLabel: String) {
    DISCONNECTED("Tunnel Disconnected"),
    VPN_ESTABLISHED("VPN Tunnel Established (Verifying Internet)"),
    INTERNET_WORKING("VPN Connected & Internet Verified"),
    CONNECTION_DEGRADED("VPN Connected • Internet Access Degraded")
}

enum class NodeHealthStatus(val label: String) {
    UNTESTED("Untested"),
    HEALTHY("Healthy"),
    DEGRADED("Degraded"),
    UNHEALTHY("Unhealthy")
}

enum class ProbeFailureReason(val diagnosticLabel: String) {
    NONE("OK"),
    PROXY_TARGET_TIMEOUT("Handshake OK, proxy HTTP timeout"),
    TIMEOUT("Proxy connection timeout"),
    CONNECTION_REFUSED("Connection refused by server"),
    DNS_FAILURE("DNS resolution failed for node host"),
    PROXY_UNREACHABLE("Proxy network unreachable"),
    SIMULATED_DOWN("Simulated node drop")
}

enum class BalancerStrategy(
    val xrayKey: String,
    val title: String,
    val subtitle: String,
    val usesBurstObservatory: Boolean
) {
    LEAST_LOAD(
        xrayKey = "leastLoad",
        title = "Dynamic Weighted Least-Load",
        subtitle = "Distributes concurrent streams across all healthy nodes weighted by latency, throughput & stability",
        usesBurstObservatory = true
    ),
    LEAST_PING(
        xrayKey = "leastPing",
        title = "Lowest Latency (leastPing)",
        subtitle = "Routes streams to the fastest live server via Xray Observatory & fails over immediately on timeout",
        usesBurstObservatory = false
    ),
    ROUND_ROBIN(
        xrayKey = "roundRobin",
        title = "Weighted Round-Robin",
        subtitle = "Cycles new TCP/UDP connections across all healthy servers in the balancer pool using node weights",
        usesBurstObservatory = false
    ),
    RANDOM(
        xrayKey = "random",
        title = "Multi-Path Random",
        subtitle = "Spreads connections across all healthy servers in the balancer group with automatic dead-node exclusion",
        usesBurstObservatory = false
    )
}

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remarks: String,
    val protocol: String = V2RayProtocol.VLESS.name,
    val address: String,
    val port: Int,
    val uuidOrPassword: String,
    val encryption: String = "none",
    val security: String = "tls", // none, tls, reality
    val sni: String = "",
    val host: String = "", // transport host for xhttp/ws
    val alpn: String = "", // e.g. h2,h3,http/1.1
    val fingerPrint: String = "chrome", // fp: chrome, edge, firefox, etc.
    val network: String = "xhttp", // tcp, ws, grpc, h2, xhttp, httpupgrade, kcp
    val pathOrServiceName: String = "",
    val xhttpMode: String = "auto",
    val xhttpExtra: String = "", // extra JSON string
    val headerType: String = "none",
    val flow: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val allowInsecure: Boolean = false,
    val subscriptionGroup: String = "Subscription",
    val inBalancerPool: Boolean = true,
    val weight: Int = 5, // 1..10 dynamic weight for traffic distribution
    val lastLatencyMs: Long = -1L, // -1 = untested, -2 = failed/offline, >0 = healthy ms
    val emaLatencyMs: Long = -1L, // Exponential moving average latency for hysteresis
    val measuredThroughputKbps: Long = 0L, // Estimated or live throughput in Kbps
    val healthStatus: String = NodeHealthStatus.UNTESTED.name,
    val lastFailureReason: String = ProbeFailureReason.NONE.name,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val stabilityScore: Float = 100f, // 0..100 reliability score
    val lastCheckedAt: Long = 0L,
    val lastStatusChangeAt: Long = 0L,
    val isSimulatedDown: Boolean = false,
    val totalBytesUp: Long = 0L,
    val totalBytesDown: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    val protocolEnum: V2RayProtocol
        get() = V2RayProtocol.entries.find { it.name.equals(protocol, ignoreCase = true) }
            ?: V2RayProtocol.VLESS

    val healthStatusEnum: NodeHealthStatus
        get() = NodeHealthStatus.entries.find { it.name.equals(healthStatus, ignoreCase = true) }
            ?: when {
                isSimulatedDown -> NodeHealthStatus.UNHEALTHY
                lastLatencyMs == -2L && consecutiveFailures >= 3 -> NodeHealthStatus.UNHEALTHY
                lastLatencyMs == -2L && consecutiveFailures in 1..2 -> NodeHealthStatus.DEGRADED
                lastLatencyMs in 1..1400 -> NodeHealthStatus.HEALTHY
                lastLatencyMs > 1400 -> NodeHealthStatus.DEGRADED
                else -> NodeHealthStatus.UNTESTED
            }

    val failureReasonEnum: ProbeFailureReason
        get() = ProbeFailureReason.entries.find { it.name.equals(lastFailureReason, ignoreCase = true) }
            ?: ProbeFailureReason.NONE

    val outboundTag: String
        get() = "proxy-balancer-${id}-${remarks.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}"

    val effectiveSni: String
        get() = when {
            sni.isNotBlank() -> sni
            host.isNotBlank() -> host.split(",").first().trim()
            else -> address
        }

    val effectiveLatencyMs: Long
        get() = when {
            emaLatencyMs > 0L -> emaLatencyMs
            lastLatencyMs > 0L -> lastLatencyMs
            else -> -1L
        }

    /**
     * A node participates in active routing if it is HEALTHY or DEGRADED (tolerating transient failures).
     * Only confirmed UNHEALTHY nodes (after consecutive failures) or simulated-down nodes are excluded.
     */
    val isHealthy: Boolean
        get() = !isSimulatedDown &&
            healthStatusEnum != NodeHealthStatus.UNHEALTHY &&
            (lastLatencyMs > 0L || emaLatencyMs > 0L || consecutiveFailures < 3)

    /**
     * Short, redacted server endpoint identifier safe for displaying in diagnostic logs
     * without leaking full private hostnames or credentials.
     */
    val redactedEndpoint: String
        get() {
            val prefix = address.substringBefore(".").take(8)
            return "$prefix***:$port"
        }
}

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remarks: String,
    val domainOrIpPattern: String,
    val targetTag: String, // "balancer", "direct", "block"
    val enabled: Boolean = true,
    val priority: Int = 10
)

data class BalancerConfigState(
    val strategy: BalancerStrategy = BalancerStrategy.LEAST_LOAD,
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val secondaryProbeUrls: List<String> = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "http://connectivitycheck.gstatic.com/generate_204"
    ),
    val probeIntervalSec: Int = 20,
    val failoverThresholdMs: Int = 6500,
    val tcpHandshakeTimeoutMs: Int = 3200,
    val consecutiveFailuresThreshold: Int = 3,
    val recoverySuccessThreshold: Int = 1,
    val weightChangeHysteresis: Int = 2,
    val reloadCooldownMs: Long = 90_000L,
    val autoFailoverEnabled: Boolean = true,
    val tcpFastOpen: Boolean = false,
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val remoteDns: String = "1.1.1.1,8.8.8.8"
)

data class NodeTrafficSnapshot(
    val serverId: Long,
    val remarks: String,
    val outboundTag: String,
    val healthStatus: NodeHealthStatus,
    val failureReason: ProbeFailureReason = ProbeFailureReason.NONE,
    val consecutiveFailures: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val isHealthy: Boolean,
    val isDropped: Boolean,
    val latencyMs: Long,
    val emaLatencyMs: Long = -1L,
    val measuredThroughputKbps: Long,
    val stabilityScore: Float,
    val weight: Int,
    val sharePercentage: Float,
    val currentDownloadBps: Long,
    val currentUploadBps: Long,
    val totalNodeBytes: Long,
    val activeStreams: Int,
    val failoverTargetRemarks: String? = null
)

data class CoreLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val repeatCount: Int = 1
)

enum class LogLevel {
    INFO,
    HEALTH,
    WEIGHTS,
    BALANCER,
    FAILOVER,
    RECOVERY,
    DNS,
    WARN,
    ERROR
}
