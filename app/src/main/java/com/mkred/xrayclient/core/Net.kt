package com.mkred.xrayclient.core

import com.mkred.xrayclient.log.AppLog
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

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

    /** Оставлено для совместимости: авторизация теперь передаётся сразу в каждом запросе. */
    fun installProxyAuth() = Unit

    private val proxyCredential: String by lazy { Credentials.basic(proxyUser, proxyPass) }

    /**
     * Логин и пароль прокси отправляются сразу, в первом же запросе (CONNECT для https).
     * Встроенный в Android HttpURLConnection так не умеет: он ждёт ответа 407 и повторяет
     * запрос в том же соединении, а Xray после 407 соединение закрывает.
     */
    private val preemptiveAuth = Authenticator { _, response ->
        if (response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", proxyCredential).build()
    }

    private val base: OkHttpClient by lazy { OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build() }

    /** Клиент через локальный прокси ядра на [port] (или напрямую, если null). */
    fun client(port: Int?, connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient = base.newBuilder().apply {
        connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        if (port != null) {
            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            proxyAuthenticator(preemptiveAuth)
        } else {
            proxy(Proxy.NO_PROXY)
        }
    }.build()

    /** Запрос; для http через прокси заголовок авторизации идёт прямо в запросе. */
    fun get(url: String, viaProxy: Boolean, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (viaProxy && url.startsWith("http://")) header("Proxy-Authorization", proxyCredential)
        }.build()

    /** Блок settings для HTTP-входа Xray с авторизацией. */
    fun inboundSettings(): org.json.JSONObject = org.json.JSONObject().apply {
        put("accounts", org.json.JSONArray().put(org.json.JSONObject().put("user", proxyUser).put("pass", proxyPass)))
        put("allowTransparent", false)
    }

    /** Ошибка авторизации на локальном прокси — это сбой приложения, а не медленный сервер. */
    fun isProxyAuthError(e: Throwable?): Boolean =
        generateSequence(e) { it.cause }.any { t ->
            val m = t.message.orEmpty()
            m.contains("407") || m.contains("authenticate with proxy", ignoreCase = true)
        }

    /** Ответ сервера для [request] — те же поля, что раньше брались у HttpURLConnection. */
    class Resp(private val r: Response) {
        val inputStream: InputStream get() = r.body!!.byteStream()
        val contentLengthLong: Long get() = r.body?.contentLength() ?: -1L
        val url: URL get() = r.request.url.toUrl()
        fun getHeaderField(name: String): String? = r.header(name)
    }

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
        block: (Resp) -> T,
    ): T {
        val routes = buildList {
            proxyPort?.let { add("через VPN" to it) }
            add("напрямую" to null)
        }
        var last: Exception? = null
        for ((name, port) in routes) {
            try {
                val call = client(port, connectTimeout.toLong(), readTimeout.toLong()).newCall(get(url, port != null, headers))
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpError(resp.code)
                    AppLog.d(TAG, "GET ${resp.request.url.host} $name: HTTP ${resp.code}")
                    return block(Resp(resp))
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "GET ${runCatching { URL(url).host }.getOrDefault("?")} $name: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("нет сети")
    }
}
