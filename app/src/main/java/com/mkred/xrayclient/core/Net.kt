package com.mkred.xrayclient.core

import com.mkred.xrayclient.log.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

/**
 * Сетевые запросы самого приложения (обновления, подписки).
 *
 * Приложение исключено из VPN, поэтому без этого его запросы шли бы напрямую через
 * провайдера. Пока VPN подключён, ядро Xray поднимает локальный HTTP-прокси на
 * 127.0.0.1 — через него запросы идут в туннель. Если не вышло, пробуем напрямую.
 */
object Net {
    private const val TAG = "Net"

    /** Порт локального прокси ядра; null — VPN не подключён. */
    @Volatile
    var proxyPort: Int? = null

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    class HttpError(val code: Int) : IOException("HTTP $code")

    /**
     * Выполнить запрос: сначала через VPN (если он включён), затем напрямую.
     * [block] получает открытое соединение с кодом 2xx.
     */
    fun <T> request(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 15_000,
        readTimeout: Int = 20_000,
        block: (HttpURLConnection) -> T,
    ): T {
        val routes = buildList {
            proxyPort?.let { add("через VPN" to Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))) }
            add("напрямую" to Proxy.NO_PROXY)
        }
        var last: Exception? = null
        for ((name, proxy) in routes) {
            val conn = URL(url).openConnection(proxy) as HttpURLConnection
            try {
                conn.connectTimeout = connectTimeout
                conn.readTimeout = readTimeout
                conn.instanceFollowRedirects = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code !in 200..299) throw HttpError(code)
                AppLog.d(TAG, "GET ${conn.url.host} $name: HTTP $code")
                return block(conn)
            } catch (e: Exception) {
                AppLog.w(TAG, "GET ${URL(url).host} $name: ${e.message}")
                last = e
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: IOException("нет сети")
    }
}
