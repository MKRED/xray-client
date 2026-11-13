package com.mkred.xrayclient.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.PriorityBlock
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.data.SplitMode
import com.mkred.xrayclient.data.Subscriptions
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.service.VpnStateHolder
import com.mkred.xrayclient.service.XrayVpnService
import com.mkred.xrayclient.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val data = AppStore.data
    val profiles = AppStore.profiles
    val usable = AppStore.usable
    val delays = AppStore.delays
    val vpn = VpnStateHolder.state

    val testing = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val update = MutableStateFlow<Updater.Release?>(null)
    val updateProgress = MutableStateFlow<Updater.Progress?>(null)
    val checkingUpdate = MutableStateFlow(false)

    init {
        checkUpdate(silent = true)
        // Подписки старше 6 часов обновляем при запуске
        val stale = data.value.subscriptions.any { System.currentTimeMillis() - it.updatedAt > 6 * 3600_000L }
        if (stale) refreshSubscriptions(silent = true)
        if (data.value.bypassRu && com.mkred.xrayclient.core.GeoRules.isStale()) updateGeo(silent = true)
    }

    fun setMode(mode: SelectionMode) {
        AppLog.i("Settings", "Режим выбора: $mode")
        AppStore.update { it.copy(mode = mode) }
        XrayVpnService.reselect(app)
    }

    fun selectServer(id: String) {
        AppStore.update { it.copy(selectedId = id, mode = SelectionMode.MANUAL) }
        XrayVpnService.reselect(app)
    }

    // ---- метрика и тест скорости ----

    val speedRun = com.mkred.xrayclient.core.SpeedTest.run

    fun setMetric(m: com.mkred.xrayclient.data.Metric) {
        if (data.value.metric == m) return
        AppLog.i("Settings", "Метрика: $m")
        AppStore.update { it.copy(metric = m) }
        XrayVpnService.reselect(app)
    }

    fun setSpeedTestMb(mb: Int) = AppStore.update { it.copy(speedTestMb = mb) }

    /** Шкалы качества; пинг «ноль» всегда хотя бы на 50 мс выше «полного балла». */
    fun setQualityLimits(speedMax: Int? = null, pingFull: Int? = null, pingZero: Int? = null) = AppStore.update { d ->
        val s = (speedMax ?: d.qSpeedMax).coerceIn(10, 500)
        val f = (pingFull ?: d.qPingFull).coerceIn(10, 300)
        var z = (pingZero ?: d.qPingZero).coerceIn(100, 3000)
        if (z < f + 50) z = f + 50
        d.copy(qSpeedMax = s, qPingFull = f, qPingZero = z)
    }

    fun resetQualityLimits() = AppStore.update { it.copy(qSpeedMax = 50, qPingFull = 50, qPingZero = 500) }

    fun setSpeedTestAllowed(subId: String, allowed: Boolean) {
        AppLog.i("Settings", "Тест скорости для подписки $subId: $allowed")
        AppStore.setSpeedTestAllowed(subId, allowed)
    }

    fun startSpeedTest(title: String, list: List<com.mkred.xrayclient.data.Profile>) =
        com.mkred.xrayclient.core.SpeedTest.start(title, list)

    fun stopSpeedTest() = com.mkred.xrayclient.core.SpeedTest.stop()

    val autoStatus = com.mkred.xrayclient.core.AutoCheck.status

    fun setAutoCheck(on: Boolean) {
        AppLog.i("Settings", "Автопроверка скорости: $on")
        AppStore.update { it.copy(autoCheck = on) }
        if (on) com.mkred.xrayclient.core.AutoCheck.requestProbe()
    }

    fun setSlowMbps(v: Int) = AppStore.update { it.copy(slowMbps = v) }

    // ---- российские сайты напрямую ----

    val geoUpdating = MutableStateFlow(false)

    fun setBypassRu(on: Boolean) {
        AppLog.i("Settings", "Российские сайты напрямую: $on")
        AppStore.update { it.copy(bypassRu = on) }
        XrayVpnService.reload(app)
        if (on && com.mkred.xrayclient.core.GeoRules.isStale()) updateGeo(silent = true)
    }

    fun updateGeo(silent: Boolean = false) = viewModelScope.launch {
        if (geoUpdating.value) return@launch
        geoUpdating.value = true
        com.mkred.xrayclient.core.GeoRules.update(app)
            .onSuccess {
                if (!silent) message.value = "Базы обновлены"
                if (data.value.bypassRu) XrayVpnService.reload(app)
            }
            .onFailure { if (!silent) message.value = "Не удалось обновить базы: ${it.message}" }
        geoUpdating.value = false
    }

    // ---- блоки приоритета ----

    fun saveBlock(b: PriorityBlock) {
        AppLog.i("Settings", "Блок сохранён: ${b.type} ${b.keywords} sub=${b.subscriptionId} maxPing=${b.maxPingMs} mobile=${b.mobileOnly}")
        AppStore.saveBlock(b)
        XrayVpnService.reselect(app)
    }

    fun deleteBlock(id: String) {
        AppLog.i("Settings", "Блок удалён")
        AppStore.deleteBlock(id)
        XrayVpnService.reselect(app)
    }

    fun moveBlock(id: String, delta: Int) {
        AppStore.moveBlock(id, delta)
        XrayVpnService.reselect(app)
    }

    fun setInterval(sec: Int) = AppStore.update { it.copy(checkIntervalSec = sec) }

    fun setTestUrl(url: String) = AppStore.update { it.copy(testUrl = url.trim()) }

    fun testAll() = viewModelScope.launch {
        if (testing.value) return@launch
        testing.value = true
        try {
            XrayCore.measureAll(usable.value, data.value.testUrl)
        } finally {
            testing.value = false
        }
    }

    /** Проверить пинг только у указанных серверов (например, у серверов блока). */
    fun testProfiles(list: List<com.mkred.xrayclient.data.Profile>) = viewModelScope.launch {
        if (testing.value || list.isEmpty()) return@launch
        testing.value = true
        try {
            XrayCore.measureAll(list, data.value.testUrl)
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

    fun removeSubscription(id: String) {
        AppLog.i("Settings", "Подписка удалена: $id")
        Subscriptions.remove(id)
        XrayVpnService.reselect(app)
    }

    fun renameProfile(id: String, name: String) = AppStore.renameProfile(id, name)

    fun renameSubscription(id: String, name: String) = AppStore.renameSubscription(id, name)

    /** Подключиться к конкретному серверу: ручной режим на него. */
    fun connectTo(id: String) {
        AppLog.i("Settings", "Подключение к выбранному серверу")
        AppStore.update { it.copy(selectedId = id, mode = SelectionMode.MANUAL) }
        XrayVpnService.reselect(app)
    }

    /** Текст из буфера: ссылка на подписку или ссылки на серверы. */
    fun addFromClipboard(text: String?) {
        val t = text?.trim().orEmpty()
        when {
            t.isEmpty() -> message.value = "Буфер обмена пуст"
            t.startsWith("http://") || t.startsWith("https://") -> addSubscription(t, "")
            else -> addLinks(t)
        }
    }

    fun setExcluded(ids: Collection<String>, excluded: Boolean) {
        AppLog.i("Settings", (if (excluded) "Исключены" else "Включены") + " серверы: ${ids.size}")
        AppStore.setExcluded(ids, excluded)
        XrayVpnService.reselect(app)
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        AppLog.i("Settings", "Подписка $id: ${if (enabled) "включена" else "выключена"}")
        AppStore.setSubscriptionEnabled(id, enabled)
        XrayVpnService.reselect(app)
    }

    fun setExcludeKeywords(subscriptionId: String, text: String) {
        val words = text.split(',', '\n')
        AppLog.i("Settings", "Фильтр подписки $subscriptionId: ${words.map(String::trim).filter(String::isNotEmpty)}")
        AppStore.setExcludeKeywords(subscriptionId, words)
        XrayVpnService.reselect(app)
    }

    fun addLinks(text: String) {
        val n = Subscriptions.addLinks(text)
        message.value = if (n > 0) "Добавлено серверов: $n" else "Не нашёл поддерживаемых ссылок"
    }

    fun removeProfile(id: String) = Subscriptions.removeProfile(id)

    fun checkUpdate(silent: Boolean = false) = viewModelScope.launch {
        checkingUpdate.value = true
        Updater.checkLatest()
            .also { checkingUpdate.value = false }
            .onSuccess {
                update.value = it
                if (!silent && it == null) message.value = "У вас последняя версия"
            }
            .onFailure { if (!silent) message.value = "Не удалось проверить: ${it.message}" }
    }

    // ---- раздельное туннелирование ----

    fun setSplitMode(mode: SplitMode) {
        if (data.value.splitMode == mode) return
        AppLog.i("Settings", "Раздельное туннелирование: $mode")
        AppStore.update { it.copy(splitMode = mode) }
        XrayVpnService.retunnel(app)
    }

    fun setSplitApps(apps: Set<String>) {
        if (data.value.splitApps == apps) return
        AppLog.i("Settings", "Приложений в списке: ${apps.size}")
        AppStore.update { it.copy(splitApps = apps) }
        if (data.value.splitMode != SplitMode.ALL) XrayVpnService.retunnel(app)
    }

    // ---- логи ----

    fun setLogLevel(level: String) {
        AppStore.update { it.copy(logLevel = level) }
        AppLog.i("Settings", "Уровень лога: $level (для ядра применится при следующем подключении)")
    }

    suspend fun logTail(): List<String> = withContext(Dispatchers.IO) { AppLog.tail() }

    /** Открыть системное меню «Поделиться» с файлом лога. */
    fun shareLog() = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { AppLog.export(app) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "Отправить лог").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Сохранить лог в файл, выбранный пользователем (Загрузки и т.п.). */
    fun saveLog(target: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val file = AppLog.export(app)
                app.contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            }
        }
            .onSuccess { message.value = "Лог сохранён" }
            .onFailure { message.value = "Не удалось сохранить: ${it.message}" }
    }

    fun clearLog() = viewModelScope.launch(Dispatchers.IO) {
        AppLog.clear()
        message.value = "Лог очищен"
    }

    fun installUpdate() = viewModelScope.launch {
        val r = update.value ?: return@launch
        if (updateProgress.value != null) return@launch
        updateProgress.value = Updater.Progress(0, -1)
        Updater.downloadAndInstall(app, r) { updateProgress.value = it }
            .onFailure { message.value = it.message }
        updateProgress.value = null
    }
}
