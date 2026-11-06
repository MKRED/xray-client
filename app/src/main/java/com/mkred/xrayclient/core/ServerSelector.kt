package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.log.AppLog

/**
 * Выбор сервера по режиму:
 *  - FASTEST  — проверяем все, берём с наименьшей задержкой;
 *  - PRIORITY — идём по списку сверху вниз, берём первый рабочий;
 *  - MANUAL   — выбранный пользователем.
 */
object ServerSelector {

    /** Переключаться на «более быстрый», только если он заметно быстрее — без дёрганья. */
    private const val MIN_GAIN_MS = 50
    private const val MIN_GAIN_RATIO = 0.7

    suspend fun pick(current: Profile? = null): Profile? {
        val data = AppStore.data.value
        val result = choose(current)
        if (result?.id != current?.id) {
            AppLog.i("Selector", "Режим ${data.mode}: выбран «${result?.name}» (было «${current?.name}»)")
        } else {
            AppLog.d("Selector", "Режим ${data.mode}: остаёмся на «${current?.name}»")
        }
        return result
    }

    private suspend fun choose(current: Profile?): Profile? {
        val data = AppStore.data.value
        val url = data.testUrl
        return when (data.mode) {
            SelectionMode.MANUAL ->
                AppStore.usableProfile(data.selectedId) ?: AppStore.orderedProfiles().firstOrNull()

            SelectionMode.PRIORITY -> {
                val ordered = AppStore.orderedProfiles()
                ordered.firstOrNull { XrayCore.measure(it, url).also { d -> AppStore.setDelay(it.id, d) } > 0 }
                    ?: current ?: ordered.firstOrNull()
            }

            SelectionMode.FASTEST -> {
                val all = AppStore.usable.value
                val delays = XrayCore.measureAll(all, url)
                val best = all.filter { (delays[it.id] ?: -1) > 0 }.minByOrNull { delays.getValue(it.id) }
                if (best == null || current == null) return best ?: current ?: all.firstOrNull()

                val cur = delays[current.id] ?: -1
                val bestDelay = delays.getValue(best.id)
                val muchFaster = bestDelay < cur * MIN_GAIN_RATIO && cur - bestDelay > MIN_GAIN_MS
                if (cur <= 0 || muchFaster) best else current
            }
        }
    }
}
