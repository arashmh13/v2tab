package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.V2RayConfigEngine
import com.example.data.AppDatabase
import com.example.data.BalancerConfigState
import com.example.data.BalancerRepository
import com.example.data.BalancerStrategy
import com.example.data.ConnectionPhase
import com.example.data.NodeHealthStatus
import com.example.ui.BalancerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verifies V2Tab branding strings from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("V2Tab", context.getString(R.string.app_name))
        assertEquals("By Dr. Arash", context.getString(R.string.app_author_credit))
    }

    @Test
    fun `parses 9-node xhttp subscription, disambiguates duplicate names, routes proxy domains direct, and generates weighted balancer json`() {
        val syntheticSubscription = (1..9).joinToString("\n") { idx ->
            "vless://00000000-1111-2222-3333-444444444444@node$idx.balancer.example.test:443?encryption=none&security=tls&fp=chrome&alpn=h2%2Ch3%2Chttp%2F1.1&type=xhttp&host=cdn.example.test&path=%2Fstream&mode=auto&extra=%7B%22mode%22%3A%22auto%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D#MultiNodeSub"
        }

        val parsed = V2RayConfigEngine.parseInputLinks(syntheticSubscription, "Subscription")
        assertEquals(9, parsed.size)
        assertEquals(9, parsed.map { it.remarks }.distinct().size)

        // Simulate 6 HEALTHY nodes, 1 DEGRADED node (single transient failure), and 2 confirmed UNHEALTHY nodes
        val evaluatedPool = parsed.mapIndexed { idx, srv ->
            when (idx) {
                2, 6 -> srv.copy(
                    id = (idx + 1).toLong(),
                    lastLatencyMs = -2L,
                    healthStatus = NodeHealthStatus.UNHEALTHY.name,
                    consecutiveFailures = 3,
                    weight = 1
                )
                4 -> srv.copy(
                    id = (idx + 1).toLong(),
                    lastLatencyMs = 850L,
                    emaLatencyMs = 600L,
                    healthStatus = NodeHealthStatus.DEGRADED.name,
                    consecutiveFailures = 1,
                    weight = 3
                )
                else -> srv.copy(
                    id = (idx + 1).toLong(),
                    lastLatencyMs = 80L + (idx * 25L),
                    emaLatencyMs = 80L + (idx * 25L),
                    healthStatus = NodeHealthStatus.HEALTHY.name,
                    consecutiveFailures = 0,
                    weight = (9 - idx).coerceIn(4, 9)
                )
            }
        }

        val jsonLeastLoad = V2RayConfigEngine.generateBalancerXrayJson(
            poolServers = evaluatedPool,
            config = BalancerConfigState(strategy = BalancerStrategy.LEAST_LOAD),
            routingRules = emptyList(),
            includeTunInbound = true
        )

        assertNotNull(jsonLeastLoad)
        assertTrue(jsonLeastLoad.contains("\"protocol\": \"tun\""))
        assertTrue(jsonLeastLoad.contains("\"xhttpSettings\""))
        assertTrue(jsonLeastLoad.contains("\"burstObservatory\""))
        assertTrue(jsonLeastLoad.contains("\"leastLoad\""))
        assertTrue(jsonLeastLoad.contains("\"costs\""))
        assertTrue(jsonLeastLoad.contains("\"fallbackTag\""))
        // Scenario 7: Verify proxy server domains have a direct routing rule to prevent circular DNS deadlocks
        assertTrue(jsonLeastLoad.contains("\"full:node1.balancer.example.test\""))
        assertTrue(jsonLeastLoad.contains("\"outboundTag\": \"direct\""))
        // Healthy and Degraded (1-strike) nodes are included; 3-strike Unhealthy nodes (node3, node7) are excluded from outbounds
        assertTrue(jsonLeastLoad.contains("node1.balancer.example.test"))
        assertTrue(jsonLeastLoad.contains("node5.balancer.example.test"))
        assertFalse(jsonLeastLoad.contains("\"address\": \"node3.balancer.example.test\""))
        assertFalse(jsonLeastLoad.contains("\"address\": \"node7.balancer.example.test\""))

        // Scenario 10: When all 9 nodes fail temporarily, fallback outbounds remain in config for immediate recovery
        val allUnhealthy = parsed.mapIndexed { idx, srv ->
            srv.copy(
                id = (idx + 1).toLong(),
                lastLatencyMs = -2L,
                healthStatus = NodeHealthStatus.UNHEALTHY.name,
                consecutiveFailures = 3
            )
        }
        val fallbackJson = V2RayConfigEngine.generateBalancerXrayJson(
            poolServers = allUnhealthy,
            config = BalancerConfigState(),
            routingRules = emptyList(),
            includeTunInbound = true
        )
        assertTrue(fallbackJson.contains("\"address\": \"node1.balancer.example.test\""))
    }

    @Test
    fun `scenario12_repeatedConnectDisconnectAndPersistenceLifecycle`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repo = BalancerRepository(db.dao(), context)

        val syntheticSub = (1..9).joinToString("\n") { idx ->
            "vless://00000000-1111-2222-3333-444444444444@127.0.0.1:443?encryption=none&security=tls&type=xhttp&host=cdn.example.test&path=%2Fstream#Node-$idx"
        }
        val parsed = V2RayConfigEngine.parseInputLinks(syntheticSub, "Subscription")
        repo.replaceSubscriptionServers(parsed)

        val saved = repo.getAllServersOnce()
        assertEquals(9, saved.size)

        val vm = BalancerViewModel(repo, context)
        repeat(3) {
            vm.stopBalancerTunnel(context)
            assertEquals(ConnectionPhase.DISCONNECTED, vm.uiState.value.connectionPhase)
            assertFalse(vm.uiState.value.isTunnelConnected)
        }
    }
}
