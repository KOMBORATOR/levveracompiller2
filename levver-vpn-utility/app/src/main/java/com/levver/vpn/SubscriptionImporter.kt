package com.levver.vpn

import java.net.HttpURLConnection
import java.net.URL

/** Downloads user-owned or user-authorized subscription URLs with strict size and timeout limits. */
object SubscriptionImporter {
    private const val MAX_BYTES = 5 * 1024 * 1024

    fun fetch(urlText: String): List<Profile> {
        val url = URL(urlText.trim())
        require(url.protocol == "https" || url.protocol == "http") {
            "Подписка должна иметь ссылку HTTP или HTTPS."
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LEVVER-VPN-UTILITY/1.0.0")
            setRequestProperty("Accept", "text/plain, application/json, */*")
        }
        try {
            val status = connection.responseCode
            require(status in 200..299) { "Сервер подписки ответил HTTP $status" }
            val declared = connection.contentLengthLong
            require(declared <= MAX_BYTES || declared < 0) { "Подписка больше 5 МБ." }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Подписка больше 5 МБ." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val body = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
            return ProfileParser.parseSubscription(body, url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
