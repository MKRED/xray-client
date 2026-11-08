package com.mkred.xrayclient.data

import com.mkred.xrayclient.core.LinkParser
import com.mkred.xrayclient.core.Net
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

object Subscriptions {
    private const val TAG = "Subs"

    /** Добавить подписку и сразу скачать серверы. */
    suspend fun add(url: String, name: String?): Result<Int> {
        val sub = Subscription(
            id = UUID.randomUUID().toString().take(8),
            name = name?.takeIf { it.isNotBlank() } ?: (runCatching { URL(url).host }.getOrNull() ?: "Подписка"),
            url = url.trim(),
        )
        AppStore.update { it.copy(subscriptions = it.subscriptions + sub) }
        return refresh(sub.id)
    }

    fun remove(id: String) = AppStore.update { d ->
        d.copy(
            subscriptions = d.subscriptions.filter { it.id != id },
            profiles = d.profiles.filter { it.subscriptionId != id },
        )
    }

    suspend fun refreshAll(): List<Result<Int>> =
        AppStore.data.value.subscriptions.map { refresh(it.id) }

    /** Скачать подписку и заменить её серверы. Возвращает число серверов. */
    suspend fun refresh(id: String): Result<Int> {
        val sub = AppStore.data.value.subscriptions.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Подписка не найдена"))
        return runCatching {
            val (body, info) = download(sub.url)
            val parsed = LinkParser.parseMany(body)
            AppLog.i(TAG, "«${sub.name}»: получено ${body.length} байт, распознано серверов ${parsed.size}")
            check(parsed.isNotEmpty()) { "В подписке нет поддерживаемых серверов" }
            AppStore.update { d ->
                d.copy(
                    profiles = d.profiles.filter { it.subscriptionId != id } +
                        parsed.map { StoredProfile(it.link, id) },
                    subscriptions = d.subscriptions.map {
                        if (it.id == id) it.copy(updatedAt = System.currentTimeMillis(), info = info, lastError = null) else it
                    },
                )
            }
            parsed.size
        }.onFailure { e ->
            AppLog.w(TAG, "«${sub.name}» не обновилась: ${e.message}", e)
            AppStore.update { d ->
                d.copy(subscriptions = d.subscriptions.map {
                    if (it.id == id) it.copy(lastError = e.message ?: e.javaClass.simpleName) else it
                })
            }
        }
    }

    /** Добавить одиночную ссылку (vless://… и т.п.) или несколько ссылок построчно. */
    fun addLinks(text: String): Int {
        val parsed = LinkParser.parseMany(text)
        AppLog.i(TAG, "Добавление ссылок вручную: распознано ${parsed.size}")
        if (parsed.isNotEmpty()) {
            AppStore.update { d -> d.copy(profiles = d.profiles + parsed.map { StoredProfile(it.link, null) }) }
        }
        return parsed.size
    }

    fun removeProfile(id: String) = AppStore.update { d ->
        d.copy(profiles = d.profiles.filter { s -> LinkParser.parse(s.link)?.id != id })
    }

    private suspend fun download(url: String): Pair<String, SubscriptionInfo?> = withContext(Dispatchers.IO) {
        // Многие панели (Marzban, 3x-ui, Remnawave) отдают нужный формат по User-Agent
        Net.request(url, headers = mapOf("User-Agent" to "v2rayNG/2.0")) { conn ->
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body to conn.getHeaderField("subscription-userinfo")?.let(::parseUserInfo)
        }
    }

    /** Заголовок вида: upload=123; download=456; total=789; expire=1700000000 */
    private fun parseUserInfo(h: String): SubscriptionInfo {
        val m = h.split(';').mapNotNull {
            val k = it.substringBefore('=').trim()
            val v = it.substringAfter('=', "").trim().toLongOrNull()
            if (v == null) null else k to v
        }.toMap()
        return SubscriptionInfo(m["upload"] ?: 0, m["download"] ?: 0, m["total"] ?: 0, m["expire"] ?: 0)
    }
}
