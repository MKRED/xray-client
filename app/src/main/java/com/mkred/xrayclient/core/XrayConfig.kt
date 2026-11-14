package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Protocol
import org.json.JSONArray
import org.json.JSONObject

/** Сборка JSON-конфига Xray из профиля. */
object XrayConfig {

    const val MTU = 1500

    /** Полный конфиг для работы VPN: TUN-вход → прокси. */
    /**
     * @param localPort порт локального HTTP-прокси для запросов самого приложения
     *                  (обновления, подписки): приложение исключено из туннеля.
     */
    fun forVpn(p: Profile, logLevel: String = "info", localPort: Int? = null, bypassRu: Boolean = false): String = JSONObject().apply {
        put("log", JSONObject().apply {
            put("loglevel", logLevel)
            // Журнал посещённых адресов — только в подробном режиме
            if (logLevel != "debug") put("access", "none")
            put("dnsLog", logLevel == "debug")
        })
        put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "tun")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("mtu", MTU)
                put("userLevel", 8)
            })
            put("sniffing", sniffing())
        }).apply {
            if (localPort != null) put(JSONObject().apply {
                put("tag", "app-local")
                put("listen", "127.0.0.1")
                put("port", localPort)
                put("protocol", "http")
                put("settings", Net.inboundSettings().put("userLevel", 8))
            })
        })
        put("outbounds", JSONArray().apply {
            put(outbound(p))
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        })
        put("routing", JSONObject().apply {
            // Для правил по IP домен нужно разрешить; без обхода РФ это не требуется
            put("domainStrategy", if (bypassRu) "IPIfNonMatch" else "AsIs")
            put("rules", JSONArray().apply {
                // Запросы самого приложения (обновления, подписки) — всегда через туннель
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray(listOf("app-local")))
                    put("outboundTag", "proxy")
                })
                if (bypassRu) ruRules().forEach { put(it) }
                // Локальная сеть — напрямую
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10")))
                    put("outboundTag", "direct")
                })
            })
        })
        // Счётчики трафика по исходящим — для статистики
        put("stats", JSONObject())
        put("policy", JSONObject().apply {
            put("levels", JSONObject().put("8", JSONObject().apply {
                put("handshake", 4); put("connIdle", 300); put("uplinkOnly", 1); put("downlinkOnly", 1)
            }))
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })
    }.toString()

    /**
     * Российские сайты напрямую. Порядок важен: сначала заблокированные в РФ
     * домены отправляем в туннель, потом российские домены и IP — напрямую.
     */
    private fun ruRules(): List<JSONObject> {
        fun site(c: String) = "ext:${GeoRules.SITE_FILE}:$c"
        fun rule(key: String, values: List<String>, out: String) = JSONObject().apply {
            put("type", "field")
            put(key, JSONArray(values))
            put("outboundTag", out)
        }
        return listOf(
            rule("domain", listOf(site("ru-blocked")), "proxy"),
            rule("domain", listOf(site("category-ru"), site("tld-ru"), site("ru-available-only-inside")), "direct"),
            rule("ip", listOf("ext:${GeoRules.IP_FILE}:ru"), "direct"),
        )
    }

    /** Конфиг для теста скорости: локальный HTTP-вход → сервер. */
    fun forSpeedTest(p: Profile, port: Int): String = JSONObject().apply {
        put("log", JSONObject().put("loglevel", "none"))
        put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "speed-in")
            put("listen", "127.0.0.1")
            put("port", port)
            put("protocol", "http")
            put("settings", Net.inboundSettings())
        }))
        put("outbounds", JSONArray().put(outbound(p)))
    }.toString()

    /** Минимальный конфиг для проверки задержки: только исходящий прокси. */
    fun forTest(p: Profile): String = JSONObject().apply {
        put("log", JSONObject().put("loglevel", "none"))
        put("outbounds", JSONArray().put(outbound(p)))
    }.toString()

    private fun sniffing() = JSONObject().apply {
        put("enabled", true)
        put("destOverride", JSONArray(listOf("http", "tls", "quic")))
    }

    fun outbound(p: Profile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", p.protocol.name.lowercase())
        put("settings", JSONObject().apply {
            put("address", p.address)
            put("port", p.port)
            put("level", 8)
            when (p.protocol) {
                Protocol.VLESS -> {
                    put("id", p.secret)
                    put("encryption", p.method ?: "none")
                    p.flow?.let { put("flow", it) }
                }
                Protocol.VMESS -> {
                    put("id", p.secret)
                    put("security", p.method ?: "auto")
                }
                Protocol.TROJAN -> put("password", p.secret)
                Protocol.SHADOWSOCKS -> {
                    put("password", p.secret)
                    put("method", p.method)
                }
            }
        })
        if (p.protocol != Protocol.SHADOWSOCKS) put("streamSettings", stream(p))
    }

    private fun stream(p: Profile) = JSONObject().apply {
        val network = when (p.network) {
            "h2", "http" -> "xhttp"
            else -> p.network
        }
        put("network", network)
        put("security", p.security)

        when (p.security) {
            "tls" -> put("tlsSettings", JSONObject().apply {
                put("serverName", p.sni ?: p.host ?: p.address)
                p.fingerprint?.let { put("fingerprint", it) }
                p.alpn?.let { put("alpn", JSONArray(it.split(','))) }
            })
            "reality" -> put("realitySettings", JSONObject().apply {
                put("serverName", p.sni ?: "")
                put("fingerprint", p.fingerprint ?: "chrome")
                put("publicKey", p.publicKey ?: "")
                p.shortId?.let { put("shortId", it) }
                p.spiderX?.let { put("spiderX", it) }
            })
        }

        when (network) {
            "ws" -> put("wsSettings", JSONObject().apply {
                put("path", p.path ?: "/")
                p.host?.let { put("host", it) }
            })
            "grpc" -> put("grpcSettings", JSONObject().apply {
                put("serviceName", p.serviceName ?: p.path ?: "")
                put("multiMode", p.mode == "multi")
            })
            "httpupgrade" -> put("httpupgradeSettings", JSONObject().apply {
                put("path", p.path ?: "/")
                p.host?.let { put("host", it) }
            })
            "xhttp" -> put("xhttpSettings", JSONObject().apply {
                put("path", p.path ?: "/")
                p.host?.let { put("host", it) }
                put("mode", p.mode ?: "auto")
            })
            "tcp", "raw" -> if (p.headerType == "http") {
                put("rawSettings", JSONObject().put("header", JSONObject().apply {
                    put("type", "http")
                    put("request", JSONObject().apply {
                        put("path", JSONArray(listOf(p.path ?: "/")))
                        p.host?.let { put("headers", JSONObject().put("Host", JSONArray(it.split(',')))) }
                    })
                }))
            }
        }
    }
}
