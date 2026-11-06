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

/** Сводка по серверу за один час: сколько проверок прошло и сколько упало. */
data class PingBucket(val id: String, val hour: Long, val ok: Int, val fail: Int, val sumDelay: Long)

enum class SwitchKind { CONNECT, FAIL, FASTER, PRIORITY, SETTINGS }

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

    private val _buckets = MutableStateFlow<List<PingBucket>>(emptyList())
    val buckets: StateFlow<List<PingBucket>> = _buckets.asStateFlow()

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

    /** Вызывается раз в секунду: байты через прокси за прошедшую секунду. */
    fun addTraffic(down: Long, up: Long, ts: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        _speed.value = (_speed.value + SpeedSample(ts, down, up)).takeLast(SPEED_POINTS)
        if (down == 0L && up == 0L) return
        val t = Traffic(down, up)
        _session.value = _session.value + t
        val d = day(ts)
        _daily.value = (_daily.value + (d to ((_daily.value[d] ?: Traffic()) + t)))
            .entries.sortedBy { it.key }.takeLast(TRAFFIC_DAYS).associate { it.key to it.value }
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
