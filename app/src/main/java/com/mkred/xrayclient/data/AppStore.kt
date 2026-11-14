package com.mkred.xrayclient.data

import android.content.Context
import com.mkred.xrayclient.core.LinkParser
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Всё состояние приложения: подписки, серверы, режим выбора, приоритеты.
 * Хранится одним JSON-файлом во внутренней памяти приложения.
 */
object AppStore {
    private const val TAG = "AppStore"
    private lateinit var file: File

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    /** Все серверы, включая исключённые. */
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    /** Серверы, которые участвуют в выборе (без исключённых). */
    private val _usable = MutableStateFlow<List<Profile>>(emptyList())
    val usable: StateFlow<List<Profile>> = _usable.asStateFlow()

    /** Результаты проверки: id профиля → задержка в мс, -1 — недоступен. */
    private val _delays = MutableStateFlow<Map<String, Long>>(emptyMap())
    val delays: StateFlow<Map<String, Long>> = _delays.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "data.json")
        val loaded = runCatching { if (file.exists()) fromJson(JSONObject(file.readText())) else AppData() }
            .onFailure { AppLog.e(TAG, "Не удалось прочитать data.json", it) }
            .getOrDefault(AppData())
        set(loaded, save = false)
        AppLog.i(TAG, "Загружено: подписок ${loaded.subscriptions.size}, серверов ${_profiles.value.size}, режим ${loaded.mode}")
    }

    @Synchronized
    fun update(transform: (AppData) -> AppData) {
        set(transform(_data.value), save = true)
    }

    private fun set(value: AppData, save: Boolean) {
        _data.value = value
        _profiles.value = value.profiles.mapNotNull { stored ->
            LinkParser.parse(stored.link)?.let { p ->
                p.copy(subscriptionId = stored.subscriptionId, name = stored.name ?: p.name)
            }
        }.distinctBy { it.id }
        _usable.value = _profiles.value.filterNot { isExcluded(it, value) }
        if (save) runCatching { file.writeText(toJson(value).toString()) }
            .onFailure { AppLog.e(TAG, "Не удалось сохранить data.json", it) }
    }

    fun setDelay(id: String, delay: Long) {
        _delays.value = _delays.value + (id to delay)
    }

    fun profile(id: String?): Profile? = id?.let { i -> _profiles.value.firstOrNull { it.id == i } }

    fun usableProfile(id: String?): Profile? = id?.let { i -> _usable.value.firstOrNull { it.id == i } }

    /** Исключён ли сервер: выключен вручную или попал под слово-фильтр своей подписки. */
    fun isExcluded(p: Profile, d: AppData = _data.value): Boolean {
        if (p.id in d.excluded) return true
        val sub = d.subscriptions.firstOrNull { it.id == p.subscriptionId }
        if (sub != null && !sub.enabled) return true
        val words = sub?.excludeKeywords.orEmpty()
        return words.any { it.isNotBlank() && p.name.contains(it.trim(), ignoreCase = true) }
    }

    /** Выключен ли сервер именно словом-фильтром (тогда переключатель не поможет). */
    fun excludedByKeyword(p: Profile, d: AppData = _data.value): String? =
        d.subscriptions.firstOrNull { it.id == p.subscriptionId }?.excludeKeywords.orEmpty()
            .firstOrNull { it.isNotBlank() && p.name.contains(it.trim(), ignoreCase = true) }

    fun setExcluded(ids: Collection<String>, excluded: Boolean) = update { d ->
        d.copy(excluded = if (excluded) d.excluded + ids else d.excluded - ids.toSet())
    }

    /** Переименовать сервер, добавленный вручную. */
    fun renameProfile(id: String, name: String) = update { d ->
        d.copy(profiles = d.profiles.map { s ->
            if (s.subscriptionId == null && LinkParser.parse(s.link)?.id == id) s.copy(name = name.trim().ifEmpty { null }) else s
        })
    }

    fun renameSubscription(id: String, name: String) = update { d ->
        d.copy(subscriptions = d.subscriptions.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })
    }

    fun setSpeedTestAllowed(id: String, allowed: Boolean) = update { d ->
        d.copy(subscriptions = d.subscriptions.map { if (it.id == id) it.copy(speedTestAllowed = allowed) else it })
    }

    /** Можно ли тестировать скорость сервера (свои серверы — всегда). */
    fun speedTestAllowed(p: Profile): Boolean =
        p.subscriptionId == null || _data.value.subscriptions.firstOrNull { it.id == p.subscriptionId }?.speedTestAllowed != false

    fun setSubscriptionEnabled(id: String, enabled: Boolean) = update { d ->
        d.copy(subscriptions = d.subscriptions.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun setExcludeKeywords(subscriptionId: String, words: List<String>) = update { d ->
        d.copy(subscriptions = d.subscriptions.map {
            if (it.id == subscriptionId) it.copy(excludeKeywords = words.map(String::trim).filter(String::isNotEmpty)) else it
        })
    }

    // ---- блоки приоритета ----

    fun saveBlock(b: PriorityBlock) = update { d ->
        val i = d.blocks.indexOfFirst { it.id == b.id }
        d.copy(blocks = if (i >= 0) d.blocks.toMutableList().also { it[i] = b } else d.blocks + b)
    }

    fun deleteBlock(id: String) = update { d -> d.copy(blocks = d.blocks.filter { it.id != id }) }

    fun moveBlock(id: String, delta: Int) = update { d ->
        val list = d.blocks.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@update d
        val to = (from + delta).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        d.copy(blocks = list)
    }

    // ---- JSON ----

    private fun toJson(d: AppData) = JSONObject().apply {
        put("subscriptions", JSONArray().apply {
            d.subscriptions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("name", s.name); put("url", s.url)
                    put("updatedAt", s.updatedAt)
                    s.lastError?.let { put("lastError", it) }
                    put("excludeKeywords", JSONArray(s.excludeKeywords))
                    put("enabled", s.enabled)
                    put("speedTest", s.speedTestAllowed)
                    s.info?.let { i ->
                        put("info", JSONObject().apply {
                            put("upload", i.upload); put("download", i.download)
                            put("total", i.total); put("expire", i.expire)
                        })
                    }
                })
            }
        })
        put("profiles", JSONArray().apply {
            d.profiles.forEach { p ->
                put(JSONObject().apply {
                    put("link", p.link)
                    p.subscriptionId?.let { put("sub", it) }
                    p.name?.let { put("name", it) }
                })
            }
        })
        put("mode", d.mode.name)
        d.selectedId?.let { put("selectedId", it) }
        put("blocks", JSONArray().apply {
            d.blocks.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("type", b.type.name)
                    b.subscriptionId?.let { put("sub", it) }
                    put("keywords", JSONArray(b.keywords))
                    b.serverId?.let { put("server", it) }
                    put("topN", b.topN)
                    b.maxPingMs?.let { put("maxPing", it) }
                    put("mobileOnly", b.mobileOnly)
                })
            }
        })
        put("excluded", JSONArray(d.excluded.toList()))
        put("testUrl", d.testUrl)
        put("checkIntervalSec", d.checkIntervalSec)
        put("logLevel", d.logLevel)
        put("bypassRu", d.bypassRu)
        put("metric", d.metric.name)
        put("speedTestMb", d.speedTestMb)
        put("sweep", d.sweep)
        put("sweepHours", d.sweepHours)
        put("sweepMobile", d.sweepMobile)
        put("sweepLastAt", d.sweepLastAt)
        put("sweepLastCount", d.sweepLastCount)
        put("autoCheck", d.autoCheck)
        put("qSpeedMax", d.qSpeedMax)
        put("qPingFull", d.qPingFull)
        put("qPingZero", d.qPingZero)
        put("slowMbps", d.slowMbps)
        put("geoUpdatedAt", d.geoUpdatedAt)
        put("splitMode", d.splitMode.name)
        put("splitApps", JSONArray(d.splitApps.toList()))
    }

    private fun fromJson(o: JSONObject): AppData {
        val subs = o.optJSONArray("subscriptions").objects().map { s ->
            Subscription(
                id = s.getString("id"),
                name = s.optString("name"),
                url = s.getString("url"),
                updatedAt = s.optLong("updatedAt"),
                lastError = s.optString("lastError").ifEmpty { null },
                excludeKeywords = s.optJSONArray("excludeKeywords").strings(),
                enabled = s.optBoolean("enabled", true),
                speedTestAllowed = s.optBoolean("speedTest", true),
                info = s.optJSONObject("info")?.let { i ->
                    SubscriptionInfo(i.optLong("upload"), i.optLong("download"), i.optLong("total"), i.optLong("expire"))
                },
            )
        }
        val profiles = o.optJSONArray("profiles").objects().map {
            StoredProfile(it.getString("link"), it.optString("sub").ifEmpty { null }, it.optString("name").ifEmpty { null })
        }
        val defaults = AppData()
        return AppData(
            subscriptions = subs,
            profiles = profiles,
            mode = runCatching { SelectionMode.valueOf(o.optString("mode")) }.getOrDefault(defaults.mode),
            selectedId = o.optString("selectedId").ifEmpty { null },
            blocks = o.optJSONArray("blocks").objects().mapNotNull { b ->
                val type = runCatching { BlockType.valueOf(b.getString("type")) }.getOrNull() ?: return@mapNotNull null
                PriorityBlock(
                    id = b.getString("id"),
                    type = type,
                    subscriptionId = b.optString("sub").ifEmpty { null },
                    keywords = b.optJSONArray("keywords").strings(),
                    serverId = b.optString("server").ifEmpty { null },
                    topN = b.optInt("topN", 5),
                    maxPingMs = if (b.has("maxPing")) b.getInt("maxPing") else null,
                    mobileOnly = b.optBoolean("mobileOnly"),
                )
            },
            excluded = o.optJSONArray("excluded").strings().toSet(),
            testUrl = o.optString("testUrl").ifEmpty { defaults.testUrl },
            checkIntervalSec = o.optInt("checkIntervalSec", defaults.checkIntervalSec),
            logLevel = o.optString("logLevel").ifEmpty { defaults.logLevel },
            bypassRu = o.optBoolean("bypassRu", false),
            metric = runCatching { Metric.valueOf(o.optString("metric")) }.getOrDefault(Metric.LATENCY),
            speedTestMb = o.optInt("speedTestMb", 5),
            sweep = o.optBoolean("sweep", false),
            sweepHours = o.optInt("sweepHours", 6),
            sweepMobile = o.optBoolean("sweepMobile", false),
            sweepLastAt = o.optLong("sweepLastAt", 0),
            sweepLastCount = o.optInt("sweepLastCount", 0),
            autoCheck = o.optBoolean("autoCheck", false),
            qSpeedMax = o.optInt("qSpeedMax", defaults.qSpeedMax),
            qPingFull = o.optInt("qPingFull", defaults.qPingFull),
            qPingZero = o.optInt("qPingZero", defaults.qPingZero),
            slowMbps = o.optInt("slowMbps", 5),
            geoUpdatedAt = o.optLong("geoUpdatedAt", 0),
            splitMode = runCatching { SplitMode.valueOf(o.optString("splitMode")) }.getOrDefault(defaults.splitMode),
            splitApps = o.optJSONArray("splitApps").strings().toSet(),
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
