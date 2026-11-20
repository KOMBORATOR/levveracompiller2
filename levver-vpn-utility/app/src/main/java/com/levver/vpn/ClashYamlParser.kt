package com.levver.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml

/** Imports individual proxy entries from common Clash subscription YAML files. */
object ClashYamlParser {
    fun parse(source: String): List<Profile> {
        val document = runCatching { Yaml().load<Any?>(source) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val proxies = document["proxies"] as? List<*> ?: return emptyList()
        return proxies.mapNotNull { item ->
            val p = item as? Map<*, *> ?: return@mapNotNull null
            runCatching { toProfile(p) }.getOrNull()
        }
    }

    private fun toProfile(p: Map<*, *>): Profile? {
        fun value(key: String) = p[key]?.toString()?.trim().orEmpty()
        fun bool(key: String) = value(key).equals("true", true)
        fun number(key: String, default: Int = 0) = Regex("\\d+").find(value(key))?.value?.toIntOrNull() ?: default
        val type = value("type").lowercase()
        val host = value("server")
        val port = number("port")
        if (host.isBlank() || port !in 1..65535) return null
        val name = value("name").ifBlank { "$type — $host" }
        val out = JSONObject().put("tag", "proxy").put("server", host).put("server_port", port)
        when (type) {
            "vless" -> {
                val uuid = value("uuid")
                if (uuid.isBlank()) return null
                out.put("type", "vless").put("uuid", uuid)
                value("flow").takeIf { it.isNotBlank() }?.let { out.put("flow", it) }
                val reality = p["reality-opts"] as? Map<*, *>
                val tls = tls(p, reality != null)
                reality?.let {
                    val realityConfig = JSONObject().put("enabled", true)
                    val publicKey = it["public-key"]?.toString()?.takeIf(String::isNotBlank) ?: return null
                    realityConfig.put("public_key", publicKey)
                    it["short-id"]?.toString()?.takeIf(String::isNotBlank)?.let { id -> realityConfig.put("short_id", id) }
                    tls.put("reality", realityConfig)
                }
                if (tls.optBoolean("enabled")) out.put("tls", tls)
                addTransport(out, value("network"), p)
            }
            "vmess" -> {
                val uuid = value("uuid")
                if (uuid.isBlank()) return null
                out.put("type", "vmess").put("uuid", uuid)
                    .put("security", value("cipher").ifBlank { "auto" })
                    .put("alter_id", number("alterId"))
                val tls = tls(p, false)
                if (tls.optBoolean("enabled")) out.put("tls", tls)
                addTransport(out, value("network"), p)
            }
            "trojan" -> {
                val password = value("password")
                if (password.isBlank()) return null
                out.put("type", "trojan").put("password", password)
                out.put("tls", tls(p, false).put("enabled", true))
            }
            "ss" -> {
                if (p["plugin"] != null) return null
                val method = value("cipher")
                val password = value("password")
                if (method.isBlank() || password.isBlank()) return null
                out.put("type", "shadowsocks").put("method", method).put("password", password)
            }
            "socks5" -> {
                out.put("type", "socks").put("version", "5")
                value("username").takeIf { it.isNotBlank() }?.let { out.put("username", it) }
                value("password").takeIf { it.isNotBlank() }?.let { out.put("password", it) }
            }
            "hysteria2", "hy2" -> {
                val password = value("password")
                if (password.isBlank()) return null
                out.put("type", "hysteria2").put("password", password)
                out.put("tls", tls(p, false).put("enabled", true))
                val obfsType = value("obfs")
                val obfsPassword = value("obfs-password")
                if (obfsType == "salamander" && obfsPassword.isNotBlank()) {
                    out.put("obfs", JSONObject().put("type", "salamander").put("password", obfsPassword))
                }
            }
            "hysteria" -> {
                val up = number("up")
                val down = number("down")
                if (up <= 0 || down <= 0) return null
                out.put("type", "hysteria").put("up_mbps", up).put("down_mbps", down)
                value("auth-str").takeIf { it.isNotBlank() }?.let { out.put("auth_str", it) }
                out.put("tls", tls(p, false).put("enabled", true))
            }
            "tuic" -> {
                val uuid = value("uuid")
                val password = value("password")
                if (uuid.isBlank() || password.isBlank()) return null
                out.put("type", "tuic").put("uuid", uuid).put("password", password)
                    .put("congestion_control", value("congestion-controller").ifBlank { "bbr" })
                    .put("udp_relay_mode", value("udp-relay-mode").ifBlank { "native" })
                    .put("tls", tls(p, false).put("enabled", true))
            }
            else -> return null
        }
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true))
        return Profile(config.toString().hashCode().toString(), name, type.uppercase(),
            config.toString(), "$host:$port")
    }

    private fun tls(p: Map<*, *>, reality: Boolean): JSONObject {
        val tls = JSONObject().put("enabled", reality || p["tls"]?.toString().equals("true", true))
        val sni = (p["servername"] ?: p["sni"])?.toString().orEmpty()
        if (sni.isNotBlank()) tls.put("server_name", sni)
        if (p["skip-cert-verify"]?.toString().equals("true", true)) tls.put("insecure", true)
        val fingerprint = p["client-fingerprint"]?.toString().orEmpty()
        if (fingerprint.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        return tls
    }

    private fun addTransport(out: JSONObject, network: String, p: Map<*, *>) {
        when (network.lowercase()) {
            "", "tcp" -> Unit
            "ws" -> {
                val options = p["ws-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
                val transport = JSONObject().put("type", "ws")
                options["path"]?.toString()?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                val headers = options["headers"] as? Map<*, *>
                headers?.get("Host")?.toString()?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                out.put("transport", transport)
            }
            "grpc" -> {
                val options = p["grpc-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
                val service = options["grpc-service-name"]?.toString().orEmpty()
                val transport = JSONObject().put("type", "grpc")
                if (service.isNotBlank()) transport.put("service_name", service)
                out.put("transport", transport)
            }
            else -> error("Clash transport $network is not supported")
        }
    }
}
