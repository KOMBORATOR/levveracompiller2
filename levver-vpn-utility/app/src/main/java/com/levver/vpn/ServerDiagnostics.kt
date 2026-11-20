package com.levver.vpn

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import org.json.JSONObject

data class ServerProbe(val latencyMs: Long?, val detail: String)

object ServerDiagnostics {
    /**
     * Measures DNS + TCP connect time only. This is a reachability indicator,
     * not a full VPN handshake or throughput test. UDP-only protocols such as
     * Hysteria2 cannot be fairly ranked by this TCP probe.
     */
    fun probe(profile: Profile, timeoutMs: Int = 3500): ServerProbe {
        val endpoint = endpoint(profile) ?: return ServerProbe(null, "Нет TCP endpoint")
        return try {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), timeoutMs)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            ServerProbe(elapsed, "TCP-доступен")
        } catch (e: Exception) {
            ServerProbe(null, when (e) {
                is java.net.SocketTimeoutException -> "Тайм-аут"
                is java.net.UnknownHostException -> "DNS не найден"
                else -> "Недоступен: ${e.javaClass.simpleName}"
            })
        }
    }

    private fun endpoint(profile: Profile): Pair<String, Int>? {
        val raw = profile.raw.trim()
        if (raw.startsWith("{")) return null
        return try {
            if (raw.startsWith("vmess://")) {
                val encoded = raw.removePrefix("vmess://").substringBefore('#')
                val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
                val host = json.optString("add")
                val port = json.optString("port").toIntOrNull() ?: return null
                if (host.isBlank() || port !in 1..65535) null else host to port
            } else {
                val uri = URI(raw)
                val host = uri.host ?: return null
                val port = uri.port.takeIf { it in 1..65535 } ?: return null
                host to port
            }
        } catch (_: Exception) { null }
    }
}
