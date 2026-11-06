package com.mkred.xrayclient

import android.app.Application
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.log.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppStore.init(this)
        XrayCore.init(this)
    }
}
