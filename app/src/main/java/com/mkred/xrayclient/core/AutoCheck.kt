package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.data.SwitchEvent
import com.mkred.xrayclient.data.SwitchKind
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Что показать под кнопкой на главной. */
data class AutoStatus(val kind: Kind, val text: String, val at: Long = System.currentTimeMillis()) {
    enum class Kind { OK, SEARCHING, NETWORK }
}

/**
 * Автопроверка скорости текущего сервера.
 *
 *  1. Пассивно: раз в секунду смотрим трафик через прокси. Идёт загрузка и скорость
 *     выше порога — сервер в порядке, тесты не нужны. Запросы уходят, а ответы
 *     не приходят 8 с — «заморозка».
 *  2. Трафика нет — лёгкий пакет 150 КБ через работающее ядро: раз в 30 с,
 *     при выключенном экране раз в 5 мин. Маленький пакет — только сигнал,
 *     в качество он не идёт; подозрение подтверждаем полным замером.
 *  3. Медленно / «заморозка» — сразу ищем замену, без перерывов:
 *     • есть сервер, замеренный за последние 10 мин и быстрее порога, — переходим на него без тестов;
 *     • иначе меряем ещё не проверенных (до 6) и переходим на первого, кто быстрее порога;
 *     • никто не дотянул — переходим на лучшего, если он хотя бы в 1,5 раза быстрее текущего.
 *     Свежие замеры не повторяем: следующий поиск проверяет других, а когда проверены все —
 *     ничего не качает, пока замеры не устареют.
 *  Работает только в режиме «Качество»: каждый замер сразу меняет оценку сервера,
 *  поэтому выбор по качеству сам не вернёт нас на медленный или «замёрзший» сервер.
 *  В режиме «Задержка» — только пинг, без тестов и трафика.
 */
object AutoCheck {
    private const val TAG = "AutoCheck"
    private const val PROBE_BYTES = 150_000L
    private const val PROBE_INTERVAL_ON = 30_000L
    private const val PROBE_INTERVAL_OFF = 300_000L
    /** Замер скорости считается свежим 10 минут — повторно такой сервер не меряем. */
    private const val FRESH_MS = 10 * 60_000L
    private const val FREEZE_SECONDS = 8
    private const val BUSY_BYTES_PER_SEC = 125_000L // ~1 Мбит/с — «идёт загрузка»
    private const val MAX_CANDIDATES = 6

    private val _status = MutableStateFlow<AutoStatus?>(null)
    val status: StateFlow<AutoStatus?> = _status.asStateFlow()


    @Volatile var screenOn = true
    @Volatile private var probeNow = false

    /** Последние секунды трафика: (приём, отдача). */
    private val window = ArrayDeque<Pair<Long, Long>>()
    private var job: Job? = null
    private var suspectFreeze = false
    private var nextProbeAt = 0L

    interface Host {
        val current: Profile?
        /** Переключиться (под блокировкой сервиса). */
        suspend fun switchTo(p: Profile, kind: SwitchKind, reason: String)
    }

    fun requestProbe() { probeNow = true }

    /** Каждую секунду из сервиса: байты через прокси за секунду. */
    fun onSecond(down: Long, up: Long) = synchronized(window) {
        window.addLast(down to up)
        while (window.size > 10) window.removeFirst()
    }

    fun start(scope: CoroutineScope, host: Host) {
        stop()
        synchronized(window) { window.clear() }
        nextProbeAt = System.currentTimeMillis() + 15_000
        job = scope.launch {
            while (isActive) {
                delay(1000)
                val d = AppStore.data.value
                val enabled = d.autoCheck && d.metric == Metric.QUALITY
                if (!enabled || d.mode == SelectionMode.MANUAL || SpeedTest.run.value != null) {
                    if (!enabled) _status.value = null
                    continue
                }
                val cur = host.current ?: continue
                runCatching { tick(host, cur) }.onFailure { AppLog.w(TAG, "Ошибка автопроверки", it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = null
    }

    private suspend fun tick(host: Host, cur: Profile) {
        val now = System.currentTimeMillis()
        val slow = AppStore.data.value.slowMbps.toFloat()
        val last = synchronized(window) { window.toList() }

        // ---- «заморозка»: отдаём, а в ответ тишина ----
        if (last.size >= FREEZE_SECONDS) {
            val tail = last.takeLast(FREEZE_SECONDS)
            val up = tail.sumOf { it.second }
            val down = tail.sumOf { it.first }
            // Большая отдача (загрузка фото и т.п.) — не заморозка
            if (up in 4_001..200_000 && down < 1_000) {
                AppLog.w(TAG, "«${cur.name}»: похоже на заморозку (отдано $up Б, принято $down Б за $FREEZE_SECONDS с) — проверяем")
                synchronized(window) { window.clear() }
                suspectFreeze = true
                probeNow = true
            }
        }

        // ---- идёт загрузка: скорость видна без тестов ----
        val busy = last.takeLast(5)
        if (busy.size == 5 && busy.all { it.first >= BUSY_BYTES_PER_SEC }) {
            val mbps = busy.sumOf { it.first } * 8f / 1_000_000f / 5f
            if (mbps >= slow) {
                _status.value = AutoStatus(AutoStatus.Kind.OK, "Скорость в норме · ${fmt(mbps)}")
                nextProbeAt = now + interval()
                return
            }
        }

        // ---- простой: лёгкая проверка по расписанию ----
        if (!probeNow && now < nextProbeAt) return
        probeNow = false
        nextProbeAt = now + interval()
        val port = Net.proxyPort ?: return
        val probeRes = runCatching { SpeedTest.download(port, PROBE_BYTES, { _, _ -> }, timeoutMs = 8_000) }
        if (Net.isProxyAuthError(probeRes.exceptionOrNull())) {
            // Не вина сервера: локальный прокси не принял пароль. Не портим оценку и не переключаемся.
            AppLog.e(TAG, "Локальный прокси отклонил авторизацию — проверка пропущена", probeRes.exceptionOrNull())
            return
        }
        val probe = probeRes.getOrDefault(0f)
        AppLog.d(TAG, "Лёгкая проверка «${cur.name}»: ${fmt(probe)}")

        val wasFreeze = suspectFreeze
        suspectFreeze = false
        if (wasFreeze && probe > 0f) AppLog.i(TAG, "Заморозка не подтвердилась: ${fmt(probe)}")
        when {
            probe <= 0f -> {
                val why = if (wasFreeze) "заморозка: данные не идут" else "не отвечает на проверку"
                // Неудачный замер — оценка качества сервера падает до «≈», выбор на него не вернётся
                Stats.recordSpeed(cur.id, 0f, AppStore.data.value.speedTestMb)
                // Заморозка важнее перерыва, но не чаще раза в 2 мин — иначе при плохой сети будем жечь трафик
                search(host, cur, 0f, SwitchKind.FREEZE, why)
            }
            probe < slow && AppStore.speedTestAllowed(cur) -> {
                // Маленький пакет занижает скорость — подтверждаем полным замером через рабочее ядро
                // (если текущий недавно уже меряли и он был медленным — берём тот замер)
                val full = freshMbps(cur.id)?.takeIf { it < slow } ?: runCatching {
                    SpeedTest.download(port, AppStore.data.value.speedTestMb * 1_000_000L, { _, _ -> })
                }.getOrDefault(0f).also { Stats.recordSpeed(cur.id, it, AppStore.data.value.speedTestMb) }
                if (full < slow) search(host, cur, full, SwitchKind.SLOW, "медленно: ${fmt(full)}")
                else _status.value = AutoStatus(AutoStatus.Kind.OK, "Скорость в норме · ${fmt(full)}")
            }
            else -> _status.value = AutoStatus(AutoStatus.Kind.OK, "Сервер отвечает · проверено")
        }
    }

    private fun interval() = if (screenOn) PROBE_INTERVAL_ON else PROBE_INTERVAL_OFF

    // ---- поиск сервера быстрее ----

    /** Скорость по замеру не старше 10 минут или null. */
    private fun freshMbps(id: String): Float? =
        Stats.speeds.value[id]?.lastOrNull()?.takeIf { System.currentTimeMillis() - it.ts < FRESH_MS }?.mbps

    private suspend fun search(host: Host, cur: Profile, curMbps: Float, kind: SwitchKind, why: String) {
        val slow = AppStore.data.value.slowMbps.toFloat()
        val cands = candidates(cur, dead = curMbps < 1f)
        val title = why.replaceFirstChar { it.uppercase() }

        suspend fun go(p: Profile, mbps: Float, note: String) {
            _status.value = AutoStatus(AutoStatus.Kind.OK, "Переключились · ${fmt(mbps)}")
            host.switchTo(p, kind, "$why → ${fmt(mbps)}$note")
            nextProbeAt = System.currentTimeMillis() + interval()
        }

        // 1. Уже знаем быстрый сервер по свежему замеру — переходим сразу, без тестов
        val known = cands.mapNotNull { p -> freshMbps(p.id)?.let { p to it } }.maxByOrNull { it.second }
        if (known != null && known.second >= slow) {
            AppLog.i(TAG, "«${cur.name}»: $why — переходим на «${known.first.name}» по свежему замеру")
            go(known.first, known.second, " (замер за 10 мин)")
            return
        }

        // 2. Меряем тех, кого давно не проверяли; первый быстрее порога — наш
        val todo = cands.filter { freshMbps(it.id) == null }.take(MAX_CANDIDATES)
        AppLog.i(TAG, "«${cur.name}»: $why — проверяем кандидатов: ${todo.size}")
        var best = known
        val size = AppStore.data.value.speedTestMb
        for ((i, p) in todo.withIndex()) {
            _status.value = AutoStatus(AutoStatus.Kind.SEARCHING, "$title — ищем сервер быстрее · ${i + 1} из ${todo.size}")
            val mbps = SpeedTest.measure(p, size * 1_000_000L)
            Stats.recordSpeed(p.id, mbps, size)
            if (best == null || mbps > best.second) best = p to mbps
            if (mbps >= slow) {
                go(p, mbps, "")
                return
            }
        }

        // 3. До порога не дотянул никто — берём лучшего, если он заметно быстрее текущего
        if (best != null && best.second >= curMbps * 1.5f && best.second >= curMbps + 1f) {
            go(best.first, best.second, " (лучший из проверенных)")
            return
        }
        val text = if (todo.isEmpty()) "Медленно (${fmt(curMbps)}) · быстрее нет среди проверенных за 10 мин"
        else "Проверено ${todo.size} — все не быстрее. Похоже, проблема в сети"
        _status.value = AutoStatus(AutoStatus.Kind.NETWORK, text)
        // Событие пишем, только когда реально что-то проверяли — иначе засорим журнал каждые 30 с
        if (todo.isNotEmpty()) Stats.recordSwitch(
            SwitchEvent(System.currentTimeMillis(), null, "Проверено ${todo.size} · остаёмся на «${cur.name}»", SwitchKind.NETWORK, "похоже, проблема в сети"),
        )
    }

    /**
     * Кандидаты: лучшие по текущей метрике среди рабочих, с разрешённым тестом.
     * В режиме «Приоритет» — сначала текущий блок; блоки ниже — только если текущий почти не работает.
     */
    private fun candidates(cur: Profile, dead: Boolean): List<Profile> {
        val data = AppStore.data.value
        val usable = AppStore.usable.value
        val delays = AppStore.delays.value
        fun ok(p: Profile) = p.id != cur.id && (delays[p.id] ?: -1) > 0 && AppStore.speedTestAllowed(p)
        fun rank(list: List<Profile>) = list.sortedByDescending { Quality.of(it, delays).score }

        val pool: List<Profile> = when (data.mode) {
            SelectionMode.PRIORITY -> {
                val mobile = NetworkState.isMobile()
                val blocks = Blocks.withAny(data).filter { Blocks.skipReason(it, mobile) == null }
                val idx = blocks.indexOfFirst { b -> Blocks.candidates(b, data, usable).any { it.id == cur.id } }
                val mineBlock = blocks.getOrNull(idx)?.let { b ->
                    rank(Blocks.candidates(b, data, usable).filter { ok(it) && Blocks.passes(b, delays[it.id]) })
                }.orEmpty()
                val below = if (dead && idx >= 0) blocks.drop(idx + 1).flatMap { b ->
                    rank(Blocks.candidates(b, data, usable).filter { ok(it) && Blocks.passes(b, delays[it.id]) })
                } else emptyList()
                (mineBlock + below).distinctBy { it.id }
            }
            else -> rank(usable.filter { ok(it) })
        }
        return pool
    }

    private fun fmt(mbps: Float) =
        if (mbps >= 10) "${mbps.toInt()} Мбит/с" else String.format(Locale.US, "%.1f Мбит/с", mbps).replace('.', ',')
}
