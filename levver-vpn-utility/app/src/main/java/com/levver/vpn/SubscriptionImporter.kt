package com.levver.vpn

import java.net.HttpURLConnection
import java.net.URL

/** Downloads user-supplied HTTPS subscription URLs with redirect, size and timeout limits. */
object SubscriptionImporter {
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun fetch(urlText: String): List<Profile> {
        var current = URL(urlText.trim())
        var redirects = 0
        while (true) {
            require(current.protocol == "https") {
                "Для защиты данных подписка должна использовать HTTPS."
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LEVVER-VPN-UTILITY/1.0.0")
                setRequestProperty("Accept", "text/plain, application/json, */*")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirects < MAX_REDIRECTS) { "Слишком много перенаправлений подписки." }
                    val location = connection.getHeaderField("Location")
                        ?: error("Сервер подписки вернул перенаправление без адреса.")
                    val next = URL(current, location)
                    require(next.protocol == "https") { "Перенаправление на незащищённый HTTP запрещено." }
                    current = next
                    redirects++
                    continue
                }
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
                return ProfileParser.parseSubscription(body, urlText.trim())
            } finally {
                connection.disconnect()
            }
        }
    }
}
