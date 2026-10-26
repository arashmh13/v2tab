package com.example.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.system.OsConstants
import android.util.Base64
import go.Seq
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

data class OutboundTrafficStat(
    val tag: String,
    val direction: String, // "uplink" or "downlink"
    val bytesDelta: Long
)

object XrayCoreRunner {

    private val initialized = AtomicBoolean(false)
    private var coreController: CoreController? = null
    private var processFinderRegistered = false

    @Volatile
    private var activeTunFd: Int = 0

    private val _coreStatusLogs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val coreStatusLogs: SharedFlow<String> = _coreStatusLogs.asSharedFlow()

    /**
     * Initializes the native Xray-core environment and copies geoip/geosite assets,
     * matching v2rayNG 2.3.13 CoreNativeManager.initCoreEnv + SettingsManager.initAssets.
     */
    @Synchronized
    fun initCoreEnv(context: Context) {
        val appCtx = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            try {
                val assetDir = copyGeoAssetsIfNeeded(appCtx)
                Seq.setContext(appCtx)
                val deviceId = getDeviceIdForXUDPBaseKey(appCtx)
                Libv2ray.initCoreEnv(assetDir, deviceId)
                ensureController(appCtx)
            } catch (_: Throwable) {
                initialized.set(false)
            }
        } else {
            ensureController(appCtx)
        }
    }

    private fun ensureController(appCtx: Context): CoreController? {
        if (coreController == null) {
            try {
                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long {
                        _coreStatusLogs.tryEmit("Xray-core engine startup complete")
                        return 0L
                    }

                    override fun shutdown(): Long {
                        _coreStatusLogs.tryEmit("Xray-core engine shutdown complete")
                        return 0L
                    }

                    override fun onEmitStatus(code: Long, status: String?): Long {
                        if (!status.isNullOrBlank()) {
                            _coreStatusLogs.tryEmit("CoreStatus($code): $status")
                        }
                        return 0L
                    }
                })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !processFinderRegistered) {
                    val cm = appCtx.getSystemService(ConnectivityManager::class.java)
                    controller.registerProcessFinder(ProcessFinder { network, srcIP, srcPort, destIP, destPort ->
                        if (cm == null || destIP.isBlank() || destPort == 0L) return@ProcessFinder -1L
                        val proto = when (network) {
                            "tcp" -> OsConstants.IPPROTO_TCP
                            "udp" -> OsConstants.IPPROTO_UDP
                            else -> return@ProcessFinder -1L
                        }
                        try {
                            cm.getConnectionOwnerUid(
                                proto,
                                InetSocketAddress(srcIP, srcPort.toInt()),
                                InetSocketAddress(destIP, destPort.toInt())
                            ).toLong()
                        } catch (_: Exception) {
                            -1L
                        }
                    })
                    processFinderRegistered = true
                }
                coreController = controller
            } catch (_: Throwable) {
                return null
            }
        }
        return coreController
    }

    fun getCoreVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (_: Throwable) {
            "Xray-core 26.10.21"
        }
    }

    fun isRunning(): Boolean {
        return try {
            coreController?.isRunning == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Starts the real Xray-core loop with the provided JSON configuration and TUN file descriptor.
     */
    @Synchronized
    fun startCoreLoop(context: Context, configJson: String, tunFd: Int): Result<Unit> {
        return try {
            initCoreEnv(context)
            val controller = ensureController(context.applicationContext)
                ?: return Result.failure(IllegalStateException("Xray-core controller unavailable"))

            if (controller.isRunning) {
                controller.stopLoop()
            }
            activeTunFd = tunFd
            controller.startLoop(configJson, tunFd)
            if (controller.isRunning) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Xray-core startLoop returned without running state"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Hot-reloads the running Xray-core instance with an updated balancer configuration
     * while keeping the Android VPN TUN interface open.
     */
    @Synchronized
    fun reloadCoreLoop(context: Context, updatedConfigJson: String): Result<Unit> {
        if (!isRunning()) return Result.success(Unit)
        return startCoreLoop(context, updatedConfigJson, activeTunFd)
    }

    /**
     * Stops the running Xray-core loop cleanly.
     */
    @Synchronized
    fun stopCoreLoop() {
        try {
            if (coreController?.isRunning == true) {
                coreController?.stopLoop()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Queries and resets all per-outbound traffic counters from the active Xray-core instance.
     * Format from Go: tag,direction,value;tag,direction,value;
     */
    fun queryOutboundTrafficStats(): List<OutboundTrafficStat> {
        return try {
            val controller = coreController ?: return emptyList()
            if (!controller.isRunning) return emptyList()
            val payload = controller.queryAllOutboundTrafficStats().orEmpty()
            if (payload.isBlank()) return emptyList()

            payload.split(';').mapNotNull { entry ->
                if (entry.isBlank()) return@mapNotNull null
                val parts = entry.split(',', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val bytes = parts[2].toLongOrNull() ?: return@mapNotNull null
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    bytesDelta = bytes
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Measures real end-to-end HTTP delay through the currently running Xray-core balancer.
     */
    fun measureRunningTunnelDelay(url: String): Long {
        return try {
            val controller = coreController ?: return -1L
            if (!controller.isRunning) return -1L
            controller.measureDelay(url)
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Measures real VLESS/XHTTP/TLS outbound delay for a single server using Libv2ray.measureOutboundDelay.
     */
    fun measureSingleOutboundDelay(
        context: Context,
        singleOutboundConfigJson: String,
        url: String
    ): Long {
        return try {
            initCoreEnv(context)
            Libv2ray.measureOutboundDelay(singleOutboundConfigJson, url)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun copyGeoAssetsIfNeeded(context: Context): String {
        val extDir = context.getDir("assets", Context.MODE_PRIVATE)
        val geoFiles = listOf("geosite.dat", "geoip.dat", "geoip-only-cn-private.dat")
        try {
            val bundledList = context.assets.list("")?.toSet().orEmpty()
            for (fileName in geoFiles) {
                val targetFile = File(extDir, fileName)
                if (!targetFile.exists() && bundledList.contains(fileName)) {
                    context.assets.open(fileName).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return extDir.absolutePath
    }

    private fun getDeviceIdForXUDPBaseKey(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty().ifBlank { "v2rayng-balancer-device" }
            val bytes = androidId.toByteArray(Charsets.UTF_8).copyOf(32)
            Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.URL_SAFE)
        } catch (_: Throwable) {
            ""
        }
    }
}
