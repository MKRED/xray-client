package com.mkred.xrayclient

import android.app.Application
import com.mkred.xrayclient.core.NetworkState
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.service.QsTileService
import com.mkred.xrayclient.service.VpnStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class App : Application() {
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLog.recordCrash("запуск: $name", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Каждый этап отдельно: если один сломался, приложение всё равно откроется и покажет ошибку
        step("настройки") { AppStore.init(this) }
        step("статистика") {
            runCatching { Stats.init(this) }.onFailure {
                // Повреждённая статистика не должна мешать запуску
                java.io.File(filesDir, "stats.json").renameTo(java.io.File(filesDir, "stats.broken.json"))
                throw it
            }
        }
        step("сеть") { NetworkState.init(this); com.mkred.xrayclient.core.Net.installProxyAuth() }
        step("ядро Xray") { XrayCore.init(this) }

        // Плитка в шторке обновляется при любом изменении состояния VPN
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VpnStateHolder.state
                .distinctUntilChangedBy { it.status to it.profileName }
                .collect { QsTileService.refresh(this@App) }
        }
    }
}
