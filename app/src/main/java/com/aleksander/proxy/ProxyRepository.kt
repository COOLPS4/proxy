package com.aleksander.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
        "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt"
    )

    private const val MAX_CANDIDATES = 600   // how many to pull from the sources
    private const val POOL_TO_CHECK = 320    // how many to actually TCP-probe
    private const val CONCURRENCY = 80       // simultaneous probes
    private const val CONNECT_TIMEOUT_MS = 1800

    /**
     * Returns up to [want] LIVE proxies of the given [type], sorted by lowest
     * ping first. Only proxies that pass a real TCP connection check are
     * returned — dead entries are never shown.
     */
    suspend fun findWorking(type: ProxyType, want: Int = 10): List<Proxy> = withContext(Dispatchers.IO) {
        val all = fetchCandidates(type)
        if (all.isEmpty()) return@withContext emptyList()

        // Probe the most promising candidates first: for MTProto the sources
        // already provide a latency hint, so check the fastest hinted ones.
        val ordered = when (type) {
            ProxyType.MTPROTO -> all.sortedWith(compareBy(nullsLast()) { it.latencyMs })
            ProxyType.SOCKS5 -> all.shuffled()
        }
        val pool = ordered.take(POOL_TO_CHECK)

        val gate = Semaphore(CONCURRENCY)
        val live = coroutineScope {
            pool.map { p ->
                async {
                    gate.withPermit {
                        val ms = probe(p, CONNECT_TIMEOUT_MS)
                        if (ms != null) p.copy(latencyMs = ms) else null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        // Fastest first, deduplicated by address.
        live.sortedBy { it.latencyMs }
            .distinctBy { it.label }
            .take(want)
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
                    val hint = o.optDouble("latency_ms", -1.0)
                    if (host.isNotBlank() && port in 1..65535 && secret.isNotBlank()) {
                        val lat = if (hint > 0) hint.toLong() else null
                        list.add(Proxy(ProxyType.MTPROTO, host, port, secret, lat))
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

    /**
     * Probes a proxy and returns a realistic ping in ms, or null if it is not
     * usable. DNS is resolved outside the timing so the number reflects the
     * real network round-trip. The best of two attempts is kept for stability.
     *
     * SOCKS5 proxies additionally must pass a real SOCKS5 handshake with the
     * "no authentication" method, so servers that merely accept a TCP
     * connection (but aren't a usable open SOCKS5) are rejected.
     */
    private fun probe(p: Proxy, timeoutMs: Int): Long? {
        val addr = try {
            java.net.InetAddress.getByName(p.host)
        } catch (_: Exception) {
            return null
        }
        // First probe decides if it is alive; dead proxies aren't retried.
        val first = probeOnce(p, addr, timeoutMs) ?: return null
        val second = probeOnce(p, addr, timeoutMs)
        return if (second != null) minOf(first, second) else first
    }

    private fun probeOnce(p: Proxy, addr: java.net.InetAddress, timeoutMs: Int): Long? =
        when (p.type) {
            ProxyType.SOCKS5 -> socks5Ping(addr, p.port, timeoutMs)
            ProxyType.MTPROTO -> mtprotoPing(addr, p.port, p.secret, timeoutMs)
        }

    /**
     * Validates an MTProto proxy. Modern proxies use a FakeTLS secret (starts
     * with "ee") and impersonate a real HTTPS site whose domain is embedded in
     * the secret. We complete a TLS handshake to that domain — only a genuine
     * FakeTLS proxy answers correctly, which rejects dead/non-MTProto hosts
     * that merely keep port 443 open. Older "dd"/plain secrets fall back to a
     * TCP check. The reported ping is the pure TCP-connect time.
     */
    private fun mtprotoPing(addr: java.net.InetAddress, port: Int, secret: String?, timeoutMs: Int): Long? {
        val sni = secret?.let { sniFromSecret(it) }
        return if (sni != null) tlsPing(addr, port, sni, timeoutMs) else tcpPing(addr, port, timeoutMs)
    }

    /** Decodes the SNI host hidden in a FakeTLS ("ee…") secret, or null. */
    private fun sniFromSecret(secret: String): String? {
        val s = secret.lowercase()
        if (!s.startsWith("ee")) return null
        val hex = s.substring(2)
        if (hex.length <= 32) return null
        val hostHex = hex.substring(32)
        return try {
            val bytes = ByteArray(hostHex.length / 2) {
                hostHex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.US_ASCII)
                .takeIf { it.length in 3..255 && it.all { c -> c.code in 33..126 } && it.contains('.') }
        } catch (_: Exception) {
            null
        }
    }

    /** TLS-only trust manager: we only care that a TLS handshake completes. */
    private val sslFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        javax.net.ssl.SSLContext.getInstance("TLS")
            .apply { init(null, trustAll, java.security.SecureRandom()) }
            .socketFactory
    }

    private fun tlsPing(addr: java.net.InetAddress, port: Int, sni: String, timeoutMs: Int): Long? {
        return try {
            val raw = Socket()
            val start = System.nanoTime()
            raw.connect(InetSocketAddress(addr, port), timeoutMs)
            val connectMs = (System.nanoTime() - start) / 1_000_000
            val ssl = sslFactory.createSocket(raw, sni, port, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            }
            ssl.use { it.startHandshake() }
            connectMs
        } catch (_: Exception) {
            null
        }
    }

    /** Pure TCP connect time to an already-resolved address. */
    private fun tcpPing(addr: java.net.InetAddress, port: Int, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(addr, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Connects and performs the SOCKS5 greeting. The reported ping is the pure
     * TCP-connect time (≈ real ping); the handshake only validates that the
     * server is a working no-auth SOCKS5 proxy.
     */
    private fun socks5Ping(addr: java.net.InetAddress, port: Int, timeoutMs: Int): Long? {
        return try {
            Socket().use { s ->
                val start = System.nanoTime()
                s.connect(InetSocketAddress(addr, port), timeoutMs)
                val connectMs = (System.nanoTime() - start) / 1_000_000
                s.soTimeout = timeoutMs
                // Greeting: version 5, 1 method, 0x00 = no authentication.
                s.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                val resp = ByteArray(2)
                val n = s.getInputStream().read(resp)
                // Valid + usable without a password: 0x05, 0x00.
                if (n >= 2 && resp[0].toInt() == 0x05 && resp[1].toInt() == 0x00) connectMs else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
