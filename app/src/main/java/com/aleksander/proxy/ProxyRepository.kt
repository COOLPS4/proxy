package com.aleksander.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Type of proxy the user is looking for. */
enum class ProxyType(val title: String) {
    MTPROTO("MTProto"),
    SOCKS5("SOCKS5")
}

/** A single proxy plus the ready-to-use Telegram deep link. */
data class Proxy(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val secret: String? = null,
    val latencyMs: Long? = null
) {
    /** Address shown to the user, e.g. 1.2.3.4:443 */
    val label: String get() = "$host:$port"

    /** Tap-to-connect link that opens the proxy directly in Telegram. */
    val link: String
        get() = when (type) {
            ProxyType.MTPROTO ->
                "https://t.me/proxy?server=$host&port=$port&secret=$secret"
            ProxyType.SOCKS5 ->
                "https://t.me/socks?server=$host&port=$port"
        }
}

/**
 * Fetches proxy candidates from several public sources and returns only the
 * ones that actually accept a TCP connection, sorted fastest-first.
 */
object ProxyRepository {

    private val MTPROTO_SOURCES = listOf(
        "https://raw.githubusercontent.com/shablin/mtproto-proxy/master/data/valid_proxy.json",
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    )

    private val SOCKS5_SOURCES = listOf(
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"
    )

    private const val MAX_CANDIDATES = 150
    private const val CONNECT_TIMEOUT_MS = 2500

    /**
     * Returns up to [want] live proxies of the given [type].
     * If not enough proxies pass the liveness check, the list is topped up
     * with unverified candidates so the user always gets a full list.
     */
    suspend fun findWorking(type: ProxyType, want: Int = 10): List<Proxy> = withContext(Dispatchers.IO) {
        val candidates = fetchCandidates(type).shuffled().take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return@withContext emptyList()

        val checked = coroutineScope {
            candidates.map { p ->
                async {
                    val ms = tcpPing(p.host, p.port, CONNECT_TIMEOUT_MS)
                    if (ms != null) p.copy(latencyMs = ms) else null
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.latencyMs }

        if (checked.size >= want) {
            checked.take(want)
        } else {
            val filler = candidates.filter { c -> checked.none { it.label == c.label } }
            (checked + filler).take(want)
        }
    }

    private fun fetchCandidates(type: ProxyType): List<Proxy> {
        val sources = if (type == ProxyType.MTPROTO) MTPROTO_SOURCES else SOCKS5_SOURCES
        val out = LinkedHashMap<String, Proxy>()
        for (url in sources) {
            val body = httpGet(url) ?: continue
            val parsed = if (type == ProxyType.MTPROTO) parseMtproto(body) else parseSocks(body)
            for (p in parsed) out.putIfAbsent(p.label, p)
            if (out.size >= MAX_CANDIDATES) break
        }
        return out.values.toList()
    }

    // ---- parsing -----------------------------------------------------------

    private fun parseMtproto(body: String): List<Proxy> {
        val list = ArrayList<Proxy>()
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val host = o.optString("host", o.optString("server", ""))
                    val port = o.optInt("port", 0)
                    val secret = o.optString("secret", "")
                    if (host.isNotBlank() && port in 1..65535 && secret.isNotBlank()) {
                        list.add(Proxy(ProxyType.MTPROTO, host, port, secret))
                    } else {
                        parseTgLink(o.optString("url", ""))?.let { list.add(it) }
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
                // fall through to link scanning
            }
        }
        Regex("(?:tg://|https?://t\\.me/)proxy\\?[^\\s\"']+")
            .findAll(body)
            .forEach { m -> parseTgLink(m.value)?.let { list.add(it) } }
        return list
    }

    private fun parseTgLink(link: String): Proxy? {
        val server = Regex("server=([^&\\s]+)").find(link)?.groupValues?.get(1) ?: return null
        val port = Regex("port=([0-9]+)").find(link)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val secret = Regex("secret=([^&\\s]+)").find(link)?.groupValues?.get(1) ?: return null
        if (port !in 1..65535) return null
        return Proxy(ProxyType.MTPROTO, server, port, secret)
    }

    private fun parseSocks(body: String): List<Proxy> {
        val list = ArrayList<Proxy>()
        val pattern = Regex("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})")
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
                .removePrefix("socks5://")
                .removePrefix("socks4://")
                .removePrefix("socks://")
            val m = pattern.find(line) ?: return@forEach
            val port = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (port in 1..65535) list.add(Proxy(ProxyType.SOCKS5, m.groupValues[1], port))
        }
        return list
    }

    // ---- network -----------------------------------------------------------

    private fun httpGet(urlStr: String): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AleksanderProxy/1.0")
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** @return round-trip time in ms if the TCP handshake succeeds, else null. */
    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }
}
