package com.mkred.xrayclient.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.data.Subscriptions
import com.mkred.xrayclient.service.VpnStateHolder
import com.mkred.xrayclient.service.XrayVpnService
import com.mkred.xrayclient.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val data = AppStore.data
    val profiles = AppStore.profiles
    val delays = AppStore.delays
    val vpn = VpnStateHolder.state

    val testing = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val update = MutableStateFlow<Updater.Release?>(null)
    val updateProgress = MutableStateFlow<Float?>(null)

    init {
        checkUpdate(silent = true)
        // Подписки старше 6 часов обновляем при запуске
        val stale = data.value.subscriptions.any { System.currentTimeMillis() - it.updatedAt > 6 * 3600_000L }
        if (stale) refreshSubscriptions(silent = true)
    }

    fun setMode(mode: SelectionMode) {
        AppStore.update { it.copy(mode = mode) }
        XrayVpnService.reselect(app)
    }

    fun selectServer(id: String) {
        AppStore.update { it.copy(selectedId = id, mode = SelectionMode.MANUAL) }
        XrayVpnService.reselect(app)
    }

    fun move(id: String, delta: Int) = AppStore.move(id, delta)

    fun setInterval(sec: Int) = AppStore.update { it.copy(checkIntervalSec = sec) }

    fun setTestUrl(url: String) = AppStore.update { it.copy(testUrl = url.trim()) }

    fun testAll() = viewModelScope.launch {
        if (testing.value) return@launch
        testing.value = true
        try {
            XrayCore.measureAll(profiles.value, data.value.testUrl)
        } finally {
            testing.value = false
        }
    }

    fun addSubscription(url: String, name: String) = viewModelScope.launch {
        busy.value = true
        Subscriptions.add(url, name)
            .onSuccess { message.value = "Добавлено серверов: $it" }
            .onFailure { message.value = "Подписка добавлена, но не загрузилась: ${it.message}" }
        busy.value = false
    }

    fun refreshSubscriptions(silent: Boolean = false) = viewModelScope.launch {
        busy.value = true
        val results = Subscriptions.refreshAll()
        busy.value = false
        if (!silent) {
            val failed = results.count { it.isFailure }
            message.value = if (failed == 0) "Подписки обновлены" else "Не обновилось подписок: $failed"
        }
    }

    fun refreshSubscription(id: String) = viewModelScope.launch {
        busy.value = true
        Subscriptions.refresh(id)
            .onSuccess { message.value = "Серверов: $it" }
            .onFailure { message.value = "Ошибка: ${it.message}" }
        busy.value = false
    }

    fun removeSubscription(id: String) = Subscriptions.remove(id)

    fun addLinks(text: String) {
        val n = Subscriptions.addLinks(text)
        message.value = if (n > 0) "Добавлено серверов: $n" else "Не нашёл поддерживаемых ссылок"
    }

    fun removeProfile(id: String) = Subscriptions.removeProfile(id)

    fun checkUpdate(silent: Boolean = false) = viewModelScope.launch {
        Updater.checkLatest()
            .onSuccess {
                update.value = it
                if (!silent && it == null) message.value = "У вас последняя версия"
            }
            .onFailure { if (!silent) message.value = "Не удалось проверить: ${it.message}" }
    }

    fun installUpdate() = viewModelScope.launch {
        val r = update.value ?: return@launch
        updateProgress.value = 0f
        Updater.downloadAndInstall(app, r) { updateProgress.value = it }
            .onFailure { message.value = it.message }
        updateProgress.value = null
    }
}
