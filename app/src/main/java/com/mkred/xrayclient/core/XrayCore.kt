package com.mkred.xrayclient.core

import android.content.Context
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Тонкая обёртка над libv2ray (Xray-core, собранный через gomobile). */
object XrayCore {
    private const val TAG = "XrayCore"
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        go.Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        initialized = true
        AppLog.i(TAG, "Ядро: ${version()}")
    }

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")

    fun newController(onStatus: (String) -> Unit): CoreController =
        Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, msg: String?): Long {
                AppLog.i("Xray", "статус $code: $msg")
                msg?.let(onStatus)
                return 0
            }
        })

    /** Задержка через сервер в мс, -1 — сервер не отвечает. */
    suspend fun measure(p: Profile, url: String): Long = withContext(Dispatchers.IO) {
        runCatching { Libv2ray.measureOutboundDelay(XrayConfig.forTest(p), url) }
            .getOrElse { AppLog.w(TAG, "Проверка «${p.name}»: ${it.message}"); -1L }
            .let { if (it <= 0) -1L else it }
            .also { AppLog.d(TAG, "Пинг «${p.name}» (${p.address}:${p.port}): ${if (it < 0) "недоступен" else "$it мс"}") }
    }

    /** Проверить список серверов параллельно (не больше 6 одновременно). */
    suspend fun measureAll(list: List<Profile>, url: String): Map<String, Long> = coroutineScope {
        AppLog.i(TAG, "Проверка ${list.size} серверов через $url")
        val gate = Semaphore(6)
        list.map { p ->
            async {
                val d = gate.withPermit { measure(p, url) }
                AppStore.setDelay(p.id, d)
                p.id to d
            }
        }.awaitAll().toMap().also { r ->
            AppLog.i(TAG, "Проверка завершена: доступно ${r.values.count { it > 0 }} из ${r.size}")
        }
    }
}
