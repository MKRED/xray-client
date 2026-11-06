package com.mkred.xrayclient.data

import android.content.Context
import android.util.Log
import com.mkred.xrayclient.core.LinkParser
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

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    /** Результаты проверки: id профиля → задержка в мс, -1 — недоступен. */
    private val _delays = MutableStateFlow<Map<String, Long>>(emptyMap())
    val delays: StateFlow<Map<String, Long>> = _delays.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "data.json")
        val loaded = runCatching { if (file.exists()) fromJson(JSONObject(file.readText())) else AppData() }
            .onFailure { Log.e(TAG, "load failed", it) }
            .getOrDefault(AppData())
        set(loaded, save = false)
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
        if (save) runCatching { file.writeText(toJson(value).toString()) }
            .onFailure { Log.e(TAG, "save failed", it) }
    }

    fun setDelay(id: String, delay: Long) {
        _delays.value = _delays.value + (id to delay)
    }

    fun profile(id: String?): Profile? = id?.let { i -> _profiles.value.firstOrNull { it.id == i } }

    /** Серверы в порядке приоритета: сначала из списка пользователя, затем остальные. */
    fun orderedProfiles(): List<Profile> {
        val all = _profiles.value
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
        update { it.copy(priority = order) }
    }

    // ---- JSON ----

    private fun toJson(d: AppData) = JSONObject().apply {
        put("subscriptions", JSONArray().apply {
            d.subscriptions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("name", s.name); put("url", s.url)
                    put("updatedAt", s.updatedAt)
                    s.lastError?.let { put("lastError", it) }
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
        put("testUrl", d.testUrl)
        put("checkIntervalSec", d.checkIntervalSec)
    }

    private fun fromJson(o: JSONObject): AppData {
        val subs = o.optJSONArray("subscriptions").objects().map { s ->
            Subscription(
                id = s.getString("id"),
                name = s.optString("name"),
                url = s.getString("url"),
                updatedAt = s.optLong("updatedAt"),
                lastError = s.optString("lastError").ifEmpty { null },
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
            priority = o.optJSONArray("priority")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            testUrl = o.optString("testUrl").ifEmpty { defaults.testUrl },
            checkIntervalSec = o.optInt("checkIntervalSec", defaults.checkIntervalSec),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
