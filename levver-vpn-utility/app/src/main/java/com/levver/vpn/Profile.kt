package com.levver.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class Profile(val id: String, val name: String, val protocol: String, val raw: String, val server: String)

object ProfileParser {
    private val supportedSchemes = mapOf(
        "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan",
        "ss" to "Shadowsocks", "socks" to "SOCKS", "socks5" to "SOCKS5",
        "socks5h" to "SOCKS5", "hysteria" to "Hysteria", "hysteria2" to "Hysteria2",
        "hy2" to "Hysteria2", "tuic" to "TUIC", "anytls" to "AnyTLS",
        "wireguard" to "WireGuard", "wg" to "WireGuard"
    )

    fun parse(input: String): List<Profile> {
        val trimmed = input.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val config = JSONObject(trimmed)
                val outbounds = config.optJSONArray("outbounds") ?: return emptyList()
                require(outbounds.length() > 0) { "No outbounds in sing-box config" }
                val inbounds = config.optJSONArray("inbounds") ?: return emptyList()
                require((0 until inbounds.length()).any { inbounds.getJSONObject(it).optString("type") == "tun" }) {
                    "The sing-box config must contain a TUN inbound"
                }
                listOf(Profile(trimmed.hashCode().toString(), "Пользовательская sing-box конфигурация",
                    "sing-box JSON", trimmed, "Пользовательский сервер"))
            } catch (_: Exception) {
                emptyList()
            }
        }
        val candidates = input.trim().lines().flatMap { line ->
            val value = line.trim()
            if (value.isBlank()) emptyList()
            else if (value.startsWith("vmess://")) listOf(value)
            else value.split(Regex("\\s+")).filter {
                it.substringBefore("://").lowercase() in supportedSchemes
            }
        }
        return candidates.mapNotNull(::parseOne).distinctBy { it.raw }
    }

    private fun parseOne(raw: String): Profile? {
        return try {
        val scheme = raw.substringBefore("://").lowercase()
        val protocol = supportedSchemes[scheme] ?: return null
        if (scheme == "vmess") {
            val payload = raw.removePrefix("vmess://").substringBefore('#')
            val decoded = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(decoded)
            val host = json.optString("add").ifBlank { "unknown" }
            val label = json.optString("ps").ifBlank { "$protocol — $host" }
            return Profile(raw.hashCode().toString(), label, protocol, raw, host)
        }
        val uri = URI(raw)
        val host = uri.host ?: if (scheme == "ss") "Shadowsocks profile" else return null
        val label = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotBlank() } ?: "$protocol — $host"
        Profile(raw.hashCode().toString(), label, protocol, raw, host)
        } catch (_: Exception) {
            null
        }
    }
}
