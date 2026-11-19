package com.levver.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class Profile(val id:String,val name:String,val protocol:String,val raw:String,val server:String)
object ProfileParser {
 fun parse(input:String):List<Profile> = input.trim().lines().flatMap { line ->
  val s=line.trim(); if(s.isBlank()) emptyList() else if(s.startsWith("vmess://")) listOf(s) else s.split(Regex("\\s+")).filter { it.contains("://") }
 }.mapNotNull { parseOne(it) }
 private fun parseOne(raw:String):Profile? = try {
  when {
   raw.startsWith("vless://")->fromUri(raw,"VLESS")
   raw.startsWith("trojan://")->fromUri(raw,"Trojan")
   raw.startsWith("ss://")->fromUri(raw,"Shadowsocks")
   raw.startsWith("socks://")||raw.startsWith("socks5://")->fromUri(raw,"SOCKS5")
   raw.startsWith("vmess://")-> { val decoded=String(Base64.decode(raw.removePrefix("vmess://"),Base64.DEFAULT),Charsets.UTF_8); val j=JSONObject(decoded); val host=j.optString("add","unknown"); Profile(raw.hashCode().toString(),j.optString("ps").ifBlank { "VMess — $host" },"VMess",raw,host) }
   else->null
  }
 } catch(_:Exception){null}
 private fun fromUri(raw:String,protocol:String):Profile { val u=URI(raw); val host=u.host?:"unknown"; val label=u.rawFragment?.let{URLDecoder.decode(it,"UTF-8")}; return Profile(raw.hashCode().toString(),label?.takeIf{it.isNotBlank()}?:"$protocol — $host",protocol,raw,host) }
}
