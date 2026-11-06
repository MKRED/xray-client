package com.mkred.xrayclient

import android.app.Application
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.service.QsTileService
import com.mkred.xrayclient.service.VpnStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppStore.init(this)
        XrayCore.init(this)

        // Плитка в шторке обновляется при любом изменении состояния VPN
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VpnStateHolder.state
                .distinctUntilChangedBy { it.status to it.profileName }
                .collect { QsTileService.refresh(this@App) }
        }
    }
}
