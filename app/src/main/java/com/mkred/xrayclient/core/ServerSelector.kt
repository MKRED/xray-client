package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.data.SwitchKind
import com.mkred.xrayclient.log.AppLog

/** Решение селектора: на какой сервер перейти и почему. */
data class Choice(val profile: Profile, val kind: SwitchKind, val reason: String)

/**
 * Выбор сервера по режиму:
 *  - FASTEST  — проверяем все, берём с наименьшей задержкой;
 *  - PRIORITY — блоки сверху вниз: первый блок с рабочим сервером, внутри — самый быстрый;
 *  - MANUAL   — выбранный пользователем.
 */
object ServerSelector {
    private const val TAG = "Selector"

    /** Переключаться на «более быстрый», только если он заметно быстрее — без дёрганья. */
    private const val MIN_GAIN_MS = 50
    private const val MIN_GAIN_RATIO = 0.7

    /** Сколько проверок подряд блок выше должен быть рабочим, чтобы на него вернуться. */
    private const val UP_CHECKS = 2

    /** Блок выше текущего, который ждёт подтверждения, и сколько раз подряд он был рабочим. */
    private var pendingUpBlock: String? = null
    private var pendingUpCount = 0

    fun reset() {
        pendingUpBlock = null
        pendingUpCount = 0
    }

    /**
     * @param current текущий сервер (null — первое подключение)
     * @param immediate без ожидания подтверждения (смена настроек или сети)
     * @return новый выбор или null, если оставаться на текущем
     */
    suspend fun pick(current: Profile?, immediate: Boolean = false): Choice? {
        val data = AppStore.data.value
        val choice = when (data.mode) {
            SelectionMode.MANUAL -> manual(current)
            SelectionMode.FASTEST -> fastest(current)
            SelectionMode.PRIORITY -> priority(current, immediate)
        }
        if (choice != null && choice.profile.id != current?.id) {
            AppLog.i(TAG, "Режим ${data.mode}: «${current?.name}» → «${choice.profile.name}» (${choice.reason})")
            return choice
        }
        AppLog.d(TAG, "Режим ${data.mode}: остаёмся на «${current?.name}»")
        return null
    }

    // ---- вручную ----

    private fun manual(current: Profile?): Choice? {
        val p = AppStore.usableProfile(AppStore.data.value.selectedId) ?: AppStore.usable.value.firstOrNull() ?: return null
        return Choice(p, if (current == null) SwitchKind.CONNECT else SwitchKind.SETTINGS, "ручной выбор")
    }

    /** Качество выше — лучше; минимальный выигрыш, ради которого переключаемся. */
    private const val MIN_GAIN_QUALITY = 10

    private fun quality() = AppStore.data.value.metric == Metric.QUALITY

    /** Лучший сервер группы по текущей метрике (только отвечающие на пинг). */
    private fun bestOf(list: List<Profile>, delays: Map<String, Long>, accept: (Profile) -> Boolean = { true }): Profile? {
        val working = list.filter { (delays[it.id] ?: -1) > 0 && accept(it) }
        return if (quality()) working.maxByOrNull { Quality.of(it, delays).score }
        else working.minByOrNull { delays.getValue(it.id) }
    }

    // ---- самый быстрый / лучший ----

    private suspend fun fastest(current: Profile?): Choice? {
        val all = AppStore.usable.value
        val delays = XrayCore.measureAll(all, AppStore.data.value.testUrl)
        val best = bestOf(all, delays)
        if (current == null) {
            val p = best ?: all.firstOrNull() ?: return null
            return Choice(p, SwitchKind.CONNECT, if (quality()) "лучшее качество" else "самый быстрый")
        }
        return betterInGroup(current, best, delays)
    }

    /** Правило «заметно лучше» или «текущий не отвечает». */
    private fun betterInGroup(current: Profile, best: Profile?, delays: Map<String, Long>): Choice? {
        best ?: return null
        if (best.id == current.id) return null
        val cur = delays[current.id] ?: -1
        val bestDelay = delays[best.id] ?: return null
        if (cur <= 0) return Choice(best, SwitchKind.FAIL, "не отвечал")
        if (quality()) {
            val q = Quality.of(current, delays).score
            val qb = Quality.of(best, delays).score
            return if (qb >= q + MIN_GAIN_QUALITY) Choice(best, SwitchKind.FASTER, "качество $qb против $q") else null
        }
        val muchFaster = bestDelay < cur * MIN_GAIN_RATIO && cur - bestDelay > MIN_GAIN_MS
        return if (muchFaster) Choice(best, SwitchKind.FASTER, "быстрее на ${cur - bestDelay} мс") else null
    }

    // ---- приоритет по блокам ----

    private suspend fun priority(current: Profile?, immediate: Boolean): Choice? {
        val data = AppStore.data.value
        val usable = AppStore.usable.value
        val url = data.testUrl
        val mobile = NetworkState.isMobile()
        val blocks = Blocks.withAny(data)
        val delays = HashMap<String, Long>()

        suspend fun measured(list: List<Profile>): Map<String, Long> {
            val todo = list.filter { it.id !in delays }
            if (todo.isNotEmpty()) delays.putAll(XrayCore.measureAll(todo, url))
            return delays
        }

        // Первый блок, где есть рабочий сервер
        var targetIdx = -1
        var targetBest: Profile? = null
        for ((i, b) in blocks.withIndex()) {
            if (Blocks.skipReason(b, mobile) != null) continue
            val cands = Blocks.candidates(b, data, usable)
            if (cands.isEmpty()) continue
            measured(cands)
            val best = bestOf(cands, AppStore.delays.value + delays) { Blocks.passes(b, delays[it.id]) }
            if (best != null) {
                targetIdx = i
                targetBest = best
                break
            }
        }
        val target = targetBest ?: run {
            // Ни одного рабочего: при работающем VPN остаёмся, при подключении пробуем первый
            if (current != null) return null
            val p = usable.firstOrNull() ?: return null
            return Choice(p, SwitchKind.CONNECT, "нет рабочих серверов, пробуем первый")
        }
        val targetBlock = blocks[targetIdx]
        val blockName = if (Blocks.isAny(targetBlock)) Blocks.ANY_TITLE else "блок ${targetIdx + 1}"

        if (current == null) {
            reset()
            return Choice(target, SwitchKind.CONNECT, blockName)
        }

        // В каком блоке сейчас текущий сервер (первый подходящий по порядку)
        if (current.id !in delays) measured(listOf(current))
        val currentIdx = blocks.indexOfFirst { b ->
            Blocks.skipReason(b, mobile) == null &&
                Blocks.candidates(b, data, usable).any { it.id == current.id } &&
                Blocks.passes(b, delays[current.id])
        }

        return when {
            // Текущий никуда не подходит (упал, исключён, не проходит условия) — сразу уходим
            currentIdx < 0 -> {
                reset()
                val failed = (delays[current.id] ?: -1) <= 0
                Choice(target, if (failed) SwitchKind.FAIL else SwitchKind.SETTINGS,
                    if (failed) "не отвечал" else "не подходит под блоки")
            }
            // Ожил блок выше — возвращаемся после нескольких проверок подряд
            targetIdx < currentIdx -> {
                if (immediate) {
                    reset()
                    return Choice(target, SwitchKind.PRIORITY, "$blockName выше по приоритету")
                }
                if (pendingUpBlock == targetBlock.id) pendingUpCount++ else {
                    pendingUpBlock = targetBlock.id
                    pendingUpCount = 1
                }
                if (pendingUpCount >= UP_CHECKS) {
                    reset()
                    Choice(target, SwitchKind.PRIORITY, "$blockName снова доступен")
                } else {
                    AppLog.i(TAG, "$blockName доступен ($pendingUpCount/$UP_CHECKS), ждём подтверждения")
                    null
                }
            }
            // Тот же блок — правило «заметно быстрее»
            else -> {
                reset()
                betterInGroup(current, target, AppStore.delays.value + delays)
            }
        }
    }
}
