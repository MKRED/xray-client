package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Плановая проверка скорости всех серверов по таймеру (раз в 3–12 ч).
 *
 * Нужна, чтобы оценка качества всех серверов оставалась полной: замер скорости старше суток
 * не учитывается. Работает, пока включён VPN и выбран режим «Качество». Стартует, только когда
 * трафика почти нет; если пользователь начал что-то качать — обход останавливается и
 * продолжится позже (уже измеренные серверы повторно не меряются).
 */
object SpeedSweep {
    private const val TAG = "SpeedSweep"
    private const val BUSY_BYTES_PER_SEC = 100_000L
    private const val IDLE_SECONDS = 20

    private var job: Job? = null
    private var running = false
    private var stoppedByUser = false
    private val window = ArrayDeque<Long>()

    /** Скачано за последнюю секунду через туннель (вызывает сервис). */
    fun onSecond(down: Long) = synchronized(window) {
        window.addLast(down)
        while (window.size > IDLE_SECONDS) window.removeFirst()
    }

    private fun busy(): Boolean = synchronized(window) {
        window.size < IDLE_SECONDS || window.any { it > BUSY_BYTES_PER_SEC }
    }

    /** Серверы, которые проверяет обход: не исключённые, с разрешённым тестом. */
    fun eligible(): List<Profile> = AppStore.usable.value.filter { AppStore.speedTestAllowed(it) }

    /** Серверы, замер которых старше половины интервала — их и меряем. */
    private fun stale(hours: Int): List<Profile> {
        val fresh = System.currentTimeMillis() - hours * 3_600_000L / 2
        val speeds = Stats.speeds.value
        return eligible().filter { p -> (speeds[p.id]?.lastOrNull()?.ts ?: 0L) < fresh }
    }

    fun nextAt(): Long {
        val d = AppStore.data.value
        return if (d.sweepLastAt == 0L) 0L else d.sweepLastAt + d.sweepHours * 3_600_000L
    }

    fun start(scope: CoroutineScope) {
        stop()
        synchronized(window) { window.clear() }
        job = scope.launch {
            delay(60_000)
            while (isActive) {
                runCatching { tick() }.onFailure { AppLog.w(TAG, "Сбой планировщика: ${it.message}") }
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (running) SpeedTest.stop()
    }

    private fun tick() {
        val d = AppStore.data.value
        if (running) {
            // Пользователь начал качать — уступаем
            if (busy() && !stoppedByUser) {
                AppLog.i(TAG, "Идёт загрузка — обход приостановлен, продолжу позже")
                SpeedTest.stop()
            }
            return
        }
        if (!d.sweep || d.metric != Metric.QUALITY) return
        if (System.currentTimeMillis() < nextAt()) return
        if (!d.sweepMobile && NetworkState.isMobile()) return
        if (SpeedTest.run.value != null || busy()) return
        val list = stale(d.sweepHours)
        if (list.isEmpty()) {
            finish(0)
            return
        }
        AppLog.i(TAG, "Плановый обход: ${list.size} серверов")
        launch(list, "Плановая проверка")
    }

    /** «Проверить сейчас»: все подходящие серверы, независимо от таймера и сети. */
    fun runNow(): Boolean {
        if (running || SpeedTest.run.value != null) return false
        val list = eligible()
        if (list.isEmpty()) return false
        AppLog.i(TAG, "Обход вручную: ${list.size} серверов")
        stoppedByUser = true // ручной запуск не прерываем из-за трафика
        launch(list, "Проверка всех серверов")
        return true
    }

    private fun launch(list: List<Profile>, title: String) {
        running = true
        SpeedTest.start(title, list, skipUnreachable = true) { finished, measured ->
            running = false
            stoppedByUser = false
            if (finished) finish(measured)
            else AppLog.i(TAG, "Обход прерван после $measured серверов")
        }
    }

    private fun finish(measured: Int) {
        AppLog.i(TAG, "Плановый обход завершён: измерено $measured")
        AppStore.update { it.copy(sweepLastAt = System.currentTimeMillis(), sweepLastCount = measured) }
    }
}
