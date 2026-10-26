package com.example.data

import android.content.Context
import com.example.core.V2RayConfigEngine
import kotlinx.coroutines.flow.Flow

class BalancerRepository(
    private val dao: V2RayBalancerDao,
    context: Context? = null
) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val serversFlow: Flow<List<ServerProfileEntity>> = dao.observeAllServers()
    val routingRulesFlow: Flow<List<RoutingRuleEntity>> = dao.observeRoutingRules()

    suspend fun ensureInitialSeedData() {
        val currentRules = dao.getRoutingRulesOnce()
        if (currentRules.isEmpty()) {
            val defaultRules = listOf(
                RoutingRuleEntity(
                    remarks = "Bypass Private LAN CIDR",
                    domainOrIpPattern = "192.168.0.0/16",
                    targetTag = "direct",
                    enabled = true,
                    priority = 1
                )
            )
            dao.insertRoutingRules(defaultRules)
        }

        // Restore backed-up subscription nodes if Room DB was cleared by a schema migration
        val currentServers = dao.getAllServersOnce()
        if (currentServers.isEmpty()) {
            val backedUpPayload = prefs?.getString(KEY_SAVED_NODES_BACKUP, "")?.trim().orEmpty()
            if (backedUpPayload.isNotEmpty()) {
                val restored = V2RayConfigEngine.parseInputLinks(backedUpPayload, "Subscription")
                if (restored.isNotEmpty()) {
                    dao.insertServers(restored)
                }
            }
        }
    }

    fun getSavedSubscriptionUrl(): String {
        return prefs?.getString(KEY_SUBSCRIPTION_URL, "")?.trim().orEmpty()
    }

    fun saveSubscriptionUrl(url: String) {
        prefs?.edit()?.putString(KEY_SUBSCRIPTION_URL, url.trim())?.apply()
    }

    suspend fun getAllServersOnce(): List<ServerProfileEntity> = dao.getAllServersOnce()

    suspend fun replaceSubscriptionServers(servers: List<ServerProfileEntity>) {
        dao.replaceAllServers(servers)
        val serializedUris = servers.joinToString("\n") { V2RayConfigEngine.toShareUri(it) }
        prefs?.edit()?.putString(KEY_SAVED_NODES_BACKUP, serializedUris)?.apply()
    }

    suspend fun addServer(server: ServerProfileEntity): Long = dao.insertServer(server)

    suspend fun addServers(servers: List<ServerProfileEntity>) = dao.insertServers(servers)

    suspend fun updateServer(server: ServerProfileEntity) = dao.updateServer(server)

    suspend fun deleteServer(id: Long) = dao.deleteServerById(id)

    suspend fun setServerInBalancerPool(id: Long, inPool: Boolean) =
        dao.setServerInBalancerPool(id, inPool)

    suspend fun updateServerWeight(id: Long, weight: Int) =
        dao.updateServerWeight(id, weight.coerceIn(1, 10))

    suspend fun updateServerLatency(id: Long, latencyMs: Long) =
        dao.updateServerLatency(id, latencyMs)

    suspend fun updateServerHealthMetrics(
        id: Long,
        latencyMs: Long,
        emaLatencyMs: Long,
        throughputKbps: Long,
        healthStatus: NodeHealthStatus,
        failureReason: ProbeFailureReason,
        computedWeight: Int,
        successCount: Int,
        failureCount: Int,
        consecutiveFailures: Int,
        consecutiveSuccesses: Int,
        stabilityScore: Float,
        checkedAt: Long = System.currentTimeMillis(),
        statusChangedAt: Long = System.currentTimeMillis()
    ) {
        dao.updateServerHealthMetrics(
            id = id,
            latencyMs = latencyMs,
            emaLatencyMs = emaLatencyMs,
            throughputKbps = throughputKbps,
            healthStatus = healthStatus.name,
            failureReason = failureReason.name,
            computedWeight = computedWeight.coerceIn(1, 10),
            successCount = successCount,
            failureCount = failureCount,
            consecutiveFailures = consecutiveFailures,
            consecutiveSuccesses = consecutiveSuccesses,
            stabilityScore = stabilityScore.coerceIn(0f, 100f),
            checkedAt = checkedAt,
            statusChangedAt = statusChangedAt
        )
    }

    suspend fun setServerSimulatedDown(id: Long, isDown: Boolean, latencyMs: Long) {
        val status = if (isDown || latencyMs == -2L) NodeHealthStatus.UNHEALTHY else NodeHealthStatus.HEALTHY
        val reason = if (isDown) ProbeFailureReason.SIMULATED_DOWN else ProbeFailureReason.NONE
        val consecutive = if (isDown) 3 else 0
        dao.setServerSimulatedDown(
            id = id,
            isDown = isDown,
            latencyMs = latencyMs,
            healthStatus = status.name,
            failureReason = reason.name,
            consecutiveFailures = consecutive
        )
    }

    suspend fun incrementServerTraffic(id: Long, deltaUp: Long, deltaDown: Long) =
        dao.incrementServerTraffic(id, deltaUp, deltaDown)

    suspend fun addRoutingRule(rule: RoutingRuleEntity) = dao.insertRoutingRule(rule)

    suspend fun updateRoutingRule(rule: RoutingRuleEntity) = dao.updateRoutingRule(rule)

    suspend fun deleteRoutingRule(id: Long) = dao.deleteRoutingRuleById(id)

    companion object {
        private const val PREFS_NAME = "v2tab_preferences"
        private const val KEY_SUBSCRIPTION_URL = "saved_subscription_url"
        private const val KEY_SAVED_NODES_BACKUP = "saved_nodes_uri_backup"
    }
}
