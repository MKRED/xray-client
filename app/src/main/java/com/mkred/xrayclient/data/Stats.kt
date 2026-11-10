package com.mkred.xrayclient.data

import android.content.Context
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Скорость за одну секунду, байт/с. */
data class SpeedSample(val ts: Long, val down: Long, val up: Long)

data class PingPoint(val ts: Long, val delay: Long)

/** Замер скорости: Мбит/с (≤0 — не удалось), размер пакета в МБ. */
data class SpeedPoint(val ts: Long, val mbps: Float, val sizeMb: Int)

/** Сводка по серверу за один час: сколько проверок прошло и сколько упало. */
data class PingBucket(val id: String, val hour: Long, val ok: Int, val fail: Int, val sumDelay: Long)

/** Сколько секунд и трафика пришлось на сервер за один час подключения. */
data class UsageBucket(val id: String, val hour: Long, val seconds: Int, val down: Long, val up: Long)

/** Итог по серверу за период — для «Топ серверов». */
data class ServerUsage(val id: String, val name: String, val seconds: Long, val traffic: Traffic)

enum class SwitchKind {
    CONNECT, FAIL, FASTER, PRIORITY, SETTINGS,

    /** Автопроверка: сервер медленный / «замёрз». */
    SLOW, FREEZE,

    /** Автопроверка не нашла лучше — запись без переключения. */
    NETWORK,
}

data class SwitchEvent(val ts: Long, val from: String?, val to: String, val kind: SwitchKind, val reason: String)

data class Traffic(val down: Long = 0, val up: Long = 0) {
    operator fun plus(o: Traffic) = Traffic(down + o.down, up + o.up)
    val total get() = down + up
}

/**
 * Статистика: скорость, трафик по дням, история пинга, переключения.
 * Скорость живёт только в памяти, остальное сохраняется в stats.json.
 */
object Stats {
    private const val SPEED_POINTS = 60
    private const val PING_POINTS = 120
    private const val BUCKET_DAYS = 7
    private const val TRAFFIC_DAYS = 62
    private const val SWITCH_EVENTS = 100

    private lateinit var file: File
    private val lock = Any()

    private val _speed = MutableStateFlow<List<SpeedSample>>(emptyList())
    val speed: StateFlow<List<SpeedSample>> = _speed.asStateFlow()

    private val _session = MutableStateFlow(Traffic())
    val session: StateFlow<Traffic> = _session.asStateFlow()

    /** День (yyyy-MM-dd) → трафик через VPN. */
    private val _daily = MutableStateFlow<Map<String, Traffic>>(emptyMap())
    val daily: StateFlow<Map<String, Traffic>> = _daily.asStateFlow()

    /** Последние проверки каждого сервера (для графика пинга). */
    private val _pings = MutableStateFlow<Map<String, List<PingPoint>>>(emptyMap())
    val pings: StateFlow<Map<String, List<PingPoint>>> = _pings.asStateFlow()

    /** Последние замеры скорости каждого сервера. */
    private val _speeds = MutableStateFlow<Map<String, List<SpeedPoint>>>(emptyMap())
    val speeds: StateFlow<Map<String, List<SpeedPoint>>> = _speeds.asStateFlow()

    fun recordSpeed(id: String, mbps: Float, sizeMb: Int, ts: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = (_speeds.value[id].orEmpty() + SpeedPoint(ts, mbps, sizeMb)).takeLast(30)
            _speeds.value = _speeds.value + (id to list)
        }
        save()
    }

    private val _buckets = MutableStateFlow<List<PingBucket>>(emptyList())
    val buckets: StateFlow<List<PingBucket>> = _buckets.asStateFlow()

    private val _usage = MutableStateFlow<List<UsageBucket>>(emptyList())
    val usage: StateFlow<List<UsageBucket>> = _usage.asStateFlow()

    /** Имена серверов на момент подключения — чтобы топ не терял удалённые серверы. */
    private val names = HashMap<String, String>()

    private val _switches = MutableStateFlow<List<SwitchEvent>>(emptyList())
    val switches: StateFlow<List<SwitchEvent>> = _switches.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "stats.json")
        runCatching { if (file.exists()) load(JSONObject(file.readText())) }
            .onFailure { AppLog.w("Stats", "Не удалось прочитать stats.json", it) }
    }

    fun day(ts: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    // ---- запись ----

    fun startSession() {
        _session.value = Traffic()
        _speed.value = emptyList()
    }

    /** Вызывается раз в секунду: байты через прокси за прошедшую секунду и текущий сервер. */
    fun addTraffic(
        down: Long, up: Long, serverId: String?, serverName: String?, ts: Long = System.currentTimeMillis(),
    ): Unit = synchronized(lock) {
        _speed.value = (_speed.value + SpeedSample(ts, down, up)).takeLast(SPEED_POINTS)
        if (serverId != null) addUsage(serverId, serverName, down, up, ts)
        if (down == 0L && up == 0L) return
        val t = Traffic(down, up)
        _session.value = _session.value + t
        val d = day(ts)
        _daily.value = (_daily.value + (d to ((_daily.value[d] ?: Traffic()) + t)))
            .entries.sortedBy { it.key }.takeLast(TRAFFIC_DAYS).associate { it.key to it.value }
    }

    private fun addUsage(id: String, name: String?, down: Long, up: Long, ts: Long) {
        name?.let { names[id] = it }
        val hour = ts / 3_600_000L * 3_600_000L
        val all = _usage.value.toMutableList()
        val i = all.indexOfFirst { it.id == id && it.hour == hour }
        val old = if (i >= 0) all[i] else UsageBucket(id, hour, 0, 0, 0)
        val upd = old.copy(seconds = old.seconds + 1, down = old.down + down, up = old.up + up)
        if (i >= 0) {
            all[i] = upd
            _usage.value = all
        } else {
            val cutoff = ts - BUCKET_DAYS * 86_400_000L
            _usage.value = (all + upd).filter { it.hour >= cutoff }
        }
    }

    /** Топ серверов по времени подключения с момента [since]. */
    fun topServers(since: Long, limit: Int = 5): List<ServerUsage> = synchronized(lock) {
        _usage.value.filter { it.hour >= since }
            .groupBy { it.id }
            .map { (id, list) ->
                ServerUsage(
                    id = id,
                    name = names[id] ?: "Удалённый сервер",
                    seconds = list.sumOf { it.seconds.toLong() },
                    traffic = Traffic(list.sumOf { it.down }, list.sumOf { it.up }),
                )
            }
            .sortedByDescending { it.seconds }
            .take(limit)
    }

    fun recordPing(id: String, delay: Long, ts: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        val list = (_pings.value[id].orEmpty() + PingPoint(ts, delay)).takeLast(PING_POINTS)
        _pings.value = _pings.value + (id to list)

        val hour = ts / 3_600_000L * 3_600_000L
        val all = _buckets.value.toMutableList()
        val i = all.indexOfFirst { it.id == id && it.hour == hour }
        val old = if (i >= 0) all[i] else PingBucket(id, hour, 0, 0, 0)
        val upd = if (delay > 0) old.copy(ok = old.ok + 1, sumDelay = old.sumDelay + delay) else old.copy(fail = old.fail + 1)
        if (i >= 0) all[i] = upd else all += upd
        val cutoff = ts - BUCKET_DAYS * 86_400_000L
        _buckets.value = all.filter { it.hour >= cutoff }
    }

    fun recordSwitch(e: SwitchEvent) {
        synchronized(lock) { _switches.value = (listOf(e) + _switches.value).take(SWITCH_EVENTS) }
        AppLog.i("Stats", "Переключение: ${e.from ?: "—"} → ${e.to} (${e.reason})")
        save()
    }

    fun clear() {
        synchronized(lock) {
            _daily.value = emptyMap(); _pings.value = emptyMap(); _buckets.value = emptyList()
            _switches.value = emptyList(); _session.value = Traffic(); _speed.value = emptyList()
            _usage.value = emptyList(); names.clear(); _speeds.value = emptyMap()
        }
        save()
    }

    // ---- сохранение ----

    fun save() {
        synchronized(lock) {
            runCatching { file.writeText(toJson().toString()) }
                .onFailure { AppLog.w("Stats", "Не удалось сохранить stats.json", it) }
        }
    }

    private fun toJson() = JSONObject().apply {
        put("daily", JSONObject().apply { _daily.value.forEach { (k, v) -> put(k, JSONArray(listOf(v.down, v.up))) } })
        put("pings", JSONObject().apply {
            _pings.value.forEach { (id, pts) ->
                put(id, JSONArray().apply { pts.forEach { put(JSONArray(listOf(it.ts, it.delay))) } })
            }
        })
        put("buckets", JSONArray().apply {
            _buckets.value.forEach { put(JSONArray(listOf(it.id, it.hour, it.ok, it.fail, it.sumDelay))) }
        })
        put("speeds", JSONObject().apply {
            _speeds.value.forEach { (id, pts) ->
                put(id, JSONArray().apply { pts.forEach { put(JSONArray(listOf(it.ts, it.mbps.toDouble(), it.sizeMb))) } })
            }
        })
        put("usage", JSONArray().apply {
            _usage.value.forEach { put(JSONArray(listOf(it.id, it.hour, it.seconds, it.down, it.up))) }
        })
        put("names", JSONObject(names as Map<*, *>))
        put("switches", JSONArray().apply {
            _switches.value.forEach {
                put(JSONObject().put("ts", it.ts).put("from", it.from ?: "").put("to", it.to)
                    .put("kind", it.kind.name).put("reason", it.reason))
            }
        })
    }

    private fun load(o: JSONObject) {
        o.optJSONObject("daily")?.let { d ->
            _daily.value = d.keys().asSequence().associateWith { k ->
                val a = d.getJSONArray(k); Traffic(a.getLong(0), a.getLong(1))
            }
        }
        o.optJSONObject("pings")?.let { p ->
            _pings.value = p.keys().asSequence().associateWith { id ->
                val a = p.getJSONArray(id)
                (0 until a.length()).map { val x = a.getJSONArray(it); PingPoint(x.getLong(0), x.getLong(1)) }
            }
        }
        o.optJSONArray("buckets")?.let { a ->
            _buckets.value = (0 until a.length()).map {
                val x = a.getJSONArray(it)
                PingBucket(x.getString(0), x.getLong(1), x.getInt(2), x.getInt(3), x.getLong(4))
            }
        }
        o.optJSONObject("speeds")?.let { sp ->
            _speeds.value = sp.keys().asSequence().associateWith { id ->
                val a = sp.getJSONArray(id)
                (0 until a.length()).map { val x = a.getJSONArray(it); SpeedPoint(x.getLong(0), x.getDouble(1).toFloat(), x.getInt(2)) }
            }
        }
        o.optJSONArray("usage")?.let { a ->
            _usage.value = (0 until a.length()).map {
                val x = a.getJSONArray(it)
                UsageBucket(x.getString(0), x.getLong(1), x.getInt(2), x.getLong(3), x.getLong(4))
            }
        }
        o.optJSONObject("names")?.let { n -> n.keys().forEach { k -> names[k] = n.getString(k) } }
        o.optJSONArray("switches")?.let { a ->
            _switches.value = (0 until a.length()).map {
                val x = a.getJSONObject(it)
                SwitchEvent(
                    x.getLong("ts"), x.optString("from").ifEmpty { null }, x.getString("to"),
                    runCatching { SwitchKind.valueOf(x.getString("kind")) }.getOrDefault(SwitchKind.SETTINGS),
                    x.optString("reason"),
                )
            }
        }
    }
}
