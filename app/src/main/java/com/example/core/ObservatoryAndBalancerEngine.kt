package com.example.core

import android.content.Context
import com.example.data.BalancerConfigState
import com.example.data.BalancerStrategy
import com.example.data.NodeHealthStatus
import com.example.data.NodeTrafficSnapshot
import com.example.data.ProbeFailureReason
import com.example.data.ServerProfileEntity
import com.example.data.TunnelConnectivityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

data class ProbeRawMeasurement(
    val latencyMs: Long,
    val tcpHandshakeMs: Long,
    val failureReason: ProbeFailureReason,
    val testDurationMs: Long
) {
    val isSuccess: Boolean
        get() = latencyMs > 0L && failureReason == ProbeFailureReason.NONE
}

data class NodeHealthCheckResult(
    val serverId: Long,
    val remarks: String,
    val redactedEndpoint: String,
    val previousStatus: NodeHealthStatus,
    val newStatus: NodeHealthStatus,
    val failureReason: ProbeFailureReason,
    val latencyMs: Long,
    val emaLatencyMs: Long,
    val testDurationMs: Long,
    val measuredThroughputKbps: Long,
    val previousWeight: Int,
    val computedWeight: Int,
    val successCount: Int,
    val failureCount: Int,
    val consecutiveFailures: Int,
    val consecutiveSuccesses: Int,
    val stabilityScore: Float,
    val statusChangedAt: Long,
    val isTransientFailure: Boolean,
    val reasonSummary: String
) {
    val isHealthy: Boolean
        get() = newStatus == NodeHealthStatus.HEALTHY || newStatus == NodeHealthStatus.DEGRADED

    val transitionedToUnhealthy: Boolean
        get() = previousStatus != NodeHealthStatus.UNHEALTHY && newStatus == NodeHealthStatus.UNHEALTHY

    val recoveredFromUnhealthy: Boolean
        get() = previousStatus == NodeHealthStatus.UNHEALTHY && isHealthy

    val weightChangedSignificantly: Boolean
        get() = isHealthy && abs(computedWeight - previousWeight) >= 2
}

data class TunnelConnectivityReport(
    val state: TunnelConnectivityState,
    val latencyMs: Long,
    val workingEndpoint: String?,
    val dnsWorking: Boolean,
    val proxyTransportWorking: Boolean,
    val diagnosticMessage: String
)

object ObservatoryAndBalancerEngine {

    // Bounded concurrency semaphore so 9 nodes never spawn 9 simultaneous heavy Go Xray instances
    private val probeConcurrencySemaphore = Semaphore(permits = 3)

    private val directHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Redacts UUIDs, subscription tokens, and full domain names from any log or status string.
     */
    fun redactSensitiveText(raw: String): String {
        var result = raw
        result = result.replace(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            "****-****"
        )
        result = result.replace(
            Regex("(/subs/)[A-Za-z0-9_-]{4,}"),
            "$1***REDACTED***"
        )
        result = result.replace(
            Regex("(https?://)([^/\\s:]+)(:\\d+)?(/[^\\s]*)?")
        ) { match ->
            val scheme = match.groupValues[1]
            val host = match.groupValues[2]
            val port = match.groupValues[3]
            val shortHost = host.take(4) + "***"
            "$scheme$shortHost$port/***"
        }
        return result
    }

    /**
     * Computes an adaptive timeout (in ms) based on the node's historical EMA latency
     * and protocol characteristics (XHTTP + TLS handshakes require ~4.5s-7.5s window).
     */
    fun computeAdaptiveProbeTimeoutMs(
        server: ServerProfileEntity,
        config: BalancerConfigState
    ): Int {
        val baseTimeout = config.failoverThresholdMs.coerceIn(4500, 9000)
        val priorEma = server.emaLatencyMs
        return if (priorEma > 0L) {
            (priorEma * 3L + 2200L).toInt().coerceIn(4800, 8500)
        } else {
            baseTimeout
        }
    }

    /**
     * Performs a detailed, protocol-aware probe of a single proxy node with bounded concurrency
     * and 1 transient retry before classifying any failure reason.
     */
    suspend fun measureServerNodeDetailed(
        server: ServerProfileEntity,
        context: Context? = null,
        config: BalancerConfigState = BalancerConfigState()
    ): ProbeRawMeasurement = withContext(Dispatchers.IO) {
        val startOverallMs = System.currentTimeMillis()
        if (server.isSimulatedDown) {
            return@withContext ProbeRawMeasurement(
                latencyMs = -2L,
                tcpHandshakeMs = -2L,
                failureReason = ProbeFailureReason.SIMULATED_DOWN,
                testDurationMs = 1L
            )
        }

        probeConcurrencySemaphore.withPermit {
            val adaptiveTimeoutMs = computeAdaptiveProbeTimeoutMs(server, config)
            val tcpTimeoutMs = config.tcpHandshakeTimeoutMs.coerceIn(2000, 4500)

            // Step 1: DNS resolution check for the node's host
            val resolvedIp = try {
                if (V2RayConfigEngine.isPureIpAddress(server.address)) {
                    server.address
                } else {
                    val cachedOrFresh = V2RayConfigEngine.resolveDomainToIps(server.address)
                    cachedOrFresh.firstOrNull()
                        ?: InetAddress.getByName(server.address).hostAddress
                }
            } catch (_: UnknownHostException) {
                null
            } catch (_: Exception) {
                null
            }

            if (resolvedIp.isNullOrBlank()) {
                val elapsed = (System.currentTimeMillis() - startOverallMs).coerceAtLeast(1L)
                return@withPermit ProbeRawMeasurement(
                    latencyMs = -2L,
                    tcpHandshakeMs = -2L,
                    failureReason = ProbeFailureReason.DNS_FAILURE,
                    testDurationMs = elapsed
                )
            }

            // Step 2: TCP handshake probe (with 1 fast retry on transient timeout)
            var tcpRtt = -2L
            var tcpFailureReason = ProbeFailureReason.NONE
            for (attempt in 1..2) {
                val tcpStartNanos = System.nanoTime()
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(resolvedIp, server.port), tcpTimeoutMs)
                    }
                    tcpRtt = max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tcpStartNanos))
                    tcpFailureReason = ProbeFailureReason.NONE
                    break
                } catch (_: ConnectException) {
                    tcpFailureReason = ProbeFailureReason.CONNECTION_REFUSED
                    break // Port refused is immediate, no need to retry
                } catch (_: NoRouteToHostException) {
                    tcpFailureReason = ProbeFailureReason.PROXY_UNREACHABLE
                    break
                } catch (_: SocketTimeoutException) {
                    tcpFailureReason = ProbeFailureReason.TIMEOUT
                    if (attempt == 1) delay(200L)
                } catch (_: Exception) {
                    tcpFailureReason = ProbeFailureReason.PROXY_UNREACHABLE
                    if (attempt == 1) delay(200L)
                }
            }

            if (tcpRtt <= 0L) {
                val elapsed = (System.currentTimeMillis() - startOverallMs).coerceAtLeast(1L)
                return@withPermit ProbeRawMeasurement(
                    latencyMs = -2L,
                    tcpHandshakeMs = -2L,
                    failureReason = tcpFailureReason,
                    testDurationMs = elapsed
                )
            }

            // Step 3: If Context is available and main tunnel is not actively contending, test Xray outbound delay
            if (context != null) {
                try {
                    val speedtestJson = V2RayConfigEngine.generateSingleServerSpeedtestJson(
                        server = server.copy(address = resolvedIp, sni = server.effectiveSni),
                        config = config,
                        resolveDomains = false
                    )
                    val xrayDelay = XrayCoreRunner.measureSingleOutboundDelay(
                        context = context,
                        singleOutboundConfigJson = speedtestJson,
                        url = config.probeUrl
                    )
                    if (xrayDelay in 1L..adaptiveTimeoutMs.toLong()) {
                        val elapsed = (System.currentTimeMillis() - startOverallMs).coerceAtLeast(1L)
                        return@withPermit ProbeRawMeasurement(
                            latencyMs = xrayDelay,
                            tcpHandshakeMs = tcpRtt,
                            failureReason = ProbeFailureReason.NONE,
                            testDurationMs = elapsed
                        )
                    }
                    // Retry once with secondary probe URL if primary endpoint timed out over XHTTP
                    val fallbackUrl = config.secondaryProbeUrls.getOrNull(1) ?: config.probeUrl
                    val retryXrayDelay = XrayCoreRunner.measureSingleOutboundDelay(
                        context = context,
                        singleOutboundConfigJson = speedtestJson,
                        url = fallbackUrl
                    )
                    if (retryXrayDelay in 1L..adaptiveTimeoutMs.toLong()) {
                        val elapsed = (System.currentTimeMillis() - startOverallMs).coerceAtLeast(1L)
                        return@withPermit ProbeRawMeasurement(
                            latencyMs = retryXrayDelay,
                            tcpHandshakeMs = tcpRtt,
                            failureReason = ProbeFailureReason.NONE,
                            testDurationMs = elapsed
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            // Step 4: TCP handshake succeeded cleanly; use calibrated RTT from TCP handshake
            val calibratedRtt = if (server.network.equals("xhttp", ignoreCase = true)) {
                (tcpRtt * 1.35).roundToLong().coerceAtLeast(tcpRtt)
            } else {
                tcpRtt
            }
            val elapsed = (System.currentTimeMillis() - startOverallMs).coerceAtLeast(1L)
            ProbeRawMeasurement(
                latencyMs = calibratedRtt,
                tcpHandshakeMs = tcpRtt,
                failureReason = ProbeFailureReason.NONE,
                testDurationMs = elapsed
            )
        }
    }

    /**
     * Backwards-compatible latency probe wrapper.
     */
    suspend fun probeServerLatency(
        server: ServerProfileEntity,
        timeoutMs: Int = 6500,
        context: Context? = null,
        config: BalancerConfigState = BalancerConfigState(failoverThresholdMs = timeoutMs)
    ): Long {
        val measurement = measureServerNodeDetailed(server, context, config)
        return if (measurement.isSuccess) measurement.latencyMs else -2L
    }

    /**
     * Evaluates a single node's health using the multi-observation hysteresis policy.
     */
    suspend fun evaluateNodeHealth(
        server: ServerProfileEntity,
        timeoutMs: Int = 6500,
        context: Context? = null,
        config: BalancerConfigState = BalancerConfigState(failoverThresholdMs = timeoutMs)
    ): NodeHealthCheckResult = withContext(Dispatchers.IO) {
        val measurement = measureServerNodeDetailed(
            server = server,
            context = context,
            config = config
        )
        applyHealthDecisionPolicy(
            server = server,
            measurement = measurement,
            config = config
        )
    }

    /**
     * Pure, deterministic multi-observation health decision & hysteresis policy:
     * - Never marks a node UNHEALTHY after a single failed probe (requires consecutiveFailures >= 3,
     *   or >= 2 hard CONNECTION_REFUSED).
     * - Reduces weight gradually on transient failures instead of immediately evicting the node.
     * - Applies EMA latency smoothing and weight hysteresis to prevent rapid node switching.
     * - Restores recovered nodes smoothly after verification.
     */
    fun applyHealthDecisionPolicy(
        server: ServerProfileEntity,
        measurement: ProbeRawMeasurement,
        config: BalancerConfigState = BalancerConfigState(),
        nowMs: Long = System.currentTimeMillis()
    ): NodeHealthCheckResult {
        val previousStatus = server.healthStatusEnum
        val adaptiveTimeoutMs = computeAdaptiveProbeTimeoutMs(server, config)
        val isReachable = measurement.isSuccess && measurement.latencyMs <= adaptiveTimeoutMs

        if (server.isSimulatedDown || measurement.failureReason == ProbeFailureReason.SIMULATED_DOWN) {
            return NodeHealthCheckResult(
                serverId = server.id,
                remarks = server.remarks,
                redactedEndpoint = server.redactedEndpoint,
                previousStatus = previousStatus,
                newStatus = NodeHealthStatus.UNHEALTHY,
                failureReason = ProbeFailureReason.SIMULATED_DOWN,
                latencyMs = -2L,
                emaLatencyMs = server.emaLatencyMs,
                testDurationMs = measurement.testDurationMs,
                measuredThroughputKbps = 0L,
                previousWeight = server.weight,
                computedWeight = 1,
                successCount = server.successCount,
                failureCount = server.failureCount + 1,
                consecutiveFailures = max(3, server.consecutiveFailures + 1),
                consecutiveSuccesses = 0,
                stabilityScore = 10f,
                statusChangedAt = if (previousStatus != NodeHealthStatus.UNHEALTHY) nowMs else server.lastStatusChangeAt,
                isTransientFailure = false,
                reasonSummary = "Simulated node drop"
            )
        }

        if (isReachable) {
            val rtt = measurement.latencyMs
            val newSuccessCount = server.successCount + 1
            val newFailureCount = server.failureCount
            val newConsecutiveFailures = 0
            val newConsecutiveSuccesses = server.consecutiveSuccesses + 1

            val newEmaLatency = if (server.emaLatencyMs > 0L) {
                ((server.emaLatencyMs * 0.65) + (rtt * 0.35)).roundToLong().coerceAtLeast(1L)
            } else {
                rtt
            }

            val totalChecks = (newSuccessCount + newFailureCount).coerceAtLeast(1)
            val historicalRatio = newSuccessCount.toFloat() / totalChecks.toFloat()
            val recoveryBonus = min(12f, newConsecutiveSuccesses * 3f)
            val stabilityScore = ((historicalRatio * 90f) + recoveryBonus).coerceIn(25f, 100f)

            val probeThroughputKbps = run {
                val baseKbps = (1_800_000L / newEmaLatency.coerceAtLeast(25L)).coerceIn(450L, 48_000L)
                if (server.measuredThroughputKbps > 0L) {
                    ((server.measuredThroughputKbps * 0.50) + (baseKbps * 0.50)).toLong()
                } else {
                    baseKbps
                }
            }

            val newStatus = when {
                previousStatus == NodeHealthStatus.UNHEALTHY &&
                    newConsecutiveSuccesses < config.recoverySuccessThreshold -> {
                    NodeHealthStatus.DEGRADED
                }
                newEmaLatency > 1800L || rtt > 2600L || stabilityScore < 45f -> {
                    NodeHealthStatus.DEGRADED
                }
                else -> NodeHealthStatus.HEALTHY
            }

            val rawWeight = computeDynamicWeight(
                latencyMs = newEmaLatency,
                throughputKbps = probeThroughputKbps,
                stabilityScore = stabilityScore,
                healthStatus = newStatus
            )

            // Apply weight hysteresis: avoid 1-point weight jitter if status didn't change
            val finalWeight = when {
                previousStatus == NodeHealthStatus.UNHEALTHY -> {
                    // Smooth ramp-up on recovery
                    min(rawWeight, 6).coerceAtLeast(3)
                }
                previousStatus == newStatus &&
                    server.successCount > 0 &&
                    abs(rawWeight - server.weight) < config.weightChangeHysteresis -> {
                    server.weight
                }
                else -> rawWeight
            }

            val statusChangedAt = if (newStatus != previousStatus) nowMs else server.lastStatusChangeAt
            val summary = when {
                previousStatus == NodeHealthStatus.UNHEALTHY ->
                    "Recovered after successful check (${rtt}ms, EMA ${newEmaLatency}ms) → Rejoined pool with weight $finalWeight/10"
                newStatus == NodeHealthStatus.DEGRADED ->
                    "High latency (${rtt}ms, EMA ${newEmaLatency}ms) → Kept in pool as Degraded with reduced weight $finalWeight/10"
                else ->
                    "Healthy (${rtt}ms, EMA ${newEmaLatency}ms, test ${measurement.testDurationMs}ms) • Weight $finalWeight/10"
            }

            return NodeHealthCheckResult(
                serverId = server.id,
                remarks = server.remarks,
                redactedEndpoint = server.redactedEndpoint,
                previousStatus = previousStatus,
                newStatus = newStatus,
                failureReason = ProbeFailureReason.NONE,
                latencyMs = rtt,
                emaLatencyMs = newEmaLatency,
                testDurationMs = measurement.testDurationMs,
                measuredThroughputKbps = probeThroughputKbps,
                previousWeight = server.weight,
                computedWeight = finalWeight,
                successCount = newSuccessCount,
                failureCount = newFailureCount,
                consecutiveFailures = newConsecutiveFailures,
                consecutiveSuccesses = newConsecutiveSuccesses,
                stabilityScore = stabilityScore,
                statusChangedAt = statusChangedAt,
                isTransientFailure = false,
                reasonSummary = summary
            )
        } else {
            // Probe failed: apply multi-observation threshold before marking UNHEALTHY
            val newSuccessCount = server.successCount
            val newFailureCount = server.failureCount + 1
            val newConsecutiveFailures = server.consecutiveFailures + 1
            val newConsecutiveSuccesses = 0
            val failureReason = if (measurement.failureReason == ProbeFailureReason.NONE) {
                ProbeFailureReason.TIMEOUT
            } else {
                measurement.failureReason
            }

            val hardFailureThreshold = if (failureReason == ProbeFailureReason.CONNECTION_REFUSED) {
                2
            } else {
                config.consecutiveFailuresThreshold.coerceAtLeast(3)
            }

            val totalChecks = (newSuccessCount + newFailureCount).coerceAtLeast(1)
            val historicalRatio = newSuccessCount.toFloat() / totalChecks.toFloat()
            val consecutivePenalty = (newConsecutiveFailures * 18f).coerceAtMost(75f)
            val stabilityScore = ((historicalRatio * 100f) - consecutivePenalty).coerceIn(5f, 95f)

            val isConfirmedUnhealthy = newConsecutiveFailures >= hardFailureThreshold ||
                previousStatus == NodeHealthStatus.UNHEALTHY

            val newStatus = if (isConfirmedUnhealthy) {
                NodeHealthStatus.UNHEALTHY
            } else {
                // Single or second transient failure: keep in pool as DEGRADED with reduced weight!
                NodeHealthStatus.DEGRADED
            }

            val penalizedEma = if (server.emaLatencyMs > 0L) {
                (server.emaLatencyMs + (450L * newConsecutiveFailures)).coerceAtMost(4500L)
            } else {
                1600L * newConsecutiveFailures
            }

            val reducedWeight = if (newStatus == NodeHealthStatus.UNHEALTHY) {
                1
            } else {
                // Step down weight on transient failure without dropping established flows
                max(2, (server.weight * 0.6f).roundToInt())
            }

            val statusChangedAt = if (newStatus != previousStatus) nowMs else server.lastStatusChangeAt
            val summary = if (newStatus == NodeHealthStatus.UNHEALTHY) {
                "Confirmed unhealthy after $newConsecutiveFailures consecutive failures (${failureReason.diagnosticLabel}) → Excluded from active routing; will retest automatically"
            } else {
                "Transient probe failure ($newConsecutiveFailures/$hardFailureThreshold: ${failureReason.diagnosticLabel}) → Kept in active pool with reduced weight $reducedWeight/10"
            }

            return NodeHealthCheckResult(
                serverId = server.id,
                remarks = server.remarks,
                redactedEndpoint = server.redactedEndpoint,
                previousStatus = previousStatus,
                newStatus = newStatus,
                failureReason = failureReason,
                latencyMs = if (newStatus == NodeHealthStatus.UNHEALTHY) -2L else penalizedEma,
                emaLatencyMs = penalizedEma,
                testDurationMs = measurement.testDurationMs,
                measuredThroughputKbps = (server.measuredThroughputKbps * 0.65).toLong(),
                previousWeight = server.weight,
                computedWeight = reducedWeight,
                successCount = newSuccessCount,
                failureCount = newFailureCount,
                consecutiveFailures = newConsecutiveFailures,
                consecutiveSuccesses = newConsecutiveSuccesses,
                stabilityScore = stabilityScore,
                statusChangedAt = statusChangedAt,
                isTransientFailure = !isConfirmedUnhealthy,
                reasonSummary = summary
            )
        }
    }

    /**
     * Computes a dynamic weight (1..10) for weighted multi-node load balancing:
     * - Low latency (<150ms), high throughput, high stability -> 8..10
     * - Moderate latency (150..450ms) -> 5..7
     * - High latency / degraded (450..1800ms) -> 2..4
     * - Unhealthy -> 1 (excluded from active outbound pool while unhealthy)
     */
    fun computeDynamicWeight(
        latencyMs: Long,
        throughputKbps: Long,
        stabilityScore: Float,
        healthStatus: NodeHealthStatus
    ): Int {
        if (healthStatus == NodeHealthStatus.UNHEALTHY || latencyMs <= 0L) {
            return 1
        }

        val latencyPoints = when {
            latencyMs <= 90L -> 50f
            latencyMs <= 160L -> 44f
            latencyMs <= 260L -> 36f
            latencyMs <= 420L -> 28f
            latencyMs <= 700L -> 20f
            latencyMs <= 1200L -> 12f
            else -> 6f
        }

        val throughputPoints = when {
            throughputKbps >= 15_000L -> 30f
            throughputKbps >= 8_000L -> 24f
            throughputKbps >= 4_000L -> 18f
            throughputKbps >= 1_500L -> 12f
            throughputKbps > 0L -> 6f
            else -> 10f
        }

        val stabilityPoints = (stabilityScore.coerceIn(0f, 100f) / 100f) * 20f

        val composite = (latencyPoints + throughputPoints + stabilityPoints).coerceIn(10f, 100f)
        val rawWeight = (composite / 10f).roundToInt().coerceIn(1, 10)

        return if (healthStatus == NodeHealthStatus.DEGRADED) {
            rawWeight.coerceIn(2, 4)
        } else {
            rawWeight.coerceIn(2, 10)
        }
    }

    /**
     * Verifies real end-to-end internet & DNS connectivity strictly THROUGH the active proxy tunnel.
     * Distinguishes between:
     * - INTERNET_WORKING (End-to-end HTTP 204 over proxy succeeded)
     * - CONNECTION_DEGRADED (Tunnel is up, but domain DNS failed or upstream internet timed out)
     * - VPN_ESTABLISHED
     */
    suspend fun verifyTunnelConnectivity(
        config: BalancerConfigState = BalancerConfigState()
    ): TunnelConnectivityReport = withContext(Dispatchers.IO) {
        val endpoints = (listOf(config.probeUrl) + config.secondaryProbeUrls).distinct()

        // 1. Test via Xray-core's internal measureDelay (routes directly through active balancer outbound)
        for (url in endpoints.take(2)) {
            val coreDelay = XrayCoreRunner.measureRunningTunnelDelay(url)
            if (coreDelay in 1L..8500L) {
                return@withContext TunnelConnectivityReport(
                    state = TunnelConnectivityState.INTERNET_WORKING,
                    latencyMs = coreDelay,
                    workingEndpoint = url,
                    dnsWorking = true,
                    proxyTransportWorking = true,
                    diagnosticMessage = "Verified internet connectivity via Xray tunnel (${coreDelay}ms)"
                )
            }
        }

        // 2. Test via OkHttpClient explicitly bound to the local Xray SOCKS5 inbound (127.0.0.1:socksPort)
        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", config.socksPort))
        val socksClient = directHttpClient.newBuilder()
            .proxy(socksProxy)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        for (url in endpoints) {
            val startNanos = System.nanoTime()
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "v2rayNG/2.3.13")
                    .build()
                socksClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 204 || response.code in 200..399) {
                        val elapsedMs = max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
                        return@withContext TunnelConnectivityReport(
                            state = TunnelConnectivityState.INTERNET_WORKING,
                            latencyMs = elapsedMs,
                            workingEndpoint = url,
                            dnsWorking = true,
                            proxyTransportWorking = true,
                            diagnosticMessage = "Verified internet connectivity via SOCKS5 balancer (${elapsedMs}ms)"
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Domain probes failed: test an IP-literal endpoint through SOCKS5 to distinguish DNS failure vs proxy failure!
        val ipCheckStart = System.nanoTime()
        val ipLiteralWorking = try {
            val ipRequest = Request.Builder()
                .url("http://1.1.1.1/cdn-cgi/trace")
                .head()
                .header("User-Agent", "v2rayNG/2.3.13")
                .build()
            socksClient.newCall(ipRequest).execute().use { resp ->
                resp.code in 200..499
            }
        } catch (_: Exception) {
            false
        }

        if (ipLiteralWorking) {
            val ipLatency = max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ipCheckStart))
            return@withContext TunnelConnectivityReport(
                state = TunnelConnectivityState.CONNECTION_DEGRADED,
                latencyMs = ipLatency,
                workingEndpoint = "1.1.1.1",
                dnsWorking = false,
                proxyTransportWorking = true,
                diagnosticMessage = "Proxy tunnel transport is reachable (${ipLatency}ms), but DNS resolution over tunnel failed"
            )
        }

        TunnelConnectivityReport(
            state = TunnelConnectivityState.CONNECTION_DEGRADED,
            latencyMs = -2L,
            workingEndpoint = null,
            dnsWorking = false,
            proxyTransportWorking = false,
            diagnosticMessage = "VPN tunnel is active, but upstream internet checks timed out across probe endpoints"
        )
    }

    /**
     * Backwards-compatible observatory URL probe.
     */
    suspend fun probeObservatoryUrl(url: String): Long = withContext(Dispatchers.IO) {
        val coreDelay = XrayCoreRunner.measureRunningTunnelDelay(url)
        if (coreDelay > 0L) {
            return@withContext coreDelay
        }
        val report = verifyTunnelConnectivity(BalancerConfigState(probeUrl = url))
        if (report.latencyMs > 0L) report.latencyMs else -2L
    }

    /**
     * Downloads and parses a real V2Ray subscription URL or raw URI list.
     */
    suspend fun fetchSubscriptionUrl(
        urlOrPayload: String,
        groupName: String = "Subscription"
    ): Result<List<ServerProfileEntity>> = withContext(Dispatchers.IO) {
        val trimmed = urlOrPayload.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Subscription URL cannot be empty"))
        }

        if (trimmed.startsWith("vless://", ignoreCase = true) ||
            trimmed.startsWith("vmess://", ignoreCase = true) ||
            trimmed.startsWith("trojan://", ignoreCase = true) ||
            trimmed.startsWith("ss://", ignoreCase = true) ||
            trimmed.startsWith("socks://", ignoreCase = true)
        ) {
            val directParsed = V2RayConfigEngine.parseInputLinks(trimmed, groupName)
            return@withContext if (directParsed.isEmpty()) {
                Result.failure(IllegalArgumentException("Could not parse proxy configuration link"))
            } else {
                Result.success(directParsed)
            }
        }

        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Please enter a valid http:// or https:// subscription URL")
            )
        }

        try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "v2rayNG/2.3.13")
                .build()
            directHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: ${response.message}")
                    )
                }
                val body = response.body?.string().orEmpty()
                val parsed = V2RayConfigEngine.parseInputLinks(body, groupName)
                if (parsed.isEmpty()) {
                    Result.failure(IllegalArgumentException("No supported proxy configurations found in subscription"))
                } else {
                    Result.success(parsed)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Computes real-time traffic distribution across all servers in the active Balancer Pool.
     * Unhealthy or dropped nodes receive 0% share and their traffic is seamlessly redistributed
     * across healthy/degraded nodes according to their dynamic weights, EMA latency, and stability.
     */
    fun calculatePoolDistribution(
        poolServers: List<ServerProfileEntity>,
        config: BalancerConfigState,
        isTunnelConnected: Boolean,
        aggregateDownloadBps: Long,
        aggregateUploadBps: Long
    ): List<NodeTrafficSnapshot> {
        if (poolServers.isEmpty()) return emptyList()

        val eligibleNodes = poolServers.filter {
            !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
        }
        val primaryFailoverTarget = eligibleNodes
            .sortedWith(
                compareByDescending<ServerProfileEntity> {
                    it.healthStatusEnum == NodeHealthStatus.HEALTHY
                }
                    .thenByDescending { it.weight }
                    .thenBy {
                        val rtt = it.effectiveLatencyMs
                        if (rtt > 0) rtt else 250L
                    }
            )
            .firstOrNull()

        val rawScores = mutableMapOf<Long, Float>()
        if (eligibleNodes.isNotEmpty()) {
            when (config.strategy) {
                BalancerStrategy.LEAST_LOAD -> {
                    eligibleNodes.forEach { node ->
                        val effectiveRtt = if (node.effectiveLatencyMs > 0) {
                            node.effectiveLatencyMs.toFloat()
                        } else {
                            150f
                        }
                        val stabilityFactor = (node.stabilityScore.coerceIn(20f, 100f) / 100f)
                        val degradedFactor = if (node.healthStatusEnum == NodeHealthStatus.DEGRADED) 0.45f else 1.0f
                        val score = ((node.weight.coerceAtLeast(1) * 1000f) / (effectiveRtt + 35f)) *
                            stabilityFactor * degradedFactor
                        rawScores[node.id] = score
                    }
                }
                BalancerStrategy.LEAST_PING -> {
                    val sorted = eligibleNodes.sortedBy {
                        if (it.effectiveLatencyMs > 0) it.effectiveLatencyMs else 150L
                    }
                    sorted.forEachIndexed { index, node ->
                        val rankWeight = when (index) {
                            0 -> 65f
                            1 -> 22f
                            2 -> 9f
                            else -> 4f
                        }
                        rawScores[node.id] = rankWeight * (0.7f + node.weight * 0.06f)
                    }
                }
                BalancerStrategy.ROUND_ROBIN -> {
                    eligibleNodes.forEach { node ->
                        rawScores[node.id] = node.weight.coerceIn(1, 10).toFloat()
                    }
                }
                BalancerStrategy.RANDOM -> {
                    eligibleNodes.forEach { node ->
                        val jitter = 0.85f + Random.nextFloat() * 0.3f
                        rawScores[node.id] = jitter
                    }
                }
            }
        }

        val totalScore = rawScores.values.sum().coerceAtLeast(0.001f)

        return poolServers.map { server ->
            val status = server.healthStatusEnum
            val isDropped = server.isSimulatedDown || status == NodeHealthStatus.UNHEALTHY
            val isHealthy = !isDropped
            val shareFraction = if (isHealthy && eligibleNodes.isNotEmpty()) {
                (rawScores[server.id] ?: 0f) / totalScore
            } else {
                0f
            }
            val sharePct = (shareFraction * 100f)

            val nodeDownBps = if (isTunnelConnected && isHealthy) {
                (aggregateDownloadBps * shareFraction).toLong()
            } else 0L

            val nodeUpBps = if (isTunnelConnected && isHealthy) {
                (aggregateUploadBps * shareFraction).toLong()
            } else 0L

            val streams = if (isTunnelConnected && isHealthy) {
                max(1, (shareFraction * config.muxConcurrency * eligibleNodes.size).roundToInt())
            } else 0

            NodeTrafficSnapshot(
                serverId = server.id,
                remarks = server.remarks,
                outboundTag = server.outboundTag,
                healthStatus = status,
                failureReason = server.failureReasonEnum,
                consecutiveFailures = server.consecutiveFailures,
                successCount = server.successCount,
                failureCount = server.failureCount,
                isHealthy = isHealthy,
                isDropped = isDropped,
                latencyMs = if (server.isSimulatedDown) -2L else server.lastLatencyMs,
                emaLatencyMs = server.emaLatencyMs,
                measuredThroughputKbps = server.measuredThroughputKbps,
                stabilityScore = server.stabilityScore,
                weight = server.weight,
                sharePercentage = sharePct,
                currentDownloadBps = nodeDownBps,
                currentUploadBps = nodeUpBps,
                totalNodeBytes = server.totalBytesDown + server.totalBytesUp,
                activeStreams = streams,
                failoverTargetRemarks = if (isDropped) primaryFailoverTarget?.remarks else null
            )
        }
    }
}
