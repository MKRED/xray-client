package com.mkred.xrayclient.core

import com.mkred.xrayclient.log.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom

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

    /**
     * Логин и пароль локальных прокси (рабочее ядро и тест скорости).
     * Без них порт на 127.0.0.1 мог бы использовать любой процесс на телефоне — в том числе
     * приложения, которые идут мимо VPN или ищут открытые прокси, чтобы обнаружить VPN.
     * Генерируются заново при каждом запуске процесса и нигде не сохраняются.
     */
    val proxyUser: String = randomToken(12)
    val proxyPass: String = randomToken(24)

    private fun randomToken(len: Int): String {
        val abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return String(CharArray(len) { abc[rnd.nextInt(abc.length)] })
    }

    /** Отдаёт логин и пароль только на запрос авторизации от локального прокси. */
    fun installProxyAuth() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val local = requestingHost == "127.0.0.1" || requestingSite?.isLoopbackAddress == true
                if (requestorType != RequestorType.PROXY || !local) return null
                return PasswordAuthentication(proxyUser, proxyPass.toCharArray())
            }
        })
    }

    /** Блок settings для HTTP-входа Xray с авторизацией. */
    fun inboundSettings(): org.json.JSONObject = org.json.JSONObject().apply {
        put("accounts", org.json.JSONArray().put(org.json.JSONObject().put("user", proxyUser).put("pass", proxyPass)))
        put("allowTransparent", false)
    }

    /** Ошибка авторизации на локальном прокси — это сбой приложения, а не медленный сервер. */
    fun isProxyAuthError(e: Throwable?): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("407") == true }

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
