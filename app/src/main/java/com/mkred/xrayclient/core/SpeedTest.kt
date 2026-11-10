package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/** Состояние прогона теста скорости (для интерфейса). */
data class SpeedRun(
    val title: String,
    val ids: List<String>,
    val index: Int,
    val currentId: String?,
    val currentBytes: Long,
    val liveMbps: Float,
    val totalBytes: Long,
    val plannedBytes: Long,
)

/**
 * Тест скорости: для сервера поднимается временное ядро Xray с локальным
 * HTTP-входом, через него скачивается файл заданного размера.
 * Работающий VPN не прерывается. Серверы проверяются по одному.
 */
object SpeedTest {
    private const val TAG = "SpeedTest"
    private const val TIMEOUT_MS = 15_000L
    private fun url(bytes: Long) = "https://speed.cloudflare.com/__down?bytes=$bytes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _run = MutableStateFlow<SpeedRun?>(null)
    val run: StateFlow<SpeedRun?> = _run.asStateFlow()

    /** Запустить прогон по списку серверов (остальные прогоны отменяются). */
    fun start(title: String, list: List<Profile>) {
        val allowed = list.filter { AppStore.speedTestAllowed(it) }
        if (allowed.isEmpty()) return
        job?.cancel()
        val sizeMb = AppStore.data.value.speedTestMb
        val size = sizeMb * 1_000_000L
        _run.value = SpeedRun(title, allowed.map { it.id }, 0, null, 0, 0f, 0, size * allowed.size)
        job = scope.launch {
            AppLog.i(TAG, "Прогон «$title»: ${allowed.size} серверов по $sizeMb МБ")
            try {
                allowed.forEachIndexed { i, p ->
                    ensureActive()
                    _run.value = _run.value?.copy(index = i, currentId = p.id, currentBytes = 0, liveMbps = 0f)
                    // Свежий пинг — для качества
                    AppStore.setDelay(p.id, XrayCore.measure(p, AppStore.data.value.testUrl))
                    val mbps = measure(p, size) { bytes, live ->
                        _run.value = _run.value?.copy(currentBytes = bytes, liveMbps = live)
                    }
                    Stats.recordSpeed(p.id, mbps, sizeMb)
                    _run.value = _run.value?.let { it.copy(totalBytes = it.totalBytes + it.currentBytes) }
                }
            } finally {
                _run.value = null
                AppLog.i(TAG, "Прогон «$title» завершён")
            }
        }
    }

    fun stop() {
        job?.cancel()
        _run.value = null
    }

    /**
     * Скачать [size] байт через сервер. Возвращает Мбит/с (0 — не удалось).
     * Время считается от первого полученного байта, чтобы не учитывать рукопожатие.
     */
    suspend fun measure(p: Profile, size: Long, onProgress: (Long, Float) -> Unit = { _, _ -> }): Float =
        withContext(Dispatchers.IO) {
            val port = Net.freePort()
            val controller = XrayCore.newController { }
            try {
                XrayCore.startIsolated(controller, XrayConfig.forSpeedTest(p, port))
                waitPort(port)
                download(port, size, onProgress).also {
                    AppLog.i(TAG, "«${p.name}»: ${"%.1f".format(it)} Мбит/с")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w(TAG, "«${p.name}»: ${e.message}")
                0f
            } finally {
                runCatching { controller.stopLoop() }
            }
        }

    private suspend fun waitPort(port: Int) {
        repeat(30) {
            val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
            if (ok) return
            delay(100)
        }
        error("ядро не открыло порт")
    }

    internal suspend fun download(
        port: Int,
        size: Long,
        onProgress: (Long, Float) -> Unit,
        timeoutMs: Long = TIMEOUT_MS,
    ): Float {
        val conn = URL(url(size)).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            val buf = ByteArray(64 * 1024)
            var bytes = 0L
            var start = 0L
            var lastReport = 0L
            val deadline = System.currentTimeMillis() + timeoutMs
            conn.inputStream.use { input ->
                while (currentCoroutineContext().isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val now = System.currentTimeMillis()
                    if (start == 0L) start = now
                    bytes += n
                    if (now - lastReport > 250) {
                        lastReport = now
                        onProgress(bytes, mbps(bytes, now - start))
                    }
                    if (now > deadline) break // считаем по тому, что успело скачаться
                }
            }
            currentCoroutineContext().ensureActive()
            val elapsed = System.currentTimeMillis() - start
            onProgress(bytes, mbps(bytes, elapsed))
            return if (bytes < minOf(64 * 1024L, size / 2)) 0f else mbps(bytes, elapsed)
        } finally {
            conn.disconnect()
        }
    }

    private fun mbps(bytes: Long, ms: Long): Float = if (ms <= 0) 0f else bytes * 8f / 1_000_000f / (ms / 1000f)
}
