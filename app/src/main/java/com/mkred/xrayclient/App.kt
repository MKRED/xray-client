package com.mkred.xrayclient

import android.app.Application
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
        XrayCore.init(this)
    }
}
