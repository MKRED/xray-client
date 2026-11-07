package com.mkred.xrayclient.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Тип сети, через которую телефон выходит в интернет.
 * Приложение исключено из VPN, поэтому для него «сеть по умолчанию» — это
 * настоящая сеть (Wi-Fi или мобильная), а не наш туннель.
 */
object NetworkState {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isMobile(): Boolean {
        if (!::appContext.isInitialized) return false
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
