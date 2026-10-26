package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.XrayCoreRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SuppressLint("VpnServicePolicy")
class BalancerVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        XrayCoreRunner.initCoreEnv(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    tunnelMutex.withLock {
                        stopBalancerTunnelInternal()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                val updatedJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val strategyName = intent.getStringExtra(EXTRA_STRATEGY_NAME) ?: "Dynamic Weighted"
                val nodeCount = intent.getIntExtra(EXTRA_NODE_COUNT, 3)
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, _boundSocksPort.value)
                if (updatedJson.isNotBlank() && _isRunning.value) {
                    startForegroundNotification(strategyName, nodeCount)
                    serviceScope.launch {
                        tunnelMutex.withLock {
                            reloadBalancerTunnelSafe(updatedJson, socksPort, nodeCount)
                        }
                    }
                }
                return START_STICKY
            }
            else -> {
                val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val strategyName = intent?.getStringExtra(EXTRA_STRATEGY_NAME) ?: "Dynamic Weighted"
                val nodeCount = intent?.getIntExtra(EXTRA_NODE_COUNT, 3) ?: 3
                val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 10808) ?: 10808
                startForegroundNotification(strategyName, nodeCount)
                serviceScope.launch {
                    tunnelMutex.withLock {
                        startBalancerTunnelInternal(configJson, socksPort, nodeCount)
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        serviceScope.launch {
            tunnelMutex.withLock {
                stopBalancerTunnelInternal()
            }
        }
        super.onRevoke()
    }

    private fun startForegroundNotification(strategyName: String, nodeCount: Int) {
        val channelId = "v2ray_balancer_tunnel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val channel = NotificationChannel(
                channelId,
                "V2Tab Multi-Node Balancer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active V2Tab multi-node load-balanced VPN tunnel"
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("V2Tab Connected ($nodeCount Healthy Nodes)")
            .setContentText("By Dr. Arash • $strategyName")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun establishFreshTunInterface(nodeCount: Int): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("V2Tab-Balancer-$nodeCount-Nodes")
                .setMtu(1500)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            // Critical: Exclude this app's own package so Xray-core's outbound sockets
            // go directly to the internet and never loop back into the TUN interface!
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.establish()
        } catch (_: Exception) {
            null
        }
    }

    private fun startBalancerTunnelInternal(configJson: String, socksPort: Int, nodeCount: Int) {
        _lastServiceError.value = null
        _boundSocksPort.value = socksPort

        try {
            XrayCoreRunner.stopCoreLoop()
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null

            val establishedTun = establishFreshTunInterface(nodeCount)
            vpnInterface = establishedTun
            val tunFd = establishedTun?.fd ?: 0
            _isTunEstablished.value = establishedTun != null

            if (configJson.isNotBlank()) {
                val startResult = XrayCoreRunner.startCoreLoop(
                    context = applicationContext,
                    configJson = configJson,
                    tunFd = tunFd
                )
                startResult.fold(
                    onSuccess = {
                        _isRunning.value = true
                    },
                    onFailure = { err ->
                        _lastServiceError.value = err.message ?: "Xray-core failed to start"
                        _isRunning.value = false
                        stopBalancerTunnelInternal()
                    }
                )
            } else {
                _isRunning.value = true
            }
        } catch (e: Exception) {
            _lastServiceError.value = e.message ?: "VPN initialization error"
            _isRunning.value = false
        }
    }

    /**
     * Safely reloads the Xray-core configuration by creating a fresh TUN file descriptor
     * before swapping the Xray loop, preventing stale/closed TUN FD blackholes.
     */
    private fun reloadBalancerTunnelSafe(updatedJson: String, socksPort: Int, nodeCount: Int) {
        if (!_isRunning.value || updatedJson.isBlank()) return
        _boundSocksPort.value = socksPort
        try {
            // Establish the replacement TUN interface BEFORE closing the old one so routing doesn't drop to raw ISP
            val oldTun = vpnInterface
            XrayCoreRunner.stopCoreLoop()
            val newTun = establishFreshTunInterface(nodeCount)
            vpnInterface = newTun
            try {
                oldTun?.close()
            } catch (_: Exception) {
            }

            val tunFd = newTun?.fd ?: 0
            _isTunEstablished.value = newTun != null

            val reloadResult = XrayCoreRunner.startCoreLoop(
                context = applicationContext,
                configJson = updatedJson,
                tunFd = tunFd
            )
            reloadResult.onFailure { err ->
                _lastServiceError.value = err.message ?: "Failed to reload Xray-core"
            }
        } catch (e: Exception) {
            _lastServiceError.value = e.message ?: "Failed to reload tunnel"
        }
    }

    private fun stopBalancerTunnelInternal() {
        _isRunning.value = false
        _isTunEstablished.value = false
        XrayCoreRunner.stopCoreLoop()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        try {
            stopSelf()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (_isRunning.value) {
            stopBalancerTunnelInternal()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.service.START_BALANCER"
        const val ACTION_RELOAD = "com.example.service.RELOAD_BALANCER"
        const val ACTION_STOP = "com.example.service.STOP_BALANCER"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_STRATEGY_NAME = "extra_strategy_name"
        const val EXTRA_NODE_COUNT = "extra_node_count"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"
        private const val NOTIFICATION_ID = 10808

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isTunEstablished = MutableStateFlow(false)
        val isTunEstablished: StateFlow<Boolean> = _isTunEstablished.asStateFlow()

        private val _boundSocksPort = MutableStateFlow(10808)
        val boundSocksPort: StateFlow<Int> = _boundSocksPort.asStateFlow()

        private val _lastServiceError = MutableStateFlow<String?>(null)
        val lastServiceError: StateFlow<String?> = _lastServiceError.asStateFlow()
    }
}
