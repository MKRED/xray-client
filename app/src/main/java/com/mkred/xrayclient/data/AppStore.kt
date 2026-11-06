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
            LinkParser.parse(stored.link)?.copy(subscriptionId = stored.subscriptionId)
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
        val words = d.subscriptions.firstOrNull { it.id == p.subscriptionId }?.excludeKeywords.orEmpty()
        return words.any { it.isNotBlank() && p.name.contains(it.trim(), ignoreCase = true) }
    }

    /** Выключен ли сервер именно словом-фильтром (тогда переключатель не поможет). */
    fun excludedByKeyword(p: Profile, d: AppData = _data.value): String? =
        d.subscriptions.firstOrNull { it.id == p.subscriptionId }?.excludeKeywords.orEmpty()
            .firstOrNull { it.isNotBlank() && p.name.contains(it.trim(), ignoreCase = true) }

    fun setExcluded(ids: Collection<String>, excluded: Boolean) = update { d ->
        d.copy(excluded = if (excluded) d.excluded + ids else d.excluded - ids.toSet())
    }

    fun setExcludeKeywords(subscriptionId: String, words: List<String>) = update { d ->
        d.copy(subscriptions = d.subscriptions.map {
            if (it.id == subscriptionId) it.copy(excludeKeywords = words.map(String::trim).filter(String::isNotEmpty)) else it
        })
    }

    /** Рабочие серверы в порядке приоритета: сначала из списка пользователя, затем остальные. */
    fun orderedProfiles(): List<Profile> {
        val all = _usable.value
        val byId = all.associateBy { it.id }
        val ordered = _data.value.priority.mapNotNull { byId[it] }
        return ordered + all.filter { it.id !in _data.value.priority }
    }

    /** Сдвинуть сервер в списке приоритета на [delta] позиций (−1 — выше). */
    fun move(id: String, delta: Int) {
        val order = orderedProfiles().map { it.id }.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        // Исключённые сохраняют своё место в списке на случай, если их снова включат
        update { d -> d.copy(priority = order + d.priority.filter { it !in order }) }
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
                put(JSONObject().apply { put("link", p.link); p.subscriptionId?.let { put("sub", it) } })
            }
        })
        put("mode", d.mode.name)
        d.selectedId?.let { put("selectedId", it) }
        put("priority", JSONArray(d.priority))
        put("excluded", JSONArray(d.excluded.toList()))
        put("testUrl", d.testUrl)
        put("checkIntervalSec", d.checkIntervalSec)
        put("logLevel", d.logLevel)
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
                info = s.optJSONObject("info")?.let { i ->
                    SubscriptionInfo(i.optLong("upload"), i.optLong("download"), i.optLong("total"), i.optLong("expire"))
                },
            )
        }
        val profiles = o.optJSONArray("profiles").objects().map {
            StoredProfile(it.getString("link"), it.optString("sub").ifEmpty { null })
        }
        val defaults = AppData()
        return AppData(
            subscriptions = subs,
            profiles = profiles,
            mode = runCatching { SelectionMode.valueOf(o.optString("mode")) }.getOrDefault(defaults.mode),
            selectedId = o.optString("selectedId").ifEmpty { null },
            priority = o.optJSONArray("priority").strings(),
            excluded = o.optJSONArray("excluded").strings().toSet(),
            testUrl = o.optString("testUrl").ifEmpty { defaults.testUrl },
            checkIntervalSec = o.optInt("checkIntervalSec", defaults.checkIntervalSec),
            logLevel = o.optString("logLevel").ifEmpty { defaults.logLevel },
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
