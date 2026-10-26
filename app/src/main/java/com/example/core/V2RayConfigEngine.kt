package com.example.core

import android.net.Uri
import android.util.Base64
import com.example.data.BalancerConfigState
import com.example.data.NodeHealthStatus
import com.example.data.RoutingRuleEntity
import com.example.data.ServerProfileEntity
import com.example.data.V2RayProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object V2RayConfigEngine {

    const val TAG_BALANCER = "balancer"
    const val TAG_PROXY_PREFIX = "proxy-balancer-"

    // In-memory DNS cache for proxy server domains so transient DNS blips never lose resolved IPs
    private val resolvedDomainCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Parses single or multiple v2rayNG share URIs (or Base64 subscription payloads)
     * supporting vless://, vmess://, trojan://, ss://, socks:// including XHTTP + extra JSON.
     * Automatically disambiguates duplicate node names using short hostname prefixes so all
     * nodes in multi-node subscriptions have distinct names and outbound tags.
     */
    fun parseInputLinks(rawInput: String, subscriptionGroup: String = "Subscription"): List<ServerProfileEntity> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decodedText = if (!trimmed.contains("://")) {
            tryDecodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val rawParsed = decodedText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> parseSingleUri(line, subscriptionGroup) }
            .toList()

        if (rawParsed.size <= 1) return rawParsed

        val remarkCounts = rawParsed.groupingBy { it.remarks.trim() }.eachCount()
        val seenDisambiguated = mutableMapOf<String, Int>()

        return rawParsed.mapIndexed { idx, server ->
            val baseRemark = server.remarks.trim().ifBlank { "Node-${idx + 1}" }
            val disambiguated = if ((remarkCounts[baseRemark] ?: 0) > 1) {
                val hostLabel = extractShortNodeLabel(server.address, idx + 1)
                if (baseRemark.contains("[$hostLabel]", ignoreCase = true) ||
                    baseRemark.contains("($hostLabel)", ignoreCase = true)
                ) {
                    baseRemark
                } else {
                    "$baseRemark [$hostLabel]"
                }
            } else {
                baseRemark
            }

            val occurrence = (seenDisambiguated[disambiguated] ?: 0) + 1
            seenDisambiguated[disambiguated] = occurrence
            val finalRemark = if (occurrence > 1) {
                "$disambiguated #$occurrence"
            } else {
                disambiguated
            }

            server.copy(remarks = finalRemark)
        }
    }

    private fun extractShortNodeLabel(address: String, fallbackIndex: Int): String {
        val clean = address.trim()
        if (clean.isEmpty()) return "N$fallbackIndex"
        if (isPureIpAddress(clean)) {
            val lastOctet = clean.substringAfterLast(".", clean.takeLast(4))
            return "ip-$lastOctet"
        }
        val firstLabel = clean.substringBefore(".").take(10)
        return firstLabel.ifBlank { "N$fallbackIndex" }
    }

    fun parseSingleUri(uriString: String, group: String = "Subscription"): ServerProfileEntity? {
        return try {
            when {
                uriString.startsWith("vless://", ignoreCase = true) ->
                    parseStandardUri(uriString, V2RayProtocol.VLESS, group)
                uriString.startsWith("trojan://", ignoreCase = true) ->
                    parseStandardUri(uriString, V2RayProtocol.TROJAN, group)
                uriString.startsWith("socks://", ignoreCase = true) ->
                    parseStandardUri(uriString, V2RayProtocol.SOCKS, group)
                uriString.startsWith("vmess://", ignoreCase = true) ->
                    parseVmessUri(uriString, group)
                uriString.startsWith("ss://", ignoreCase = true) ->
                    parseShadowsocksUri(uriString, group)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStandardUri(
        uriString: String,
        protocol: V2RayProtocol,
        group: String
    ): ServerProfileEntity? {
        val fixedUrl = uriString.trim().replace(" ", "%20")
        val jUri = runCatching { URI(fixedUrl) }.getOrNull()
        val hostAddress = jUri?.host ?: Uri.parse(fixedUrl).host ?: return null
        val port = (jUri?.port?.takeIf { it > 0 } ?: Uri.parse(fixedUrl).port.takeIf { it > 0 }) ?: 443
        val rawUserInfo = jUri?.rawUserInfo ?: Uri.parse(fixedUrl).userInfo ?: "uuid-not-set"
        val userInfo = decodeUrlComponent(rawUserInfo)

        val rawFragment = jUri?.rawFragment ?: fixedUrl.substringAfter("#", "")
        val remarks = decodeUrlComponent(rawFragment).takeIf { it.isNotBlank() }
            ?: "$hostAddress:$port"

        val queryMap = parseRawQueryMap(jUri?.rawQuery ?: fixedUrl.substringAfter("?", "").substringBefore("#"))

        val encryption = queryMap["encryption"] ?: "none"
        val security = queryMap["security"] ?: if (protocol == V2RayProtocol.TROJAN) "tls" else "none"
        val network = queryMap["type"] ?: "tcp"
        val transportHost = queryMap["host"] ?: queryMap["authority"] ?: ""
        val explicitSni = queryMap["sni"] ?: queryMap["peer"] ?: ""
        val alpn = queryMap["alpn"] ?: ""
        val fingerPrint = queryMap["fp"] ?: "chrome"
        val path = queryMap["path"] ?: queryMap["serviceName"] ?: ""
        val xhttpMode = queryMap["mode"] ?: "auto"
        val rawExtra = queryMap["extra"] ?: ""
        val xPaddingBytes = queryMap["x_padding_bytes"] ?: queryMap["xPaddingBytes"] ?: ""
        val xhttpExtra = if (rawExtra.isNotBlank()) {
            rawExtra
        } else if (xPaddingBytes.isNotBlank()) {
            JSONObject().apply {
                put("mode", xhttpMode.ifBlank { "auto" })
                put("xPaddingBytes", xPaddingBytes)
            }.toString()
        } else {
            ""
        }
        val headerType = queryMap["headerType"] ?: "none"
        val flow = queryMap["flow"] ?: ""
        val pbk = queryMap["pbk"] ?: ""
        val sid = queryMap["sid"] ?: ""
        val spx = queryMap["spx"] ?: ""
        val allowInsecure = queryMap["insecure"] == "1" ||
            queryMap["allowInsecure"] == "1" ||
            queryMap["allow_insecure"] == "1"

        return ServerProfileEntity(
            remarks = remarks,
            protocol = protocol.name,
            address = hostAddress,
            port = port,
            uuidOrPassword = userInfo,
            encryption = encryption,
            security = security,
            sni = explicitSni,
            host = transportHost,
            alpn = alpn,
            fingerPrint = fingerPrint,
            network = network,
            pathOrServiceName = path,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            headerType = headerType,
            flow = flow,
            publicKey = pbk,
            shortId = sid,
            spiderX = spx,
            allowInsecure = allowInsecure,
            subscriptionGroup = group,
            inBalancerPool = true,
            weight = 5
        )
    }

    private fun parseRawQueryMap(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                if (idx >= 0) {
                    val k = decodeUrlComponent(pair.substring(0, idx))
                    val v = decodeUrlComponent(pair.substring(idx + 1))
                    k to v
                } else {
                    decodeUrlComponent(pair) to ""
                }
            }
    }

    private fun decodeUrlComponent(encoded: String): String {
        return try {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            encoded
        }
    }

    private fun encodeUrlComponent(raw: String): String {
        return try {
            URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")
        } catch (_: Exception) {
            raw
        }
    }

    private fun parseVmessUri(uriString: String, group: String): ServerProfileEntity? {
        val payload = uriString.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val jsonStr = tryDecodeBase64(payload) ?: return null
        val obj = JSONObject(jsonStr)
        val add = obj.optString("add").takeIf { it.isNotBlank() } ?: return null
        val port = obj.optString("port", "443").toIntOrNull() ?: 443
        val id = obj.optString("id", "00000000-0000-0000-0000-000000000000")
        val ps = obj.optString("ps").takeIf { it.isNotBlank() } ?: "$add:$port"
        val net = obj.optString("net", "tcp")
        val tls = obj.optString("tls", "none").ifBlank { "none" }
        val host = obj.optString("host", "")
        val sni = obj.optString("sni", "")
        val alpn = obj.optString("alpn", "")
        val fp = obj.optString("fp", "chrome")
        val path = obj.optString("path", "")

        return ServerProfileEntity(
            remarks = ps,
            protocol = V2RayProtocol.VMESS.name,
            address = add,
            port = port,
            uuidOrPassword = id,
            encryption = obj.optString("scy", "auto"),
            security = tls,
            sni = sni,
            host = host,
            alpn = alpn,
            fingerPrint = fp,
            network = net,
            pathOrServiceName = path,
            subscriptionGroup = group,
            inBalancerPool = true,
            weight = 5
        )
    }

    private fun parseShadowsocksUri(uriString: String, group: String): ServerProfileEntity? {
        val withoutScheme = uriString.substringAfter("://")
        val fragment = withoutScheme.substringAfter("#", "")
        val remarks = if (fragment.isNotBlank()) {
            decodeUrlComponent(fragment)
        } else {
            "SS-Node"
        }
        val mainPart = withoutScheme.substringBefore("#")
        val resolved = if (mainPart.contains("@")) {
            val userInfoB64 = mainPart.substringBefore("@")
            val decodedUser = tryDecodeBase64(userInfoB64) ?: userInfoB64
            "$decodedUser@${mainPart.substringAfter("@")}"
        } else {
            tryDecodeBase64(mainPart) ?: return null
        }

        val credentials = resolved.substringBeforeLast("@")
        val hostPort = resolved.substringAfterLast("@")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 8388

        return ServerProfileEntity(
            remarks = remarks,
            protocol = V2RayProtocol.SHADOWSOCKS.name,
            address = host,
            port = port,
            uuidOrPassword = credentials,
            security = "none",
            network = "tcp",
            subscriptionGroup = group,
            inBalancerPool = true,
            weight = 5
        )
    }

    fun toShareUri(server: ServerProfileEntity): String {
        val encodedRemarks = encodeUrlComponent(server.remarks)
        return when (server.protocolEnum) {
            V2RayProtocol.VMESS -> {
                val json = JSONObject().apply {
                    put("v", "2")
                    put("ps", server.remarks)
                    put("add", server.address)
                    put("port", server.port.toString())
                    put("id", server.uuidOrPassword)
                    put("aid", "0")
                    put("scy", server.encryption.ifBlank { "auto" })
                    put("net", server.network)
                    put("type", server.headerType.ifBlank { "none" })
                    put("host", server.host)
                    put("path", server.pathOrServiceName)
                    put("tls", server.security)
                    put("sni", server.sni)
                    put("alpn", server.alpn)
                    put("fp", server.fingerPrint)
                }
                val b64 = Base64.encodeToString(
                    json.toString().toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
                )
                "vmess://$b64"
            }
            V2RayProtocol.SHADOWSOCKS -> {
                val raw = "${server.uuidOrPassword}@${server.address}:${server.port}"
                val b64 = Base64.encodeToString(
                    raw.toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )
                "ss://$b64#$encodedRemarks"
            }
            else -> {
                val scheme = server.protocolEnum.scheme
                val params = LinkedHashMap<String, String>()
                if (server.protocolEnum == V2RayProtocol.VLESS) {
                    params["encryption"] = server.encryption.ifBlank { "none" }
                }
                params["security"] = server.security.ifBlank { "none" }
                if (server.fingerPrint.isNotBlank()) params["fp"] = server.fingerPrint
                if (server.alpn.isNotBlank()) params["alpn"] = server.alpn
                params["type"] = server.network.ifBlank { "tcp" }
                if (server.sni.isNotBlank()) params["sni"] = server.sni
                if (server.host.isNotBlank()) params["host"] = server.host
                if (server.pathOrServiceName.isNotBlank()) {
                    val key = if (server.network == "grpc") "serviceName" else "path"
                    params[key] = server.pathOrServiceName
                }
                if (server.network == "xhttp") {
                    if (server.xhttpMode.isNotBlank()) params["mode"] = server.xhttpMode
                    if (server.xhttpExtra.isNotBlank()) params["extra"] = server.xhttpExtra
                }
                if (server.flow.isNotBlank()) params["flow"] = server.flow
                if (server.publicKey.isNotBlank()) params["pbk"] = server.publicKey
                if (server.shortId.isNotBlank()) params["sid"] = server.shortId
                if (server.spiderX.isNotBlank()) params["spx"] = server.spiderX
                val queryStr = params.entries.joinToString("&") { (k, v) ->
                    "$k=${encodeUrlComponent(v)}"
                }
                "${scheme}${encodeUrlComponent(server.uuidOrPassword)}@${server.address}:${server.port}?$queryStr#$encodedRemarks"
            }
        }
    }

    /**
     * Generates a lightweight single-server Xray-core configuration for Libv2ray.measureOutboundDelay,
     * matching v2rayNG 2.3.13 CoreConfigManager.getV2rayConfig4Speedtest.
     */
    fun generateSingleServerSpeedtestJson(
        server: ServerProfileEntity,
        config: BalancerConfigState,
        resolveDomains: Boolean = true
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("inbounds", JSONArray())

        val outbounds = JSONArray()
        val resolvedIps = if (resolveDomains) resolveDomainToIps(server.address) else emptyList()
        val outboundServer = if (resolvedIps.isNotEmpty()) {
            server.copy(
                sni = server.effectiveSni,
                address = resolvedIps.first()
            )
        } else {
            server
        }
        outbounds.put(buildServerOutboundJson(outboundServer, config, tagOverride = "proxy"))
        outbounds.put(buildDirectOutboundJson())
        outbounds.put(buildBlockOutboundJson())
        root.put("outbounds", outbounds)

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        })
        return root.toString()
    }

    /**
     * Generates the complete Xray-core v26.10.21 JSON configuration featuring:
     * - stats & policy (per-outbound uplink/downlink byte counters)
     * - SOCKS5 (10808) and Built-in Xray TUN ("xray0") inbounds
     * - All eligible healthy/degraded servers in the Balancer Pool
     * - Direct DNS bypass for proxy server domains to eliminate circular DNS deadlocks
     * - BurstObservatory (with protocol-appropriate 6-7s timeout & 20s interval)
     * - Dynamic weighted routing.balancers with inverse-weight costs & fallbackTag
     */
    fun generateBalancerXrayJson(
        poolServers: List<ServerProfileEntity>,
        config: BalancerConfigState,
        routingRules: List<RoutingRuleEntity>,
        includeTunInbound: Boolean = true,
        resolveDomains: Boolean = false
    ): String {
        val root = JSONObject()

        // 1. Stats & Policy (required by CoreController.queryAllOutboundTrafficStats)
        root.put("stats", JSONObject())
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 6)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                })
            })
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // 2. Inbounds (v2rayNG 2.3.13: socks + tun)
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks")
            put("port", config.socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            })
        })
        if (includeTunInbound) {
            inbounds.put(JSONObject().apply {
                put("tag", "tun")
                put("protocol", "tun")
                put("settings", JSONObject().apply {
                    put("name", "xray0")
                    put("MTU", 1500)
                    put("userLevel", 8)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                })
            })
        }
        root.put("inbounds", inbounds)

        // 3. Outbounds (Include all HEALTHY, DEGRADED, and UNTESTED nodes; only exclude confirmed UNHEALTHY or simulated down)
        val outbounds = JSONArray()
        val activePool = poolServers.filter {
            !it.isSimulatedDown && it.healthStatusEnum != NodeHealthStatus.UNHEALTHY
        }.ifEmpty {
            poolServers.filter { !it.isSimulatedDown }.ifEmpty { poolServers }
        }

        val dnsHosts = JSONObject().apply {
            put("googleapis.cn", "googleapis.com")
            put("dns.google", JSONArray().put("8.8.8.8").put("8.8.4.4"))
            put("one.one.one.one", JSONArray().put("1.1.1.1").put("1.0.0.1"))
            put("cloudflare-dns.com", JSONArray().put("1.1.1.1").put("1.0.0.1"))
        }

        val proxyServerDomains = mutableSetOf<String>()
        val memberTags = mutableListOf<String>()
        val tagWeights = mutableListOf<Pair<String, Int>>()

        activePool.forEachIndexed { index, server ->
            val safeRemark = server.remarks.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-')
            val tag = "${TAG_PROXY_PREFIX}${index + 1}-${safeRemark.ifBlank { "node" }}"
            memberTags.add(tag)
            tagWeights.add(tag to server.weight.coerceIn(1, 10))

            if (!isPureIpAddress(server.address)) {
                proxyServerDomains.add("full:${server.address}")
                val ips = if (resolveDomains) {
                    resolveDomainToIps(server.address)
                } else {
                    resolvedDomainCache[server.address].orEmpty()
                }
                if (ips.isNotEmpty()) {
                    if (ips.size == 1) {
                        dnsHosts.put(server.address, ips.first())
                    } else {
                        val ipArr = JSONArray()
                        ips.forEach { ipArr.put(it) }
                        dnsHosts.put(server.address, ipArr)
                    }
                }
            }

            outbounds.put(buildServerOutboundJson(server, config, tagOverride = tag))
        }

        outbounds.put(buildDirectOutboundJson())
        outbounds.put(buildBlockOutboundJson())
        root.put("outbounds", outbounds)

        // 4. Observatory / BurstObservatory with protocol-appropriate timeouts (6-7s for XHTTP/TLS)
        val selectorArray = JSONArray().put(TAG_PROXY_PREFIX)
        val probeIntervalSec = config.probeIntervalSec.coerceIn(12, 45)
        val probeTimeoutSec = (config.failoverThresholdMs / 1000).coerceIn(5, 9)

        if (config.strategy.usesBurstObservatory) {
            root.put("burstObservatory", JSONObject().apply {
                put("subjectSelector", selectorArray)
                put("pingConfig", JSONObject().apply {
                    put("destination", config.probeUrl)
                    put("httpMethod", "HEAD")
                    put("interval", "${probeIntervalSec}s")
                    put("sampling", 3)
                    put("timeout", "${probeTimeoutSec}s")
                })
            })
        } else {
            root.put("observatory", JSONObject().apply {
                put("subjectSelector", selectorArray)
                put("probeUrl", config.probeUrl)
                put("probeInterval", "${probeIntervalSec}s")
                put("enableConcurrency", true)
            })
        }

        // 5. DNS Configuration
        val dnsServers = JSONArray()
        config.remoteDns.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            .forEach { dnsServers.put(it) }

        root.put("dns", JSONObject().apply {
            put("servers", dnsServers)
            put("hosts", dnsHosts)
            put("tag", "dns")
        })

        // 6. Routing & Dynamic Weighted Multi-Server Balancer
        val bestFallbackTag = activePool
            .mapIndexed { idx, srv -> idx to srv }
            .sortedWith(
                compareByDescending<Pair<Int, ServerProfileEntity>> { (_, srv) ->
                    srv.healthStatusEnum == NodeHealthStatus.HEALTHY
                }
                    .thenByDescending { (_, srv) -> srv.weight }
                    .thenBy { (_, srv) ->
                        val rtt = srv.effectiveLatencyMs
                        if (rtt > 0L) rtt else Long.MAX_VALUE
                    }
            )
            .firstOrNull()
            ?.let { (idx, _) -> memberTags.getOrNull(idx) }
            ?: memberTags.firstOrNull()

        val strategyObject = JSONObject().apply {
            put("type", config.strategy.xrayKey)
            if (config.strategy.usesBurstObservatory && tagWeights.isNotEmpty()) {
                val costsArray = JSONArray()
                tagWeights.forEach { (tag, weight) ->
                    // Higher dynamic weight -> lower cost factor in Xray leastLoad -> receives more streams
                    val inverseCost = ((10.0 / weight.coerceIn(1, 10)) * 100.0).toInt() / 100.0
                    costsArray.put(JSONObject().apply {
                        put("regexp", false)
                        put("match", tag)
                        put("value", inverseCost)
                    })
                }
                put("settings", JSONObject().apply {
                    put("costs", costsArray)
                })
            }
        }

        val balancersArray = JSONArray()
        balancersArray.put(JSONObject().apply {
            put("tag", TAG_BALANCER)
            put("selector", JSONArray().put(TAG_PROXY_PREFIX))
            if (bestFallbackTag != null) {
                put("fallbackTag", bestFallbackTag)
            }
            put("strategy", strategyObject)
        })

        val rulesArray = JSONArray()

        // Critical: Route any direct connection or DNS lookup for the proxy server domains themselves
        // via "direct" so Xray never deadlocks trying to resolve a proxy server domain through the balancer!
        if (proxyServerDomains.isNotEmpty()) {
            val domainArr = JSONArray()
            proxyServerDomains.forEach { domainArr.put(it) }
            rulesArray.put(JSONObject().apply {
                put("type", "field")
                put("domain", domainArr)
                put("outboundTag", "direct")
            })
        }

        // Route Xray internal DNS queries through the balancer
        rulesArray.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().put("dns"))
            put("balancerTag", TAG_BALANCER)
        })

        // Bypass private LAN IPs directly without requiring external .dat files
        rulesArray.put(JSONObject().apply {
            put("type", "field")
            put("ip", JSONArray()
                .put("10.0.0.0/8")
                .put("127.0.0.0/8")
                .put("172.16.0.0/12")
                .put("192.168.0.0/16")
                .put("169.254.0.0/16")
            )
            put("outboundTag", "direct")
        })

        // User-configured custom routing rules
        routingRules.filter { it.enabled }.forEach { rule ->
            val pattern = rule.domainOrIpPattern.trim()
            if (pattern.isNotEmpty() && !pattern.startsWith("geosite:") && !pattern.startsWith("geoip:")) {
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    val isIp = pattern.any { it.isDigit() } && (pattern.contains("/") || isPureIpAddress(pattern))
                    if (isIp) {
                        put("ip", JSONArray().put(pattern))
                    } else {
                        put("domain", JSONArray().put(pattern))
                    }
                    when (rule.targetTag) {
                        "direct" -> put("outboundTag", "direct")
                        "block" -> put("outboundTag", "block")
                        else -> put("balancerTag", TAG_BALANCER)
                    }
                })
            }
        }

        // Catch-all TCP & UDP rule to the Multi-Server Balancer
        rulesArray.put(JSONObject().apply {
            put("type", "field")
            put("network", "tcp,udp")
            put("balancerTag", TAG_BALANCER)
        })

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", rulesArray)
            put("balancers", balancersArray)
        })

        return root.toString(2)
    }

    /**
     * Builds an Xray-core v26.10.21 Outbound JSON object matching v2rayNG 2.3.13 CoreOutboundBuilder.kt.
     */
    private fun buildServerOutboundJson(
        server: ServerProfileEntity,
        config: BalancerConfigState,
        tagOverride: String? = null
    ): JSONObject {
        val network = server.network.lowercase(Locale.US).ifBlank { "tcp" }
        val security = server.security.lowercase(Locale.US).ifBlank { "none" }
        val sniToUse = server.effectiveSni

        return JSONObject().apply {
            put("tag", tagOverride ?: server.outboundTag)
            put("protocol", server.protocolEnum.name.lowercase(Locale.US))

            put("settings", when (server.protocolEnum) {
                V2RayProtocol.VLESS -> JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("id", server.uuidOrPassword)
                    put("encryption", server.encryption.ifBlank { "none" })
                    if (server.flow.isNotBlank()) {
                        put("flow", server.flow)
                    }
                    put("level", 8)
                }
                V2RayProtocol.VMESS -> JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("id", server.uuidOrPassword)
                    put("security", server.encryption.ifBlank { "auto" })
                    put("level", 8)
                }
                V2RayProtocol.TROJAN -> JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("password", server.uuidOrPassword)
                    if (server.flow.isNotBlank()) {
                        put("flow", server.flow)
                    }
                    put("level", 8)
                }
                V2RayProtocol.SHADOWSOCKS -> JSONObject().apply {
                    val method = server.uuidOrPassword.substringBefore(":", "2022-blake3-aes-128-gcm")
                    val pass = server.uuidOrPassword.substringAfter(":", server.uuidOrPassword)
                    put("address", server.address)
                    put("port", server.port)
                    put("method", method)
                    put("password", pass)
                    put("level", 8)
                }
                else -> JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("level", 8)
                }
            })

            put("streamSettings", JSONObject().apply {
                put("network", network)
                if (security == "tls" || security == "reality") {
                    put("security", security)
                }

                when (network) {
                    "xhttp" -> {
                        put("xhttpSettings", JSONObject().apply {
                            put("host", server.host.ifBlank { sniToUse })
                            put("path", server.pathOrServiceName.ifBlank { "/" })
                            if (server.xhttpMode.isNotBlank()) {
                                put("mode", server.xhttpMode)
                            }
                            if (server.xhttpExtra.isNotBlank()) {
                                runCatching { JSONObject(server.xhttpExtra) }.getOrNull()?.let { extraObj ->
                                    put("extra", extraObj)
                                }
                            }
                        })
                    }
                    "ws" -> {
                        put("wsSettings", JSONObject().apply {
                            put("host", server.host.ifBlank { sniToUse })
                            put("path", server.pathOrServiceName.ifBlank { "/" })
                        })
                    }
                    "httpupgrade" -> {
                        put("httpupgradeSettings", JSONObject().apply {
                            put("host", server.host.ifBlank { sniToUse })
                            put("path", server.pathOrServiceName.ifBlank { "/" })
                        })
                    }
                    "grpc" -> {
                        put("grpcSettings", JSONObject().apply {
                            put("serviceName", server.pathOrServiceName.ifBlank { "grpc" })
                            if (server.host.isNotBlank()) {
                                put("authority", server.host)
                            }
                            put("multiMode", server.xhttpMode == "multi")
                        })
                    }
                    "h2", "http" -> {
                        put("network", "h2")
                        put("httpSettings", JSONObject().apply {
                            val hostsArr = JSONArray()
                            server.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                hostsArr.put(it)
                            }
                            put("host", hostsArr)
                            put("path", server.pathOrServiceName.ifBlank { "/" })
                        })
                    }
                    else -> {
                        put("tcpSettings", JSONObject().apply {
                            put("header", JSONObject().put("type", server.headerType.ifBlank { "none" }))
                        })
                    }
                }

                if (security == "tls") {
                    put("tlsSettings", JSONObject().apply {
                        put("allowInsecure", server.allowInsecure)
                        if (sniToUse.isNotBlank()) {
                            put("serverName", sniToUse)
                        }
                        if (server.fingerPrint.isNotBlank()) {
                            put("fingerprint", server.fingerPrint)
                        }
                        val alpnList = server.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (alpnList.isNotEmpty()) {
                            val alpnArr = JSONArray()
                            alpnList.forEach { alpnArr.put(it) }
                            put("alpn", alpnArr)
                        }
                    })
                } else if (security == "reality") {
                    put("realitySettings", JSONObject().apply {
                        put("allowInsecure", false)
                        if (sniToUse.isNotBlank()) {
                            put("serverName", sniToUse)
                        }
                        put("fingerprint", server.fingerPrint.ifBlank { "chrome" })
                        if (server.publicKey.isNotBlank()) put("publicKey", server.publicKey)
                        if (server.shortId.isNotBlank()) put("shortId", server.shortId)
                        if (server.spiderX.isNotBlank()) put("spiderX", server.spiderX)
                    })
                }

                put("sockopt", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                    put("happyEyeballs", JSONObject().apply {
                        put("prioritizeIPv6", false)
                        put("maxConcurrentTry", 4)
                        put("tryDelayMs", 250)
                        put("interleave", 2)
                    })
                })
            })

            val shouldEnableMux = config.muxEnabled &&
                network != "xhttp" &&
                server.flow.isBlank() &&
                (server.protocolEnum == V2RayProtocol.VLESS || server.protocolEnum == V2RayProtocol.VMESS)

            put("mux", JSONObject().apply {
                put("enabled", shouldEnableMux)
                put("concurrency", if (shouldEnableMux) config.muxConcurrency else -1)
            })
        }
    }

    private fun buildDirectOutboundJson(): JSONObject {
        return JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                    put("happyEyeballs", JSONObject().apply {
                        put("tryDelayMs", 250)
                        put("interleave", 2)
                    })
                })
            })
        }
    }

    private fun buildBlockOutboundJson(): JSONObject {
        return JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
            put("settings", JSONObject().apply {
                put("response", JSONObject().put("type", "http"))
            })
        }
    }

    fun resolveDomainToIps(host: String): List<String> {
        if (host.isBlank() || isPureIpAddress(host)) return emptyList()
        return try {
            val fresh = InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() }
                .distinct()
            if (fresh.isNotEmpty()) {
                resolvedDomainCache[host] = fresh
            }
            fresh.ifEmpty { resolvedDomainCache[host].orEmpty() }
        } catch (_: Exception) {
            resolvedDomainCache[host].orEmpty()
        }
    }

    fun isPureIpAddress(value: String): Boolean {
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Regex.matches(value.trim()) || value.contains(":")
    }

    private fun tryDecodeBase64(input: String): String? {
        return try {
            val clean = input.trim().replace("\n", "").replace("\r", "")
            val bytes = try {
                Base64.decode(clean, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                Base64.decode(clean, Base64.URL_SAFE)
            }
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
