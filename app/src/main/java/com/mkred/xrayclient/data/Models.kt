package com.mkred.xrayclient.data

import java.security.MessageDigest

enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS }

/** Раздельное туннелирование. */
enum class SplitMode {
    /** Через VPN идут все приложения. */
    ALL,

    /** Через VPN идут только выбранные. */
    ONLY_SELECTED,

    /** Через VPN идут все, кроме выбранных. */
    BYPASS_SELECTED,
}

/** Как выбирать сервер при подключении и во время работы. */
enum class SelectionMode {
    /** Самый быстрый из рабочих. */
    FASTEST,

    /** Первый рабочий по порядку, который задал пользователь. */
    PRIORITY,

    /** Только выбранный вручную. */
    MANUAL,
}

/**
 * Один сервер. Хранится как исходная ссылка (vless://…), а поля ниже
 * получаются из неё парсером — так формат хранения не ломается
 * при добавлении новых параметров.
 */
data class Profile(
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    /** UUID для VLESS/VMess, пароль для Trojan/Shadowsocks. */
    val secret: String,
    /** Шифрование: encryption для VLESS, security для VMess, method для SS. */
    val method: String? = null,
    val flow: String? = null,
    val network: String = "tcp",
    val security: String = "none",
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    val mode: String? = null,
    val headerType: String? = null,
    val allowInsecure: Boolean = false,
    val link: String,
    val subscriptionId: String? = null,
) {
    /**
     * Стабильный идентификатор: не зависит от названия и порядка в подписке,
     * поэтому приоритеты сохраняются после обновления подписки.
     */
    val id: String by lazy {
        val key = listOf(protocol, address, port, secret, network, security, sni, path, serviceName)
            .joinToString("|")
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    val subtitle: String
        get() = buildString {
            append(protocol.name.lowercase().replaceFirstChar { it.uppercase() })
            if (security == "reality") append(" · Reality")
            else if (security == "tls") append(" · TLS")
            if (network != "tcp") append(" · ").append(network)
        }
}

data class SubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Unix-время окончания, секунды. 0 — не указано. */
    val expire: Long = 0,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
    val info: SubscriptionInfo? = null,
    val lastError: String? = null,
    /** Выключенная подписка обновляется как обычно, но её серверы не используются. */
    val enabled: Boolean = true,
    /** Серверы, в названии которых есть любое из этих слов, не используются. */
    val excludeKeywords: List<String> = emptyList(),
)

data class StoredProfile(
    val link: String,
    val subscriptionId: String?,
)

data class AppData(
    val subscriptions: List<Subscription> = emptyList(),
    val profiles: List<StoredProfile> = emptyList(),
    val mode: SelectionMode = SelectionMode.FASTEST,
    /** Сервер для ручного режима. */
    val selectedId: String? = null,
    /** Порядок серверов для режима «Приоритет» (id профилей). */
    val priority: List<String> = emptyList(),
    val testUrl: String = "https://www.gstatic.com/generate_204",
    /** Как часто проверять серверы во время работы, секунды. */
    val checkIntervalSec: Int = 30,
    /** Вручную выключенные серверы (id профилей). Сохраняются при обновлении подписки. */
    val excluded: Set<String> = emptySet(),
    val splitMode: SplitMode = SplitMode.ALL,
    /** Пакеты приложений для раздельного туннелирования. */
    val splitApps: Set<String> = emptySet(),
    /** Подробность лога ядра Xray: warning, info или debug. */
    val logLevel: String = "info",
)
