package com.example

import com.example.core.ObservatoryAndBalancerEngine
import com.example.core.ProbeRawMeasurement
import com.example.data.BalancerConfigState
import com.example.data.BalancerStrategy
import com.example.data.NodeHealthStatus
import com.example.data.ProbeFailureReason
import com.example.data.ServerProfileEntity
import com.example.data.V2RayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private fun createTestNodes(count: Int = 9): List<ServerProfileEntity> {
        return (1..count).map { idx ->
            ServerProfileEntity(
                id = idx.toLong(),
                remarks = "Node-$idx",
                protocol = V2RayProtocol.VLESS.name,
                address = "node$idx.balancer.example.test",
                port = 443,
                uuidOrPassword = "test-uuid-$idx",
                network = "xhttp",
                security = "tls",
                inBalancerPool = true,
                weight = 5,
                lastLatencyMs = 110L,
                emaLatencyMs = 110L,
                healthStatus = NodeHealthStatus.HEALTHY.name,
                successCount = 5,
                failureCount = 0,
                consecutiveFailures = 0,
                consecutiveSuccesses = 5,
                stabilityScore = 98f
            )
        }
    }

    @Test
    fun scenario1_allNineNodesHealthy_allIncludedAndWeighted() {
        val nodes = createTestNodes(9)
        val evaluated = nodes.mapIndexed { idx, node ->
            val rtt = 75L + (idx * 30L)
            val res = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
                server = node,
                measurement = ProbeRawMeasurement(
                    latencyMs = rtt,
                    tcpHandshakeMs = rtt - 15L,
                    failureReason = ProbeFailureReason.NONE,
                    testDurationMs = rtt
                )
            )
            node.copy(
                lastLatencyMs = res.latencyMs,
                emaLatencyMs = res.emaLatencyMs,
                healthStatus = res.newStatus.name,
                weight = res.computedWeight
            )
        }

        assertEquals(9, evaluated.count { it.isHealthy })
        val snapshots = ObservatoryAndBalancerEngine.calculatePoolDistribution(
            poolServers = evaluated,
            config = BalancerConfigState(strategy = BalancerStrategy.LEAST_LOAD),
            isTunnelConnected = true,
            aggregateDownloadBps = 18_000_000L,
            aggregateUploadBps = 4_000_000L
        )
        assertEquals(9, snapshots.count { it.isHealthy })
        assertEquals(100.0, snapshots.sumOf { it.sharePercentage.toDouble() }, 0.5)
        assertTrue(snapshots.first().sharePercentage > snapshots.last().sharePercentage)
    }

    @Test
    fun scenario2_slowNodeWithinAdaptiveWindow_succeedsAndRemainsEligible() {
        val node = createTestNodes(1).first().copy(emaLatencyMs = 950L)
        val slowMeasurement = ProbeRawMeasurement(
            latencyMs = 2200L,
            tcpHandshakeMs = 450L,
            failureReason = ProbeFailureReason.NONE,
            testDurationMs = 2210L
        )

        val result = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
            server = node,
            measurement = slowMeasurement
        )

        assertTrue("Slow responding node must remain eligible (not UNHEALTHY)", result.isHealthy)
        assertFalse(result.transitionedToUnhealthy)
        assertTrue("Slow node should receive lower weight (2..5)", result.computedWeight in 2..5)
    }

    @Test
    fun scenario3_singleFailedHealthCheckThenRecovery_neverEvictsNodeOnSingleFailure() {
        val healthyNode = createTestNodes(1).first().copy(weight = 8, emaLatencyMs = 95L)

        // 1st transient timeout
        val afterFirstFail = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
            server = healthyNode,
            measurement = ProbeRawMeasurement(
                latencyMs = -2L,
                tcpHandshakeMs = -2L,
                failureReason = ProbeFailureReason.TIMEOUT,
                testDurationMs = 3200L
            )
        )

        assertTrue("Single failure must be classified as transient", afterFirstFail.isTransientFailure)
        assertEquals("Node must degrade rather than become UNHEALTHY on 1 failure", NodeHealthStatus.DEGRADED, afterFirstFail.newStatus)
        assertTrue("Node remains eligible in pool on 1 failure", afterFirstFail.isHealthy)
        assertEquals(1, afterFirstFail.consecutiveFailures)
        assertTrue("Weight is stepped down on transient failure", afterFirstFail.computedWeight < healthyNode.weight)

        // Subsequent check succeeds -> recovers to HEALTHY
        val degradedEntity = healthyNode.copy(
            healthStatus = afterFirstFail.newStatus.name,
            weight = afterFirstFail.computedWeight,
            emaLatencyMs = afterFirstFail.emaLatencyMs,
            consecutiveFailures = afterFirstFail.consecutiveFailures,
            consecutiveSuccesses = 0,
            failureCount = afterFirstFail.failureCount
        )

        val afterRecovery = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
            server = degradedEntity,
            measurement = ProbeRawMeasurement(
                latencyMs = 105L,
                tcpHandshakeMs = 70L,
                failureReason = ProbeFailureReason.NONE,
                testDurationMs = 110L
            )
        )

        assertEquals(NodeHealthStatus.HEALTHY, afterRecovery.newStatus)
        assertEquals(0, afterRecovery.consecutiveFailures)
    }

    @Test
    fun scenario4_and_5_persistentFailureExcludesNode_andSubsequentRecoveryRestoresNode() {
        var node = createTestNodes(1).first()

        // 3 consecutive timeouts required to confirm UNHEALTHY
        for (strike in 1..3) {
            val res = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
                server = node,
                measurement = ProbeRawMeasurement(
                    latencyMs = -2L,
                    tcpHandshakeMs = -2L,
                    failureReason = ProbeFailureReason.TIMEOUT,
                    testDurationMs = 3200L
                )
            )
            node = node.copy(
                lastLatencyMs = res.latencyMs,
                emaLatencyMs = res.emaLatencyMs,
                healthStatus = res.newStatus.name,
                weight = res.computedWeight,
                consecutiveFailures = res.consecutiveFailures,
                consecutiveSuccesses = res.consecutiveSuccesses,
                failureCount = res.failureCount,
                stabilityScore = res.stabilityScore
            )
            if (strike < 3) {
                assertEquals(NodeHealthStatus.DEGRADED, res.newStatus)
            } else {
                assertEquals(NodeHealthStatus.UNHEALTHY, res.newStatus)
                assertTrue(res.transitionedToUnhealthy)
            }
        }

        // Now node recovers in background retest (Scenario 5)
        val recoveryRes = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
            server = node,
            measurement = ProbeRawMeasurement(
                latencyMs = 120L,
                tcpHandshakeMs = 80L,
                failureReason = ProbeFailureReason.NONE,
                testDurationMs = 125L
            )
        )

        assertTrue("Node should be flagged as recoveredFromUnhealthy", recoveryRes.recoveredFromUnhealthy)
        assertTrue(recoveryRes.isHealthy)
        assertEquals(0, recoveryRes.consecutiveFailures)
        assertTrue("Recovered node starts with ramped weight (3..6)", recoveryRes.computedWeight in 3..6)
    }

    @Test
    fun scenario9_multipleNodesFail_remainingHealthyNodesCarryTraffic() {
        val nodes = createTestNodes(9)
        val mixedNodes = nodes.mapIndexed { idx, srv ->
            if (idx < 5) {
                srv.copy(
                    lastLatencyMs = -2L,
                    healthStatus = NodeHealthStatus.UNHEALTHY.name,
                    consecutiveFailures = 3,
                    weight = 1
                )
            } else {
                srv
            }
        }
        val snapshots = ObservatoryAndBalancerEngine.calculatePoolDistribution(
            poolServers = mixedNodes,
            config = BalancerConfigState(),
            isTunnelConnected = true,
            aggregateDownloadBps = 8_000_000L,
            aggregateUploadBps = 2_000_000L
        )
        assertEquals(4, snapshots.count { it.isHealthy })
        assertEquals(100.0, snapshots.filter { it.isHealthy }.sumOf { it.sharePercentage.toDouble() }, 0.5)
    }

    @Test
    fun scenario11_weightHysteresis_suppressesMinorLatencyJitter() {
        val stableNode = createTestNodes(1).first().copy(
            weight = 8,
            emaLatencyMs = 150L,
            healthStatus = NodeHealthStatus.HEALTHY.name,
            successCount = 10
        )

        // Slight latency fluctuation that would shift raw weight by only 1 point (8 -> 9)
        val jitterResult = ObservatoryAndBalancerEngine.applyHealthDecisionPolicy(
            server = stableNode,
            measurement = ProbeRawMeasurement(
                latencyMs = 135L,
                tcpHandshakeMs = 95L,
                failureReason = ProbeFailureReason.NONE,
                testDurationMs = 140L
            ),
            config = BalancerConfigState(weightChangeHysteresis = 2)
        )

        assertEquals("1-point weight jitter should be suppressed by hysteresis", 8, jitterResult.computedWeight)
        assertFalse(jitterResult.weightChangedSignificantly)
    }
}
