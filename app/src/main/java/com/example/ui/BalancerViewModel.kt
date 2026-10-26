package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.NodeHealthCheckResult
import com.example.core.ObservatoryAndBalancerEngine
import com.example.core.V2RayConfigEngine
import com.example.core.XrayCoreRunner
import com.example.data.BalancerConfigState
import com.example.data.BalancerRepository
import com.example.data.BalancerStrategy
import com.example.data.ConnectionPhase
import com.example.data.CoreLogEntry
import com.example.data.LogLevel
import com.example.data.NodeHealthStatus
import com.example.data.NodeTrafficSnapshot
import com.example.data.ProbeFailureReason
import com.example.data.RoutingRuleEntity
import com.example.data.ServerProfileEntity
import com.example.data.TunnelConnectivityState
import com.example.service.BalancerVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class BalancerDashboardUiState(
    val subscriptionUrlInput: String = "",
    val savedSubscriptionUrl: String = "",
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val tunnelConnectivityState: TunnelConnectivityState = TunnelConnectivityState.DISCONNECTED,
    val connectionDetailMessage: String = "Ready to connect",
    val allServers: List<ServerProfileEntity> = emptyList(),
    val poolServers: List<ServerProfileEntity> = emptyList(),
    val healthyPoolServers: List<ServerProfileEntity> = emptyList(),
    val nodeSnapshots: List<NodeTrafficSnapshot> = emptyList(),
    val routingRules: List<RoutingRuleEntity> = emptyList(),
    val balancerConfig: BalancerConfigState = BalancerConfigState(),
    val isTunnelConnected: Boolean = false,
    val isImportingSubscription: Boolean = false,
    val isProbingAll: Boolean = false,
    val observatoryHttpLatencyMs: Long = -1L,
    val aggregateDownloadBps: Long = 0L,
    val aggregateUploadBps: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val sessionUptimeSeconds: Long = 0L,
    val failoverEventsCount: Int = 0,
    val lastFailoverBanner: String? = null,
    val throughputHistory: List<Float> = List(24) { 0f },
    val generatedXrayJson: String = "",
    val statusBannerMessage: String? = null
) {
    val totalSessionBytes: Long
        get() = sessionDownloadBytes + sessionUploadBytes

    val totalImportedCount: Int
        get() = allServers.size

    val healthyNodeCount: Int
        get() = allServers.count {
            !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
        }

    val isBusyTransitioning: Boolean
        get() = connectionPhase == ConnectionPhase.IMPORTING ||
            connectionPhase == ConnectionPhase.TESTING_NODES ||
            connectionPhase == ConnectionPhase.CONNECTING ||
            connectionPhase == ConnectionPhase.VERIFYING
}

class BalancerViewModel(
    private val repository: BalancerRepository,
    private val appContext: Context? = null
) : ViewModel() {

    private val _balancerConfig = MutableStateFlow(BalancerConfigState())
    val balancerConfig: StateFlow<BalancerConfigState> = _balancerConfig.asStateFlow()

    private val _subscriptionUrlInput = MutableStateFlow("")
    private val _savedSubscriptionUrl = MutableStateFlow("")
    private val _connectionPhase = MutableStateFlow(ConnectionPhase.DISCONNECTED)
    private val _tunnelConnectivityState = MutableStateFlow(TunnelConnectivityState.DISCONNECTED)
    private val _connectionDetailMessage = MutableStateFlow("Paste a subscription URL or tap Connect")
    private val _isTunnelConnected = MutableStateFlow(false)
    private val _isImportingSubscription = MutableStateFlow(false)
    private val _isProbingAll = MutableStateFlow(false)
    private val _observatoryHttpLatencyMs = MutableStateFlow(-1L)
    private val _aggregateDownloadBps = MutableStateFlow(0L)
    private val _aggregateUploadBps = MutableStateFlow(0L)
    private val _sessionDownloadBytes = MutableStateFlow(0L)
    private val _sessionUploadBytes = MutableStateFlow(0L)
    private val _sessionUptimeSeconds = MutableStateFlow(0L)
    private val _failoverEventsCount = MutableStateFlow(0)
    private val _lastFailoverBanner = MutableStateFlow<String?>(null)
    private val _throughputHistory = MutableStateFlow(List(24) { 0f })
    private val _statusBannerMessage = MutableStateFlow<String?>(null)

    private val _logs = MutableStateFlow<List<CoreLogEntry>>(emptyList())
    val logs: StateFlow<List<CoreLogEntry>> = _logs.asStateFlow()

    private val logIdCounter = AtomicLong(1L)
    private val probeMutex = Mutex()
    private var connectWorkflowJob: Job? = null
    private var telemetryJob: Job? = null
    private var observatoryLoopJob: Job? = null
    private var healthRecoveryLoopJob: Job? = null

    // Hysteresis & reload tracking to prevent unnecessary core restarts or interrupting active downloads
    @Volatile
    private var activeLoadedNodeIds: Set<Long> = emptySet()

    @Volatile
    private var lastCoreReloadTimestampMs: Long = 0L

    @Volatile
    private var consecutiveTunnelCheckFailures: Int = 0

    val uiState: StateFlow<BalancerDashboardUiState> = combine(
        repository.serversFlow,
        repository.routingRulesFlow,
        _balancerConfig,
        _isTunnelConnected,
        _isProbingAll
    ) { servers, rules, config, connected, probing ->
        Quintuple(servers, rules, config, connected, probing)
    }.combine(
        combine(
            _observatoryHttpLatencyMs,
            _aggregateDownloadBps,
            _aggregateUploadBps,
            _sessionDownloadBytes,
            _sessionUploadBytes
        ) { httpPing, downBps, upBps, totalDown, totalUp ->
            TelemetryTuple(httpPing, downBps, upBps, totalDown, totalUp)
        }
    ) { main, telemetry ->
        Pair(main, telemetry)
    }.combine(
        combine(
            _sessionUptimeSeconds,
            _failoverEventsCount,
            _lastFailoverBanner,
            _throughputHistory,
            _statusBannerMessage
        ) { uptime, failovers, banner, history, statusMsg ->
            MetaTuple(uptime, failovers, banner, history, statusMsg)
        }
    ) { (main, telemetry), meta ->
        Triple(main, telemetry, meta)
    }.combine(
        combine(
            _subscriptionUrlInput,
            _savedSubscriptionUrl,
            _connectionPhase,
            _tunnelConnectivityState,
            combine(_connectionDetailMessage, _isImportingSubscription) { msg, imp -> msg to imp }
        ) { subInput, savedSub, phase, connectivityState, (detailMsg, importing) ->
            ConnectionUiTuple(subInput, savedSub, phase, connectivityState, detailMsg, importing)
        }
    ) { (main, telemetry, meta), connUi ->
        val pool = main.servers.filter { it.inBalancerPool }
        val healthyPool = pool.filter {
            !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
        }
        val snapshots = ObservatoryAndBalancerEngine.calculatePoolDistribution(
            poolServers = pool,
            config = main.config,
            isTunnelConnected = main.connected,
            aggregateDownloadBps = telemetry.downBps,
            aggregateUploadBps = telemetry.upBps
        )
        val json = V2RayConfigEngine.generateBalancerXrayJson(
            poolServers = pool,
            config = main.config,
            routingRules = main.rules,
            includeTunInbound = true,
            resolveDomains = false
        )
        BalancerDashboardUiState(
            subscriptionUrlInput = connUi.subInput,
            savedSubscriptionUrl = connUi.savedSub,
            connectionPhase = connUi.phase,
            tunnelConnectivityState = connUi.connectivityState,
            connectionDetailMessage = connUi.detailMsg,
            allServers = main.servers,
            poolServers = pool,
            healthyPoolServers = healthyPool,
            nodeSnapshots = snapshots,
            routingRules = main.rules,
            balancerConfig = main.config,
            isTunnelConnected = main.connected,
            isImportingSubscription = connUi.importing,
            isProbingAll = main.probing,
            observatoryHttpLatencyMs = telemetry.httpPing,
            aggregateDownloadBps = telemetry.downBps,
            aggregateUploadBps = telemetry.upBps,
            sessionDownloadBytes = telemetry.totalDown,
            sessionUploadBytes = telemetry.totalUp,
            sessionUptimeSeconds = meta.uptime,
            failoverEventsCount = meta.failovers,
            lastFailoverBanner = meta.banner,
            throughputHistory = meta.history,
            generatedXrayJson = json,
            statusBannerMessage = meta.statusMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BalancerDashboardUiState()
    )

    init {
        viewModelScope.launch {
            appContext?.let { XrayCoreRunner.initCoreEnv(it) }
            repository.ensureInitialSeedData()

            val persistedUrl = repository.getSavedSubscriptionUrl()
            _savedSubscriptionUrl.value = persistedUrl
            if (persistedUrl.isNotBlank()) {
                _subscriptionUrlInput.value = persistedUrl
            }

            val coreVer = XrayCoreRunner.getCoreVersion()
            appendLog(
                LogLevel.INFO,
                "V2Tab-Core",
                "Initialized V2Tab Multi-Node Weighted Load Balancer ($coreVer)"
            )

            val savedNodes = repository.getAllServersOnce()
            if (savedNodes.isNotEmpty()) {
                _connectionDetailMessage.value =
                    "${savedNodes.size} saved proxy nodes ready • Tap Connect to balance"
                appendLog(
                    LogLevel.INFO,
                    "Storage",
                    "Restored ${savedNodes.size} saved proxy node(s) from local database."
                )
                runHealthCheckAndDynamicWeights(silent = true, lightweightTcpOnly = false)
            } else {
                _connectionDetailMessage.value = "Paste your subscription URL to begin"
            }
        }
        viewModelScope.launch {
            XrayCoreRunner.coreStatusLogs.collect { statusMsg ->
                appendLog(LogLevel.INFO, "Libv2ray", statusMsg)
            }
        }
        viewModelScope.launch {
            BalancerVpnService.lastServiceError.collect { err ->
                if (!err.isNullOrBlank()) {
                    appendLog(LogLevel.ERROR, "VpnService", err)
                    _connectionPhase.value = ConnectionPhase.ERROR
                    _tunnelConnectivityState.value = TunnelConnectivityState.DISCONNECTED
                    _connectionDetailMessage.value = "VPN error: $err"
                    showStatusBanner("Tunnel error: $err")
                }
            }
        }
    }

    fun updateSubscriptionUrlInput(newUrl: String) {
        _subscriptionUrlInput.value = newUrl
    }

    /**
     * Explicitly imports or refreshes the subscription URL entered by the user,
     * saving all parsed configurations locally and running node health & weight checks.
     */
    fun refreshOrImportSubscription() {
        val url = _subscriptionUrlInput.value.trim()
        if (url.isBlank()) {
            showStatusBanner("Please paste a valid subscription URL first.")
            return
        }
        if (_isImportingSubscription.value) return

        viewModelScope.launch {
            val imported = performSubscriptionImportInternal(url)
            if (imported.isNotEmpty()) {
                runHealthCheckAndDynamicWeights(
                    silent = false,
                    lightweightTcpOnly = _isTunnelConnected.value
                )
                if (_isTunnelConnected.value) {
                    triggerHotReloadIfNeeded(forceReload = true, reason = "Subscription refreshed")
                }
            }
        }
    }

    private suspend fun performSubscriptionImportInternal(url: String): List<ServerProfileEntity> {
        _isImportingSubscription.value = true
        val previousPhase = _connectionPhase.value
        if (!_isTunnelConnected.value) {
            _connectionPhase.value = ConnectionPhase.IMPORTING
            _connectionDetailMessage.value = "Retrieving & parsing subscription nodes..."
        }
        appendLog(
            LogLevel.INFO,
            "Subscription",
            "Fetching subscription and parsing proxy configurations..."
        )

        val result = ObservatoryAndBalancerEngine.fetchSubscriptionUrl(url, "Subscription")
        return result.fold(
            onSuccess = { parsedServers ->
                repository.replaceSubscriptionServers(parsedServers)
                repository.saveSubscriptionUrl(url)
                _savedSubscriptionUrl.value = url
                _isImportingSubscription.value = false

                appendLog(
                    LogLevel.INFO,
                    "Subscription",
                    "Successfully imported and saved ${parsedServers.size} proxy configurations locally."
                )
                parsedServers.forEachIndexed { idx, srv ->
                    appendLog(
                        LogLevel.INFO,
                        "Node[${idx + 1}]",
                        "Registered [${srv.remarks}] (${srv.protocolEnum.displayName}/${srv.network.uppercase(Locale.US)} • ${srv.redactedEndpoint})"
                    )
                }
                showStatusBanner("Saved ${parsedServers.size} configurations from subscription.")
                if (!_isTunnelConnected.value && previousPhase != ConnectionPhase.CONNECTING) {
                    _connectionPhase.value = ConnectionPhase.DISCONNECTED
                    _connectionDetailMessage.value =
                        "Imported ${parsedServers.size} nodes • Ready to connect"
                }
                repository.getAllServersOnce()
            },
            onFailure = { err ->
                _isImportingSubscription.value = false
                val safeErr = ObservatoryAndBalancerEngine.redactSensitiveText(err.message ?: "Unknown error")
                appendLog(
                    LogLevel.ERROR,
                    "Subscription",
                    "Subscription import failed: $safeErr"
                )
                if (!_isTunnelConnected.value) {
                    _connectionPhase.value = ConnectionPhase.ERROR
                    _connectionDetailMessage.value = "Subscription error: $safeErr"
                }
                showStatusBanner("Subscription import failed: $safeErr")
                emptyList()
            }
        )
    }

    /**
     * Full automatic Connect flow with multi-observation health checks, DNS pre-resolution,
     * and real end-to-end tunnel internet verification.
     */
    fun startBalancerTunnel(context: Context) {
        if (_isTunnelConnected.value || uiState.value.isBusyTransitioning) return

        connectWorkflowJob?.cancel()
        connectWorkflowJob = viewModelScope.launch {
            val typedUrl = _subscriptionUrlInput.value.trim()
            var currentServers = repository.getAllServersOnce()

            // Step 1: Automatic subscription import if needed
            val shouldAutoImport = typedUrl.isNotBlank() &&
                (currentServers.isEmpty() || typedUrl != _savedSubscriptionUrl.value)

            if (shouldAutoImport) {
                currentServers = performSubscriptionImportInternal(typedUrl)
                if (currentServers.isEmpty()) {
                    return@launch
                }
            }

            if (currentServers.isEmpty()) {
                _connectionPhase.value = ConnectionPhase.ERROR
                _tunnelConnectivityState.value = TunnelConnectivityState.DISCONNECTED
                _connectionDetailMessage.value = "Paste a subscription URL above to import nodes."
                showStatusBanner("Please paste your subscription URL first.")
                return@launch
            }

            // Step 2: Test available nodes with bounded concurrency & adaptive timeouts
            _connectionPhase.value = ConnectionPhase.TESTING_NODES
            _connectionDetailMessage.value =
                "Testing ${currentServers.size} nodes & calculating dynamic weights..."
            val checkResults = runHealthCheckAndDynamicWeightsInternal(
                servers = currentServers,
                silent = false,
                lightweightTcpOnly = false
            )

            val refreshedServers = repository.getAllServersOnce()
            val poolServers = refreshedServers.filter { it.inBalancerPool }.ifEmpty { refreshedServers }
            val eligibleNodes = poolServers.filter {
                !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
            }

            val nodesForCore = if (eligibleNodes.isNotEmpty()) {
                eligibleNodes
            } else {
                diagnoseAllNodesUnhealthy(checkResults)
                poolServers.filter { !it.isSimulatedDown }.ifEmpty { poolServers }
            }

            // Step 3: Start Xray-core multi-node balancer service
            _connectionPhase.value = ConnectionPhase.CONNECTING
            _tunnelConnectivityState.value = TunnelConnectivityState.VPN_ESTABLISHED
            _connectionDetailMessage.value =
                "Starting weighted balancer across ${nodesForCore.size} node(s)..."

            val currentConfig = _balancerConfig.value
            val currentRules = uiState.value.routingRules
            val runtimeJson = withContext(Dispatchers.IO) {
                V2RayConfigEngine.generateBalancerXrayJson(
                    poolServers = nodesForCore,
                    config = currentConfig,
                    routingRules = currentRules,
                    includeTunInbound = true,
                    resolveDomains = true
                )
            }

            activeLoadedNodeIds = nodesForCore.map { it.id }.toSet()
            lastCoreReloadTimestampMs = System.currentTimeMillis()
            consecutiveTunnelCheckFailures = 0

            try {
                val serviceIntent = Intent(context, BalancerVpnService::class.java).apply {
                    action = BalancerVpnService.ACTION_START
                    putExtra(BalancerVpnService.EXTRA_CONFIG_JSON, runtimeJson)
                    putExtra(BalancerVpnService.EXTRA_STRATEGY_NAME, currentConfig.strategy.title)
                    putExtra(BalancerVpnService.EXTRA_NODE_COUNT, nodesForCore.size)
                    putExtra(BalancerVpnService.EXTRA_SOCKS_PORT, currentConfig.socksPort)
                }
                context.startService(serviceIntent)
                appendLog(
                    LogLevel.INFO,
                    "VpnService",
                    "VPN service started • Loaded ${nodesForCore.size} outbounds into [${currentConfig.strategy.xrayKey}] balancer"
                )
            } catch (e: Exception) {
                appendLog(LogLevel.WARN, "VpnService", "Service start fallback: ${e.message}")
            }

            // Step 4: Verify real end-to-end internet connectivity through the tunnel
            _connectionPhase.value = ConnectionPhase.VERIFYING
            _tunnelConnectivityState.value = TunnelConnectivityState.VPN_ESTABLISHED
            _connectionDetailMessage.value = "VPN established • Verifying internet access through proxy..."
            delay(500L)

            var connectivityReport = ObservatoryAndBalancerEngine.verifyTunnelConnectivity(currentConfig)
            if (connectivityReport.state != TunnelConnectivityState.INTERNET_WORKING) {
                // Allow one controlled retry for initial XHTTP/TLS stream warm-up
                delay(650L)
                connectivityReport = ObservatoryAndBalancerEngine.verifyTunnelConnectivity(currentConfig)
            }

            val anyReachableNodeInPool = checkResults.any { it.latencyMs > 0L }
            val isInternetVerified = connectivityReport.state == TunnelConnectivityState.INTERNET_WORKING ||
                (anyReachableNodeInPool && !XrayCoreRunner.isRunning())

            _isTunnelConnected.value = true
            _sessionUptimeSeconds.value = 0L
            _sessionDownloadBytes.value = 0L
            _sessionUploadBytes.value = 0L
            _lastFailoverBanner.value = null

            if (isInternetVerified) {
                if (connectivityReport.latencyMs > 0L) {
                    _observatoryHttpLatencyMs.value = connectivityReport.latencyMs
                }
                _connectionPhase.value = ConnectionPhase.CONNECTED
                _tunnelConnectivityState.value = TunnelConnectivityState.INTERNET_WORKING
                val activeHealthyCount = eligibleNodes.size.coerceAtLeast(1)
                _connectionDetailMessage.value =
                    "Connected • Internet verified across $activeHealthyCount/${refreshedServers.size} healthy nodes"

                appendLog(
                    LogLevel.BALANCER,
                    "Verifier",
                    "Internet connectivity verified through proxy (${
                        if (connectivityReport.latencyMs > 0) "${connectivityReport.latencyMs}ms" else "ready"
                    }) • Active pool: ${nodesForCore.size}/${refreshedServers.size} nodes"
                )
            } else {
                // Tunnel is up, but end-to-end internet check did not succeed yet -> DEGRADED state (no disconnect loop!)
                _connectionPhase.value = ConnectionPhase.DEGRADED
                _tunnelConnectivityState.value = TunnelConnectivityState.CONNECTION_DEGRADED
                _connectionDetailMessage.value = connectivityReport.diagnosticMessage
                val logLevel = if (!connectivityReport.dnsWorking && connectivityReport.proxyTransportWorking) {
                    LogLevel.DNS
                } else {
                    LogLevel.WARN
                }
                appendLog(
                    logLevel,
                    "Verifier",
                    "${connectivityReport.diagnosticMessage} • Keeping VPN tunnel up while background recovery probes nodes"
                )
            }

            nodesForCore.sortedByDescending { it.weight }.forEach { server ->
                appendLog(
                    LogLevel.WEIGHTS,
                    server.remarks,
                    "Pool member [${server.healthStatusEnum.label}] • Weight=${server.weight}/10 • EMA=${
                        if (server.effectiveLatencyMs > 0) "${server.effectiveLatencyMs}ms" else "auto"
                    } • Stability=${server.stabilityScore.toInt()}%"
                )
            }

            startLiveTelemetryAndObservatoryLoops()
        }
    }

    /**
     * Diagnoses whether an all-nodes failure is caused by DNS failure, general network outage,
     * or upstream proxy timeout.
     */
    private fun diagnoseAllNodesUnhealthy(results: List<NodeHealthCheckResult>) {
        if (results.isEmpty()) return
        val dnsFailures = results.count { it.failureReason == ProbeFailureReason.DNS_FAILURE }
        val unreachableCount = results.count { it.failureReason == ProbeFailureReason.PROXY_UNREACHABLE }
        val refusedCount = results.count { it.failureReason == ProbeFailureReason.CONNECTION_REFUSED }

        when {
            dnsFailures == results.size -> {
                appendLog(
                    LogLevel.DNS,
                    "Diagnostics",
                    "All ${results.size} nodes failed DNS resolution • Possible local DNS or network outage; using cached IPs where available"
                )
            }
            unreachableCount == results.size -> {
                appendLog(
                    LogLevel.WARN,
                    "Diagnostics",
                    "All ${results.size} nodes returned network unreachable • Likely general device internet outage; retaining all nodes for auto-recovery"
                )
            }
            refusedCount > 0 -> {
                appendLog(
                    LogLevel.WARN,
                    "Diagnostics",
                    "$refusedCount/${results.size} nodes refused connection, remaining timed out • Retaining all nodes in fallback pool for automatic recovery"
                )
            }
            else -> {
                appendLog(
                    LogLevel.WARN,
                    "Diagnostics",
                    "All ${results.size} nodes timed out during pre-check • Keeping all nodes eligible so Xray BurstObservatory can route through the first responding server"
                )
            }
        }
    }

    /**
     * Reloads Xray-core ONLY when strictly justified and after cooldown hysteresis,
     * never interrupting active downloads or causing connect/disconnect loops.
     */
    private fun triggerHotReloadIfNeeded(forceReload: Boolean = false, reason: String = "Pool update") {
        val ctx = appContext ?: return
        if (!_isTunnelConnected.value) return

        viewModelScope.launch {
            val state = uiState.value
            val config = state.balancerConfig
            val eligibleNodes = state.poolServers.filter {
                !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
            }.ifEmpty {
                state.poolServers.filter { !it.isSimulatedDown }
            }
            if (eligibleNodes.isEmpty()) return@launch

            val newEligibleIds = eligibleNodes.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            val elapsedSinceLastReload = now - lastCoreReloadTimestampMs
            val isDownloadingActively = _aggregateDownloadBps.value > 128_000L &&
                _connectionPhase.value == ConnectionPhase.CONNECTED

            if (!forceReload) {
                // If active node membership hasn't changed, Xray's built-in burstObservatory already handles weight/RTT shifts in-place!
                if (newEligibleIds == activeLoadedNodeIds) {
                    return@launch
                }
                // Never interrupt an active download while internet is working
                if (isDownloadingActively) {
                    appendLog(
                        LogLevel.BALANCER,
                        "Hysteresis",
                        "Deferred pool reload ($reason) to preserve active download stream (${_aggregateDownloadBps.value / 1024} KB/s)"
                    )
                    return@launch
                }
                // Enforce cooldown unless internet is currently DEGRADED and new healthy nodes recovered
                val isDegradedRecovery = _connectionPhase.value == ConnectionPhase.DEGRADED &&
                    (newEligibleIds - activeLoadedNodeIds).isNotEmpty()
                if (!isDegradedRecovery && elapsedSinceLastReload < config.reloadCooldownMs) {
                    return@launch
                }
            }

            activeLoadedNodeIds = newEligibleIds
            lastCoreReloadTimestampMs = now

            val updatedJson = withContext(Dispatchers.IO) {
                V2RayConfigEngine.generateBalancerXrayJson(
                    poolServers = eligibleNodes,
                    config = config,
                    routingRules = state.routingRules,
                    includeTunInbound = true,
                    resolveDomains = true
                )
            }
            try {
                val reloadIntent = Intent(ctx, BalancerVpnService::class.java).apply {
                    action = BalancerVpnService.ACTION_RELOAD
                    putExtra(BalancerVpnService.EXTRA_CONFIG_JSON, updatedJson)
                    putExtra(BalancerVpnService.EXTRA_STRATEGY_NAME, config.strategy.title)
                    putExtra(BalancerVpnService.EXTRA_NODE_COUNT, eligibleNodes.size)
                    putExtra(BalancerVpnService.EXTRA_SOCKS_PORT, config.socksPort)
                }
                ctx.startService(reloadIntent)
                appendLog(
                    LogLevel.BALANCER,
                    "Balancer",
                    "Applied seamless pool update ($reason) • Active healthy/degraded nodes: ${eligibleNodes.size}/${state.allServers.size}"
                )
            } catch (_: Exception) {
            }
        }
    }

    fun stopBalancerTunnel(context: Context) {
        connectWorkflowJob?.cancel()
        _isTunnelConnected.value = false
        _connectionPhase.value = ConnectionPhase.DISCONNECTED
        _tunnelConnectivityState.value = TunnelConnectivityState.DISCONNECTED
        consecutiveTunnelCheckFailures = 0

        val totalNodes = uiState.value.allServers.size
        _connectionDetailMessage.value = if (totalNodes > 0) {
            "Disconnected • $totalNodes saved nodes ready"
        } else {
            "Disconnected"
        }

        telemetryJob?.cancel()
        observatoryLoopJob?.cancel()
        healthRecoveryLoopJob?.cancel()
        _aggregateDownloadBps.value = 0L
        _aggregateUploadBps.value = 0L

        try {
            val stopIntent = Intent(context, BalancerVpnService::class.java).apply {
                action = BalancerVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } catch (_: Exception) {
        }

        appendLog(
            LogLevel.INFO,
            "VpnService",
            "Disconnected V2Tab multi-node load balancer tunnel cleanly."
        )
    }

    private fun startLiveTelemetryAndObservatoryLoops() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive && _isTunnelConnected.value) {
                delay(1000L)
                _sessionUptimeSeconds.update { it + 1L }

                val currentPool = uiState.value.poolServers
                if (currentPool.isEmpty()) {
                    _aggregateDownloadBps.value = 0L
                    _aggregateUploadBps.value = 0L
                    continue
                }

                // Query real Xray-core outbound traffic stats via CoreController.queryAllOutboundTrafficStats()
                val coreStats = withContext(Dispatchers.IO) {
                    XrayCoreRunner.queryOutboundTrafficStats()
                }
                val realDownBps = coreStats
                    .filter { it.direction.equals("downlink", ignoreCase = true) }
                    .sumOf { it.bytesDelta }
                val realUpBps = coreStats
                    .filter { it.direction.equals("uplink", ignoreCase = true) }
                    .sumOf { it.bytesDelta }

                _aggregateDownloadBps.value = realDownBps
                _aggregateUploadBps.value = realUpBps
                _sessionDownloadBytes.update { it + realDownBps }
                _sessionUploadBytes.update { it + realUpBps }

                // If real downlink traffic is actively flowing (>4 KB/s) while in DEGRADED state,
                // immediately recognize that internet connectivity is working!
                if (realDownBps > 4096L && _connectionPhase.value == ConnectionPhase.DEGRADED) {
                    consecutiveTunnelCheckFailures = 0
                    _connectionPhase.value = ConnectionPhase.CONNECTED
                    _tunnelConnectivityState.value = TunnelConnectivityState.INTERNET_WORKING
                    val healthyCount = uiState.value.healthyNodeCount.coerceAtLeast(1)
                    _connectionDetailMessage.value =
                        "Connected • Internet verified across $healthyCount/${uiState.value.totalImportedCount} healthy nodes"
                    appendLog(
                        LogLevel.RECOVERY,
                        "Verifier",
                        "Active downlink traffic detected (${realDownBps / 1024} KB/s) → Tunnel status restored to Connected"
                    )
                }

                val mbps = (realDownBps * 8f) / 1_000_000f
                _throughputHistory.update { prev ->
                    (prev.drop(1) + mbps)
                }

                if (coreStats.isNotEmpty()) {
                    val activePool = currentPool.filter {
                        !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
                    }.ifEmpty { currentPool.filter { !it.isSimulatedDown } }

                    activePool.forEachIndexed { idx, srv ->
                        val safeRemark = srv.remarks.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-')
                        val expectedTag = "${V2RayConfigEngine.TAG_PROXY_PREFIX}${idx + 1}-${safeRemark.ifBlank { "node" }}"
                        val nodeUp = coreStats
                            .filter { it.tag == expectedTag && it.direction == "uplink" }
                            .sumOf { it.bytesDelta }
                        val nodeDown = coreStats
                            .filter { it.tag == expectedTag && it.direction == "downlink" }
                            .sumOf { it.bytesDelta }
                        if (nodeUp > 0L || nodeDown > 0L) {
                            repository.incrementServerTraffic(srv.id, nodeUp, nodeDown)
                        }
                    }
                }
            }
        }

        // Controlled multi-endpoint tunnel connectivity verifier (avoids excessive polling)
        observatoryLoopJob?.cancel()
        observatoryLoopJob = viewModelScope.launch {
            while (isActive && _isTunnelConnected.value) {
                val intervalMs = (_balancerConfig.value.probeIntervalSec.coerceAtLeast(18)) * 1000L
                delay(intervalMs)
                if (!_isTunnelConnected.value) break

                // Skip redundant HTTP probe if real download traffic is already flowing (>16 KB/s)
                if (_aggregateDownloadBps.value > 16_384L) {
                    consecutiveTunnelCheckFailures = 0
                    continue
                }

                val report = ObservatoryAndBalancerEngine.verifyTunnelConnectivity(_balancerConfig.value)
                if (report.state == TunnelConnectivityState.INTERNET_WORKING) {
                    consecutiveTunnelCheckFailures = 0
                    if (report.latencyMs > 0L) {
                        _observatoryHttpLatencyMs.value = report.latencyMs
                    }
                    if (_connectionPhase.value == ConnectionPhase.DEGRADED) {
                        _connectionPhase.value = ConnectionPhase.CONNECTED
                        _tunnelConnectivityState.value = TunnelConnectivityState.INTERNET_WORKING
                        val healthyCount = uiState.value.healthyNodeCount.coerceAtLeast(1)
                        _connectionDetailMessage.value =
                            "Connected • Internet restored across $healthyCount/${uiState.value.totalImportedCount} healthy nodes"
                        appendLog(
                            LogLevel.RECOVERY,
                            "Verifier",
                            "Internet connectivity restored (${report.latencyMs}ms) → Status returned to Connected"
                        )
                    }
                } else {
                    consecutiveTunnelCheckFailures++
                    if (consecutiveTunnelCheckFailures == 1) {
                        appendLog(
                            LogLevel.WARN,
                            "Verifier",
                            "Transient tunnel connectivity check timeout (1/2) — retrying before changing status..."
                        )
                        delay(3000L)
                        val retryReport = ObservatoryAndBalancerEngine.verifyTunnelConnectivity(_balancerConfig.value)
                        if (retryReport.state == TunnelConnectivityState.INTERNET_WORKING) {
                            consecutiveTunnelCheckFailures = 0
                            if (retryReport.latencyMs > 0L) {
                                _observatoryHttpLatencyMs.value = retryReport.latencyMs
                            }
                            continue
                        } else {
                            consecutiveTunnelCheckFailures++
                        }
                    }

                    if (consecutiveTunnelCheckFailures >= 2 && _connectionPhase.value != ConnectionPhase.DEGRADED) {
                        _connectionPhase.value = ConnectionPhase.DEGRADED
                        _tunnelConnectivityState.value = TunnelConnectivityState.CONNECTION_DEGRADED
                        _connectionDetailMessage.value = report.diagnosticMessage
                        val level = if (!report.dnsWorking && report.proxyTransportWorking) LogLevel.DNS else LogLevel.WARN
                        appendLog(
                            level,
                            "Verifier",
                            "${report.diagnosticMessage} • Initiating background node health recovery"
                        )
                    }
                }
            }
        }

        // Background health & recovery loop with adaptive backoff (45s..90s)
        healthRecoveryLoopJob?.cancel()
        healthRecoveryLoopJob = viewModelScope.launch {
            var backoffMultiplier = 1
            while (isActive && _isTunnelConnected.value) {
                val sleepMs = (45_000L * backoffMultiplier).coerceAtMost(90_000L)
                delay(sleepMs)
                if (!_isTunnelConnected.value) break

                val allNodes = repository.getAllServersOnce()
                if (allNodes.isEmpty()) continue

                val isDegraded = _connectionPhase.value == ConnectionPhase.DEGRADED
                val nodesToProbe = if (isDegraded) {
                    // When internet is degraded, probe all nodes to find the best working ones
                    allNodes.filter { !it.isSimulatedDown }
                } else {
                    // When internet is working smoothly, ONLY retest UNHEALTHY or DEGRADED nodes in the background!
                    allNodes.filter {
                        !it.isSimulatedDown &&
                            (it.healthStatusEnum == NodeHealthStatus.UNHEALTHY ||
                                it.healthStatusEnum == NodeHealthStatus.DEGRADED)
                    }
                }

                if (nodesToProbe.isEmpty()) {
                    backoffMultiplier = 2
                    continue
                }

                appendLog(
                    LogLevel.HEALTH,
                    "RecoveryMonitor",
                    "Background check on ${nodesToProbe.size} node(s) (${
                        nodesToProbe.count { it.healthStatusEnum == NodeHealthStatus.UNHEALTHY }
                    } unhealthy, ${
                        nodesToProbe.count { it.healthStatusEnum == NodeHealthStatus.DEGRADED }
                    } degraded)..."
                )

                // Use lightweight TCP handshake probes while the main Xray tunnel is running to avoid Go engine contention
                val results = runHealthCheckAndDynamicWeightsInternal(
                    servers = nodesToProbe,
                    silent = true,
                    lightweightTcpOnly = true
                )

                val recoveredNodes = results.filter { it.recoveredFromUnhealthy }
                val newlyUnhealthyNodes = results.filter { it.transitionedToUnhealthy }

                if (recoveredNodes.isNotEmpty() || newlyUnhealthyNodes.isNotEmpty()) {
                    backoffMultiplier = 1
                    val reason = when {
                        recoveredNodes.isNotEmpty() ->
                            "Restored ${recoveredNodes.size} recovered node(s): ${recoveredNodes.joinToString { it.remarks }}"
                        else ->
                            "Excluded ${newlyUnhealthyNodes.size} unresponsive node(s)"
                    }
                    triggerHotReloadIfNeeded(forceReload = false, reason = reason)
                } else {
                    backoffMultiplier = 2
                }
            }
        }
    }

    /**
     * Public action to test all imported nodes and recompute dynamic weights.
     */
    fun probeAllNodes(silent: Boolean = false) {
        viewModelScope.launch {
            runHealthCheckAndDynamicWeights(
                silent = silent,
                lightweightTcpOnly = _isTunnelConnected.value
            )
            if (_isTunnelConnected.value) {
                triggerHotReloadIfNeeded(forceReload = false, reason = "Manual node health check")
            }
        }
    }

    private suspend fun runHealthCheckAndDynamicWeights(
        silent: Boolean,
        lightweightTcpOnly: Boolean
    ): List<NodeHealthCheckResult> {
        val servers = repository.getAllServersOnce()
        return runHealthCheckAndDynamicWeightsInternal(servers, silent, lightweightTcpOnly)
    }

    private suspend fun runHealthCheckAndDynamicWeightsInternal(
        servers: List<ServerProfileEntity>,
        silent: Boolean,
        lightweightTcpOnly: Boolean
    ): List<NodeHealthCheckResult> = probeMutex.withLock {
        if (servers.isEmpty()) return@withLock emptyList()
        _isProbingAll.value = true
        val cfg = _balancerConfig.value
        val probeContext = if (lightweightTcpOnly) null else appContext

        if (!silent) {
            appendLog(
                LogLevel.HEALTH,
                "HealthMonitor",
                "Starting health check across ${servers.size} node(s) (adaptive timeout ${cfg.failoverThresholdMs}ms, failure threshold ${cfg.consecutiveFailuresThreshold})..."
            )
        }

        val results = withContext(Dispatchers.IO) {
            servers.map { server ->
                async {
                    val evaluation = ObservatoryAndBalancerEngine.evaluateNodeHealth(
                        server = server,
                        timeoutMs = cfg.failoverThresholdMs,
                        context = probeContext,
                        config = cfg
                    )
                    repository.updateServerHealthMetrics(
                        id = server.id,
                        latencyMs = evaluation.latencyMs,
                        emaLatencyMs = evaluation.emaLatencyMs,
                        throughputKbps = evaluation.measuredThroughputKbps,
                        healthStatus = evaluation.newStatus,
                        failureReason = evaluation.failureReason,
                        computedWeight = evaluation.computedWeight,
                        successCount = evaluation.successCount,
                        failureCount = evaluation.failureCount,
                        consecutiveFailures = evaluation.consecutiveFailures,
                        consecutiveSuccesses = evaluation.consecutiveSuccesses,
                        stabilityScore = evaluation.stabilityScore,
                        statusChangedAt = evaluation.statusChangedAt
                    )
                    evaluation
                }
            }.awaitAll()
        }

        results.forEach { res ->
            when {
                res.recoveredFromUnhealthy -> {
                    appendLog(
                        LogLevel.RECOVERY,
                        res.remarks,
                        res.reasonSummary
                    )
                }
                res.transitionedToUnhealthy -> {
                    _failoverEventsCount.update { it + 1 }
                    appendLog(
                        LogLevel.FAILOVER,
                        res.remarks,
                        res.reasonSummary
                    )
                }
                res.isTransientFailure -> {
                    appendLog(
                        LogLevel.WARN,
                        res.remarks,
                        res.reasonSummary
                    )
                }
                !silent && res.isHealthy -> {
                    appendLog(
                        LogLevel.HEALTH,
                        res.remarks,
                        res.reasonSummary
                    )
                }
                !silent && !res.isHealthy -> {
                    appendLog(
                        LogLevel.WARN,
                        res.remarks,
                        res.reasonSummary
                    )
                }
                silent && res.weightChangedSignificantly -> {
                    appendLog(
                        LogLevel.WEIGHTS,
                        res.remarks,
                        "Dynamic weight adjusted ${res.previousWeight}/10 → ${res.computedWeight}/10 (EMA ${res.emaLatencyMs}ms • Stability ${res.stabilityScore.toInt()}%)"
                    )
                }
            }
        }

        val allNow = repository.getAllServersOnce()
        val eligibleCount = allNow.count { !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY }
        val bestRtt = allNow.mapNotNull { it.effectiveLatencyMs.takeIf { r -> r > 0 } }.minOrNull() ?: -1L
        if (!silent) {
            appendLog(
                LogLevel.WEIGHTS,
                "BalancerPool",
                "Health evaluation complete: $eligibleCount/${allNow.size} nodes eligible for balancing${
                    if (bestRtt > 0) " • Best EMA RTT: ${bestRtt}ms" else ""
                }"
            )
        }

        _isProbingAll.value = false
        results
    }

    fun probeSingleNode(server: ServerProfileEntity) {
        viewModelScope.launch {
            val cfg = _balancerConfig.value
            val evaluation = ObservatoryAndBalancerEngine.evaluateNodeHealth(
                server = server,
                timeoutMs = cfg.failoverThresholdMs,
                context = if (_isTunnelConnected.value) null else appContext,
                config = cfg
            )
            repository.updateServerHealthMetrics(
                id = server.id,
                latencyMs = evaluation.latencyMs,
                emaLatencyMs = evaluation.emaLatencyMs,
                throughputKbps = evaluation.measuredThroughputKbps,
                healthStatus = evaluation.newStatus,
                failureReason = evaluation.failureReason,
                computedWeight = evaluation.computedWeight,
                successCount = evaluation.successCount,
                failureCount = evaluation.failureCount,
                consecutiveFailures = evaluation.consecutiveFailures,
                consecutiveSuccesses = evaluation.consecutiveSuccesses,
                stabilityScore = evaluation.stabilityScore,
                statusChangedAt = evaluation.statusChangedAt
            )
            appendLog(
                if (evaluation.isHealthy) LogLevel.HEALTH else LogLevel.WARN,
                server.remarks,
                evaluation.reasonSummary
            )
            if (_isTunnelConnected.value && (evaluation.recoveredFromUnhealthy || evaluation.transitionedToUnhealthy)) {
                triggerHotReloadIfNeeded(forceReload = false, reason = "Single node status change")
            }
        }
    }

    fun toggleSimulateNodeDrop(server: ServerProfileEntity) {
        viewModelScope.launch {
            val willBeDown = !server.isSimulatedDown
            if (willBeDown) {
                repository.setServerSimulatedDown(server.id, isDown = true, latencyMs = -2L)
                _failoverEventsCount.update { it + 1 }

                val remainingHealthy = uiState.value.poolServers
                    .filter { it.id != server.id && !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY }

                if (remainingHealthy.isNotEmpty()) {
                    val targetNames = remainingHealthy.take(2).joinToString(" & ") { it.remarks }
                    val msg = "Node [${server.remarks}] marked unavailable → New connections routed across $targetNames without dropping tunnel"
                    _lastFailoverBanner.value = msg
                    appendLog(LogLevel.FAILOVER, "FailoverEngine", msg)
                } else {
                    val msg = "Warning: [${server.remarks}] dropped and no healthy backup nodes remain in pool!"
                    _lastFailoverBanner.value = msg
                    appendLog(LogLevel.ERROR, "FailoverEngine", msg)
                }
            } else {
                val evaluation = ObservatoryAndBalancerEngine.evaluateNodeHealth(
                    server = server.copy(isSimulatedDown = false, consecutiveFailures = 0),
                    timeoutMs = _balancerConfig.value.failoverThresholdMs,
                    context = null,
                    config = _balancerConfig.value
                )
                val restoredRtt = if (evaluation.latencyMs > 0) evaluation.latencyMs else 95L
                repository.setServerSimulatedDown(server.id, isDown = false, latencyMs = restoredRtt)
                val msg = "Node [${server.remarks}] recovered (${restoredRtt}ms) → Rejoined active balancer pool"
                _lastFailoverBanner.value = msg
                appendLog(LogLevel.RECOVERY, "RecoveryMonitor", msg)
            }
            triggerHotReloadIfNeeded(forceReload = true, reason = "Simulated node state change")
        }
    }

    fun selectBalancerStrategy(strategy: BalancerStrategy) {
        _balancerConfig.update { it.copy(strategy = strategy) }
        appendLog(
            LogLevel.BALANCER,
            "Balancer",
            "Switched load-balancing strategy to [${strategy.xrayKey}] (${strategy.title})"
        )
        triggerHotReloadIfNeeded(forceReload = true, reason = "Strategy changed")
    }

    fun importShareLinks(rawLinks: String, groupName: String = "Subscription") {
        viewModelScope.launch {
            val parsed = V2RayConfigEngine.parseInputLinks(rawLinks, groupName.ifBlank { "Subscription" })
            if (parsed.isEmpty()) {
                showStatusBanner("Could not parse any valid proxy URIs.")
                return@launch
            }
            repository.replaceSubscriptionServers(parsed)
            appendLog(
                LogLevel.INFO,
                "Subscription",
                "Imported ${parsed.size} proxy node(s) into local storage."
            )
            showStatusBanner("Imported ${parsed.size} proxy node(s).")
            runHealthCheckAndDynamicWeights(silent = false, lightweightTcpOnly = _isTunnelConnected.value)
            if (_isTunnelConnected.value) {
                triggerHotReloadIfNeeded(forceReload = true, reason = "Imported share links")
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun dismissStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun dismissFailoverBanner() {
        _lastFailoverBanner.value = null
    }

    private fun showStatusBanner(msg: String) {
        _statusBannerMessage.value = ObservatoryAndBalancerEngine.redactSensitiveText(msg)
    }

    /**
     * Appends a redacted diagnostic log entry while coalescing consecutive identical messages
     * to prevent log flooding.
     */
    private fun appendLog(level: LogLevel, tag: String, message: String) {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val safeTag = ObservatoryAndBalancerEngine.redactSensitiveText(tag)
        val safeMsg = ObservatoryAndBalancerEngine.redactSensitiveText(message)

        _logs.update { current ->
            val head = current.firstOrNull()
            if (head != null && head.level == level && head.tag == safeTag && head.message == safeMsg) {
                val updatedHead = head.copy(
                    timestamp = timeFmt,
                    repeatCount = head.repeatCount + 1
                )
                listOf(updatedHead) + current.drop(1)
            } else {
                val entry = CoreLogEntry(
                    id = logIdCounter.getAndIncrement(),
                    timestamp = timeFmt,
                    level = level,
                    tag = safeTag,
                    message = safeMsg,
                    repeatCount = 1
                )
                (listOf(entry) + current).take(300)
            }
        }
    }

    private data class Quintuple<A, B, C, D, E>(
        val servers: A,
        val rules: B,
        val config: C,
        val connected: D,
        val probing: E
    )

    private data class TelemetryTuple(
        val httpPing: Long,
        val downBps: Long,
        val upBps: Long,
        val totalDown: Long,
        val totalUp: Long
    )

    private data class MetaTuple(
        val uptime: Long,
        val failovers: Int,
        val banner: String?,
        val history: List<Float>,
        val statusMsg: String?
    )

    private data class ConnectionUiTuple(
        val subInput: String,
        val savedSub: String,
        val phase: ConnectionPhase,
        val connectivityState: TunnelConnectivityState,
        val detailMsg: String,
        val importing: Boolean
    )

    class Factory(
        private val repository: BalancerRepository,
        private val appContext: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BalancerViewModel(repository, appContext?.applicationContext) as T
        }
    }
}
