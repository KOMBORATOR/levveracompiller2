package com.levver.vpn

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.net.URI
import java.net.URLDecoder

/**
 * Converts supported user-supplied share links into a minimal sing-box config.
 * Initial URI coverage: VLESS (TLS/Reality, TCP/WS/gRPC) and Hysteria2.
 * This is a client-side config adapter; it does not provide VPN servers.
 */
object SingBoxConfigBuilder {
    fun build(profile: Profile): String {
        val raw = profile.raw.trim()
        if (raw.startsWith("{")) {
            val config = JSONObject(raw)
            val inbounds = config.optJSONArray("inbounds") ?: error("В JSON нет inbounds")
            require((0 until inbounds.length()).any { inbounds.getJSONObject(it).optString("type") == "tun" }) {
                "В sing-box JSON нужен inbound типа tun"
            }
            require(config.optJSONArray("outbounds")?.length()?.let { it > 0 } == true) {
                "В JSON нет outbounds"
            }
            return config.toString()
        }

        if (raw.startsWith("vmess://", ignoreCase = true)) return baseConfig(vmess(raw)).toString()
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: error("Неизвестная схема ссылки")
        val query = parseQuery(uri.rawQuery)
        val host = uri.host ?: error("В ссылке не указан сервер")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Некорректный порт сервера")
        val outbound = when (scheme) {
            "vless" -> vless(uri, query, host, port)
            "hysteria2", "hy2" -> hysteria2(uri, query, host, port)
            "trojan" -> trojan(uri, query, host, port)
            "ss" -> shadowsocks(uri, host, port)
            "socks", "socks5", "socks5h" -> socks(uri, host, port)
            "tuic" -> tuic(uri, query, host, port)
            "anytls" -> anytls(uri, query, host, port)
            else -> error("Формат $scheme пока не поддерживается движком LEVVER.")
        }
        return baseConfig(outbound).toString()
    }


    private fun vmess(raw: String): JSONObject {
        val encoded=raw.substringAfter("://").substringBefore('#')
        val decoded=try { String(Base64.decode(encoded,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8) }
            catch (_:Exception) { String(Base64.decode(encoded,Base64.DEFAULT),Charsets.UTF_8) }
        val data=JSONObject(decoded)
        val host=data.optString("add").takeIf { it.isNotBlank() } ?: error("VMess: нет адреса")
        val port=data.optString("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: error("VMess: неверный порт")
        val uuid=data.optString("id").takeIf { it.isNotBlank() } ?: error("VMess: нет UUID")
        val out=JSONObject().put("type","vmess").put("tag","proxy").put("server",host).put("server_port",port)
            .put("uuid",uuid).put("security",data.optString("scy").ifBlank { "auto" }).put("alter_id",data.optInt("aid",0))
        if(data.optString("tls").equals("tls",true)) {
            val tls=JSONObject().put("enabled",true)
            val sni=data.optString("sni").ifBlank { data.optString("host") }
            if(sni.isNotBlank()) tls.put("server_name",sni)
            out.put("tls",tls)
        }
        when(data.optString("net").lowercase()) {
            "", "tcp" -> Unit
            "ws" -> {
                val t=JSONObject().put("type","ws")
                if(data.optString("path").isNotBlank()) t.put("path",data.optString("path"))
                if(data.optString("host").isNotBlank()) t.put("headers",JSONObject().put("Host",data.optString("host")))
                out.put("transport",t)
            }
            "grpc" -> {
                val t=JSONObject().put("type","grpc")
                if(data.optString("path").isNotBlank()) t.put("service_name",data.optString("path"))
                out.put("transport",t)
            }
            else -> error("VMess: транспорт не поддерживается")
        }
        return out
    }

    private fun trojan(uri: URI, q: Map<String,String>, host: String, port: Int): JSONObject {
        val pass=decode(uri.rawUserInfo.orEmpty()).substringBefore(':').takeIf { it.isNotBlank() } ?: error("Trojan: нет пароля")
        val out=JSONObject().put("type","trojan").put("tag","proxy").put("server",host).put("server_port",port).put("password",pass)
        val tls=JSONObject().put("enabled",true)
        q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name",it) }
        q["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",it)) }
        if(q["insecure"]=="1" || q["allowInsecure"]=="1") tls.put("insecure",true)
        out.put("tls",tls)
        return out
    }

    private fun shadowsocks(uri: URI, host: String, port: Int): JSONObject {
        val auth=decode(uri.rawUserInfo.orEmpty())
        val method=auth.substringBefore(':').takeIf { it.isNotBlank() } ?: error("Shadowsocks: ссылка должна содержать method:password@host")
        val pass=auth.substringAfter(':',"").takeIf { it.isNotBlank() } ?: error("Shadowsocks: нет пароля")
        return JSONObject().put("type","shadowsocks").put("tag","proxy").put("server",host).put("server_port",port).put("method",method).put("password",pass)
    }

    private fun socks(uri: URI, host: String, port: Int): JSONObject {
        val out=JSONObject().put("type","socks").put("tag","proxy").put("server",host).put("server_port",port).put("version","5")
        uri.rawUserInfo?.let { raw -> val auth=decode(raw); if(auth.contains(':')) { out.put("username",auth.substringBefore(':')); out.put("password",auth.substringAfter(':')) } }
        return out
    }

    private fun tuic(uri: URI, q: Map<String,String>, host: String, port: Int): JSONObject {
        val c=decode(uri.rawUserInfo.orEmpty()).split(':',limit=2)
        require(c.size==2 && c.all { it.isNotBlank() }) { "TUIC: нужны UUID:пароль" }
        val out=JSONObject().put("type","tuic").put("tag","proxy").put("server",host).put("server_port",port)
            .put("uuid",c[0]).put("password",c[1]).put("congestion_control",q["congestion_control"] ?: "bbr")
            .put("udp_relay_mode",q["udp_relay_mode"] ?: "native")
        val tls=JSONObject().put("enabled",true)
        q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name",it) }
        out.put("tls",tls)
        return out
    }

    private fun anytls(uri: URI, q: Map<String,String>, host: String, port: Int): JSONObject {
        val pass=decode(uri.rawUserInfo.orEmpty()).takeIf { it.isNotBlank() } ?: error("AnyTLS: нет пароля")
        val out=JSONObject().put("type","anytls").put("tag","proxy").put("server",host).put("server_port",port).put("password",pass)
        val tls=JSONObject().put("enabled",true)
        q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name",it) }
        out.put("tls",tls)
        return out
    }

    private fun vless(uri: URI, q: Map<String, String>, host: String, port: Int): JSONObject {
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: error("В VLESS-ссылке отсутствует UUID")
        val out = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
        q["flow"]?.takeIf { it.isNotBlank() }?.let { out.put("flow", it) }

        val security = q["security"]?.lowercase().orEmpty()
        if (security == "tls" || security == "reality") {
            val tls = JSONObject().put("enabled", true)
            q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            if (q["allowInsecure"] == "1" || q["insecure"] == "1") tls.put("insecure", true)
            q["fp"]?.takeIf { it.isNotBlank() }?.let {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
            }
            if (security == "reality") {
                val publicKey = q["pbk"] ?: q["publickey"]
                    ?: error("Для Reality не указан публичный ключ pbk")
                val reality = JSONObject().put("enabled", true).put("public_key", publicKey)
                q["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
                tls.put("reality", reality)
            }
            q["alpn"]?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty)))
            }
            out.put("tls", tls)
        }

        val network = q["type"]?.lowercase() ?: "tcp"
        when (network) {
            "tcp" -> Unit
            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                q["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                q["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                out.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                q["serviceName"]?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                out.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                q["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                q["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
                out.put("transport", transport)
            }
            else -> error("Транспорт VLESS '$network' пока не поддерживается")
        }
        return out
    }

    private fun hysteria2(uri: URI, q: Map<String, String>, host: String, port: Int): JSONObject {
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: error("В Hysteria2-ссылке отсутствует пароль")
        val out = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
        val tls = JSONObject().put("enabled", true)
        q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
        if (q["insecure"] == "1" || q["allowInsecure"] == "1") tls.put("insecure", true)
        q["alpn"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty)))
        }
        out.put("tls", tls)
        q["obfs"]?.takeIf { it.isNotBlank() }?.let { method ->
            require(method == "salamander") { "Для Hysteria2 поддерживается obfs salamander" }
            val obfsPassword = q["obfs-password"] ?: q["obfs_password"]
                ?: error("Для salamander не указан obfs-password")
            out.put("obfs", JSONObject().put("type", "salamander").put("password", obfsPassword))
        }
        return out
    }

    private fun baseConfig(outbound: JSONObject): JSONObject {
        val outbounds = JSONArray().put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "levver-tun")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("mtu", 1400)
            .put("auto_route", true)
            .put("strict_route", true)
        val dnsServer = JSONObject()
            .put("type", "udp")
            .put("tag", "dns-remote")
            .put("server", "1.1.1.1")
        val dns = JSONObject().put("servers", JSONArray().put(dnsServer)).put("final", "dns-remote")
        val route = JSONObject().put("auto_detect_interface", true).put("final", "proxy")
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0])
            val value = decode(pieces.getOrElse(1) { "" })
            key to value
        }.toMap()
    }

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
