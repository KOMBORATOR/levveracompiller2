package com.levver.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class Profile(val id: String, val name: String, val protocol: String, val raw: String, val server: String, val subscriptionUrl: String? = null)

object ProfileParser {
    private val supportedSchemes = mapOf(
        "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan",
        "ss" to "Shadowsocks", "socks" to "SOCKS", "socks5" to "SOCKS5",
        "socks5h" to "SOCKS5", "hysteria" to "Hysteria", "hysteria2" to "Hysteria2",
        "hy2" to "Hysteria2", "tuic" to "TUIC", "anytls" to "AnyTLS",
        // WireGuard legacy outbound was removed by sing-box before the bundled 1.14.x engine.
    )

    fun parse(input: String): List<Profile> {
        val trimmed = input.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val config = JSONObject(trimmed)
                val outbounds = config.optJSONArray("outbounds")
                    ?: throw IllegalArgumentException("В JSON не найден массив outbounds")
                require(outbounds.length() > 0) { "В JSON нет ни одного outbound" }
                listOf(Profile(
                    trimmed.hashCode().toString(),
                    config.optString("remarks").ifBlank { "Пользовательская sing-box конфигурация" },
                    "sing-box JSON",
                    trimmed,
                    "Выходов: ${outbounds.length()}"
                ))
            } catch (e: Exception) {
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

    fun parseSubscription(input: String, sourceUrl: String): List<Profile> {
        val original = input.trim()
        require(original.isNotEmpty()) { "Подписка пуста" }
        var parsed = when {
            original.startsWith("{") && sourceUrl != "local-import" -> parseSingBoxSubscription(original)
            original.contains("proxies:") -> ClashYamlParser.parse(original)
            else -> parse(original)
        }
        if (parsed.isEmpty() && !original.startsWith("{")) {
            val decoded = runCatching {
                String(Base64.decode(original.replace("\\s".toRegex(), ""), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) parsed = parse(decoded)
        }
        require(parsed.isNotEmpty()) { "Подписка не содержит поддерживаемых профилей" }
        return parsed.distinctBy { it.raw }.map { it.copy(id = (sourceUrl + it.raw).hashCode().toString(), subscriptionUrl = sourceUrl) }
    }

    private fun parseSingBoxSubscription(raw: String): List<Profile> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val supported = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria",
            "hysteria2", "tuic", "anytls")
        val profiles = mutableListOf<Profile>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val type = outbound.optString("type").lowercase()
            if (type !in supported) continue
            val tag = outbound.optString("tag").ifBlank { "$type-${index + 1}" }
            val host = outbound.optString("server").ifBlank { "unknown" }
            val port = outbound.optInt("server_port", 0)
            val isolated = JSONObject(raw)
            val isolatedOutbounds = JSONArray()
                .put(JSONObject(outbound.toString()).put("tag", "proxy"))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
            isolated.put("outbounds", isolatedOutbounds)
            isolated.put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true))
            val config = isolated.toString()
            profiles += Profile(config.hashCode().toString(), tag, type.uppercase(), config,
                if (port in 1..65535) "$host:$port" else host)
        }
        return profiles
    }

    private fun parseOne(raw: String): Profile? {
        return try {
        val scheme = raw.substringBefore("://").lowercase()
        val protocol = supportedSchemes[scheme] ?: return null
        if (scheme == "vmess") {
            val payload = raw.removePrefix("vmess://").substringBefore('#')
            val decoded = try {
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            } catch (_: Exception) {
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            }
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
