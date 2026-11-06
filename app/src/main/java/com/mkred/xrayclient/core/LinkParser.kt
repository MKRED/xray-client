package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Protocol
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * Разбор ссылок vless://, vmess://, trojan://, ss:// и содержимого подписок.
 */
object LinkParser {

    /** Текст подписки: либо base64 со списком ссылок, либо сами ссылки построчно. */
    fun parseMany(text: String): List<Profile> {
        val body = text.trim()
        val plain = if (body.contains("://")) body else decodeBase64(body) ?: body
        return plain.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
            .toList()
    }

    fun parse(link: String): Profile? = runCatching {
        when (link.substringBefore("://").lowercase()) {
            "vless" -> parseStandard(link, Protocol.VLESS)
            "trojan" -> parseStandard(link, Protocol.TROJAN)
            "vmess" -> parseVmess(link)
            "ss" -> parseShadowsocks(link)
            else -> null
        }
    }.getOrNull()

    // ---- vless:// и trojan:// : scheme://secret@host:port?query#name ----

    private fun parseStandard(link: String, protocol: Protocol): Profile? {
        val u = SimpleUri.parse(link) ?: return null
        val q = u.query
        val security = q["security"] ?: if (protocol == Protocol.TROJAN) "tls" else "none"
        return Profile(
            name = u.fragment.ifEmpty { "${u.host}:${u.port}" },
            protocol = protocol,
            address = u.host,
            port = u.port,
            secret = u.userInfo,
            method = if (protocol == Protocol.VLESS) q["encryption"] ?: "none" else null,
            flow = q["flow"],
            network = q["type"] ?: "tcp",
            security = security,
            sni = q["sni"] ?: q["peer"],
            fingerprint = q["fp"],
            alpn = q["alpn"],
            publicKey = q["pbk"],
            shortId = q["sid"],
            spiderX = q["spx"],
            path = q["path"],
            host = q["host"],
            serviceName = q["serviceName"],
            mode = q["mode"],
            headerType = q["headerType"],
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
            link = link,
        )
    }

    // ---- vmess:// + base64(JSON) ----

    private fun parseVmess(link: String): Profile? {
        val json = decodeBase64(link.substringAfter("://")) ?: return null
        val o = JSONObject(json)
        val tls = o.optString("tls")
        return Profile(
            name = o.optString("ps").ifEmpty { o.optString("add") },
            protocol = Protocol.VMESS,
            address = o.getString("add"),
            port = o.optString("port").toInt(),
            secret = o.getString("id"),
            method = o.optString("scy").ifEmpty { "auto" },
            network = o.optString("net").ifEmpty { "tcp" },
            security = if (tls.isEmpty()) "none" else tls,
            sni = o.optString("sni").ifEmpty { null },
            fingerprint = o.optString("fp").ifEmpty { null },
            alpn = o.optString("alpn").ifEmpty { null },
            path = o.optString("path").ifEmpty { null },
            host = o.optString("host").ifEmpty { null },
            serviceName = o.optString("path").ifEmpty { null }.takeIf { o.optString("net") == "grpc" },
            headerType = o.optString("type").ifEmpty { null },
            link = link,
        )
    }

    // ---- ss:// : SIP002 (ss://base64(method:pass)@host:port#name) или старый формат ----

    private fun parseShadowsocks(link: String): Profile? {
        val rest = link.substringAfter("://")
        val fragment = rest.substringAfter('#', "").let(::urlDecode)
        val main = rest.substringBefore('#').substringBefore('?')
        val full = if ('@' in main) main else decodeBase64(main) ?: return null
        val userPart = full.substringBeforeLast('@')
        val hostPart = full.substringAfterLast('@')
        val creds = if (':' in userPart) urlDecode(userPart) else decodeBase64(userPart) ?: return null
        val (host, port) = splitHostPort(hostPart) ?: return null
        return Profile(
            name = fragment.ifEmpty { "$host:$port" },
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            secret = creds.substringAfter(':'),
            method = creds.substringBefore(':'),
            link = link,
        )
    }

    // ---- helpers ----

    fun decodeBase64(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "")
            .replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val host: String
        val port: String
        if (s.startsWith("[")) {
            host = s.substring(1, s.indexOf(']'))
            port = s.substringAfter("]:", "")
        } else {
            host = s.substringBeforeLast(':')
            port = s.substringAfterLast(':', "")
        }
        val p = port.trimEnd('/').toIntOrNull() ?: return null
        return host to p
    }

    /**
     * Свой мини-парсер: java.net.URI падает на пробелах и кириллице
     * в названии сервера, которые часто встречаются в подписках.
     */
    private class SimpleUri(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    ) {
        companion object {
            fun parse(link: String): SimpleUri? {
                val afterScheme = link.substringAfter("://")
                val fragment = afterScheme.substringAfter('#', "").let { LinkParser.urlDecode(it) }
                val noFragment = afterScheme.substringBefore('#')
                val queryStr = noFragment.substringAfter('?', "")
                val authority = noFragment.substringBefore('?').trimEnd('/')
                val userInfo = LinkParser.urlDecode(authority.substringBeforeLast('@'))
                val (host, port) = LinkParser.splitHostPort(authority.substringAfterLast('@')) ?: return null
                val query = queryStr.split('&').filter { '=' in it }.associate {
                    it.substringBefore('=') to LinkParser.urlDecode(it.substringAfter('='))
                }.filterValues { it.isNotEmpty() }
                return SimpleUri(userInfo, host, port, query, fragment)
            }
        }
    }
}
