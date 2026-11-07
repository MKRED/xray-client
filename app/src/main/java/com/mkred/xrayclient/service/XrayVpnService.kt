package com.mkred.xrayclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.mkred.xrayclient.R
import com.mkred.xrayclient.core.ServerSelector
import com.mkred.xrayclient.core.XrayConfig
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.data.SplitMode
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.data.SwitchEvent
import com.mkred.xrayclient.data.SwitchKind
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreController

class XrayVpnService : VpnService() {

    companion object {
        private const val TAG = "XrayVpn"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RESELECT = "reselect"
        private const val ACTION_RETUNNEL = "retunnel"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** Пересобрать выбор сервера сейчас (например, после смены режима). */
        fun reselect(ctx: Context) {
            if (VpnStateHolder.state.value.status != VpnStatus.DISCONNECTED) send(ctx, ACTION_RESELECT)
        }

        /** Пересоздать VPN-интерфейс (после смены списка приложений), не отключаясь. */
        fun retunnel(ctx: Context) {
            if (VpnStateHolder.state.value.status == VpnStatus.CONNECTED) send(ctx, ACTION_RETUNNEL)
        }

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, XrayVpnService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var current: Profile? = null
    private var monitor: Job? = null
    private var notifier: Job? = null
    private var traffic: Job? = null
    /** Защищает ядро: нельзя читать счётчики, пока оно останавливается. */
    private val coreLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "Команда: ${intent?.action ?: "запуск системой"}")
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { shutdown() }
                return START_NOT_STICKY
            }
            ACTION_RESELECT -> scope.launch { reselect(fromSettings = true) }
            ACTION_RETUNNEL -> scope.launch { retunnel() }
            // ACTION_START, а также запуск системой («Постоянная VPN»)
            else -> {
                startForegroundCompat(getString(R.string.app_name), "Подключение…")
                scope.launch { connect() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        AppLog.w(TAG, "Система отозвала VPN")
        // Система отозвала VPN (например, включили другой VPN)
        scope.launch { shutdown() }
    }

    override fun onDestroy() {
        stopCore()
        runCatching { tun?.close() }
        scope.cancel()
        VpnStateHolder.set { VpnState(message = it.message) }
        super.onDestroy()
    }

    // ---- подключение ----

    private suspend fun connect() = lock.withLock {
        if (tun != null) return@withLock
        VpnStateHolder.set { it.copy(status = VpnStatus.CONNECTING, message = "Выбор сервера…") }

        val data = AppStore.data.value
        AppLog.i(TAG, "Подключение: режим ${data.mode}, серверов ${AppStore.usable.value.size} из ${AppStore.profiles.value.size}")
        val profile = ServerSelector.pick()
        if (profile == null) {
            fail("Нет серверов — добавьте подписку")
            return@withLock
        }

        val iface = runCatching { establish() }
            .onFailure { AppLog.e(TAG, "establish()", it) }
            .getOrNull() ?: run {
            fail("Не удалось создать VPN-интерфейс")
            return@withLock
        }
        tun = iface
        controller = XrayCore.newController { }

        if (!startCore(profile)) {
            fail(VpnStateHolder.state.value.message ?: "Xray не запустился: проверьте сервер")
            return@withLock
        }
        Stats.startSession()
        Stats.recordSwitch(
            SwitchEvent(System.currentTimeMillis(), null, profile.name, SwitchKind.CONNECT, "режим «${modeName(data.mode)}»"),
        )
        startMonitor()
    }

    private fun establish(): ParcelFileDescriptor? = Builder()
        .setSession(getString(R.string.app_name))
        .setMtu(XrayConfig.MTU)
        .addAddress("10.10.14.1", 30)
        .addRoute("0.0.0.0", 0)
        .addAddress("fd00:10:10:14::1", 126)
        .addRoute("::", 0)
        .addDnsServer("1.1.1.1")
        .addDnsServer("8.8.8.8")
        .applySplitTunneling()
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
        .establish()

    /**
     * Какие приложения идут через VPN. Само приложение (и ядро Xray внутри него)
     * всегда идёт мимо туннеля, иначе трафик ядра зациклится.
     * Android не позволяет смешивать allowed и disallowed в одном интерфейсе.
     */
    private fun Builder.applySplitTunneling(): Builder {
        val d = AppStore.data.value
        val apps = d.splitApps - packageName
        when (d.splitMode) {
            SplitMode.ONLY_SELECTED -> {
                var added = 0
                apps.forEach { pkg ->
                    runCatching { addAllowedApplication(pkg); added++ }
                        .onFailure { AppLog.w(TAG, "Приложение не найдено: $pkg") }
                }
                if (added == 0) {
                    AppLog.w(TAG, "Список «только выбранные» пуст — через VPN пойдут все приложения")
                    addDisallowedApplication(packageName)
                } else {
                    AppLog.i(TAG, "Через VPN только выбранные приложения: $added")
                }
            }
            SplitMode.BYPASS_SELECTED -> {
                addDisallowedApplication(packageName)
                var added = 0
                apps.forEach { pkg ->
                    runCatching { addDisallowedApplication(pkg); added++ }
                        .onFailure { AppLog.w(TAG, "Приложение не найдено: $pkg") }
                }
                AppLog.i(TAG, "Мимо VPN приложений: $added")
            }
            SplitMode.ALL -> {
                addDisallowedApplication(packageName)
                AppLog.i(TAG, "Через VPN все приложения")
            }
        }
        return this
    }

    /** Новый интерфейс с текущими настройками приложений; ядро перезапускается на том же сервере. */
    private suspend fun retunnel() = lock.withLock {
        val p = current ?: return@withLock
        AppLog.i(TAG, "Пересоздание VPN-интерфейса (изменились приложения)")
        val fresh = runCatching { establish() }
            .onFailure { AppLog.e(TAG, "establish()", it) }
            .getOrNull() ?: return@withLock
        stopCore()
        val old = tun
        tun = fresh
        startCore(p)
        runCatching { old?.close() }
    }

    private fun startCore(p: Profile): Boolean {
        val fd = tun?.fd ?: return false
        val c = controller ?: return false
        return try {
            AppLog.i(TAG, "Запуск ядра: «${p.name}» ${p.protocol} ${p.address}:${p.port} ${p.network}/${p.security}")
            synchronized(coreLock) { c.startLoop(XrayConfig.forVpn(p, AppStore.data.value.logLevel), fd) }
            AppLog.i(TAG, "Ядро запущено")
            current = p
            VpnStateHolder.set {
                it.copy(
                    status = VpnStatus.CONNECTED,
                    profileId = p.id,
                    profileName = p.name,
                    connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt,
                    message = null,
                )
            }
            updateNotification()
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Ядро не запустилось: ${e.message}", e)
            VpnStateHolder.set { it.copy(message = "Ошибка ядра: ${e.message}") }
            false
        }
    }

    /** Переключение на другой сервер без пересоздания VPN-интерфейса. */
    private fun switchTo(p: Profile, kind: SwitchKind, reason: String) {
        AppLog.i(TAG, "Переключение: «${current?.name}» → «${p.name}» ($reason)")
        Stats.recordSwitch(SwitchEvent(System.currentTimeMillis(), current?.name, p.name, kind, reason))
        stopCore()
        if (!startCore(p)) VpnStateHolder.set { it.copy(message = "Не удалось переключиться на ${p.name}") }
    }

    private fun stopCore() = synchronized(coreLock) { runCatching { controller?.stopLoop() } }

    private suspend fun reselect(fromSettings: Boolean = false) = lock.withLock {
        if (tun == null) return@withLock
        val prev = current
        val next = ServerSelector.pick(prev) ?: return@withLock
        if (next.id == prev?.id) return@withLock

        val data = AppStore.data.value
        val delays = AppStore.delays.value
        val prevDelay = prev?.let { delays[it.id] }
        val (kind, reason) = when {
            fromSettings -> SwitchKind.SETTINGS to "изменены настройки"
            data.mode == SelectionMode.PRIORITY -> {
                val order = AppStore.orderedProfiles().map { it.id }
                val prevIdx = prev?.let { order.indexOf(it.id) } ?: -1
                if (prevIdx >= 0 && order.indexOf(next.id) < prevIdx) SwitchKind.PRIORITY to "выше по приоритету"
                else SwitchKind.FAIL to "не отвечал"
            }
            prevDelay == null || prevDelay <= 0 -> SwitchKind.FAIL to "не отвечал"
            else -> SwitchKind.FASTER to "быстрее на ${prevDelay - (delays[next.id] ?: prevDelay)} мс"
        }
        switchTo(next, kind, reason)
    }

    /** Раз в секунду забираем счётчики трафика прокси из ядра. */
    private fun startTraffic() {
        traffic?.cancel()
        traffic = scope.launch {
            var lastSave = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val (down, up) = queryTraffic()
                Stats.addTraffic(down, up, current?.id, current?.name)
                if (System.currentTimeMillis() - lastSave > 60_000) {
                    Stats.save()
                    lastSave = System.currentTimeMillis()
                }
            }
        }
    }

    private fun queryTraffic(): Pair<Long, Long> = synchronized(coreLock) {
        val c = controller ?: return 0L to 0L
        if (!c.isRunning) return 0L to 0L
        var down = 0L
        var up = 0L
        runCatching { c.queryAllOutboundTrafficStats() }.getOrDefault("").split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size != 3 || parts[0] != "proxy") return@forEach
            val v = parts[2].toLongOrNull() ?: return@forEach
            if (parts[1] == "downlink") down += v else if (parts[1] == "uplink") up += v
        }
        down to up
    }

    private fun modeName(m: SelectionMode) = when (m) {
        SelectionMode.FASTEST -> "Быстрый"
        SelectionMode.PRIORITY -> "По приоритету"
        SelectionMode.MANUAL -> "Вручную"
    }

    /** Периодическая проверка: переключаемся, если текущий упал или нашёлся лучше. */
    /** Уведомление следит за режимом и пингом текущего сервера. */
    private fun startNotifier() {
        notifier?.cancel()
        notifier = scope.launch {
            combine(AppStore.data, AppStore.delays, VpnStateHolder.state) { d, delays, st ->
                Triple(d.mode, st.profileId, st.profileId?.let { delays[it] })
            }
                .distinctUntilChanged()
                .collect { if (current != null) updateNotification() }
        }
    }

    private fun startMonitor() {
        startNotifier()
        startTraffic()
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(AppStore.data.value.checkIntervalSec.coerceAtLeast(10) * 1000L)
                if (AppStore.data.value.mode == SelectionMode.MANUAL) {
                    // Переключаться не нужно, но пинг текущего сервера пишем в статистику
                    current?.let { p -> AppStore.setDelay(p.id, XrayCore.measure(p, AppStore.data.value.testUrl)) }
                    continue
                }
                runCatching { reselect() }.onFailure { AppLog.w(TAG, "Ошибка фоновой проверки", it) }
            }
        }
    }

    private suspend fun fail(msg: String) {
        AppLog.w(TAG, "Остановка из-за ошибки: $msg")
        shutdown()
        VpnStateHolder.set { it.copy(message = msg) }
    }

    private suspend fun shutdown() {
        AppLog.i(TAG, "Отключение")
        monitor?.cancel()
        notifier?.cancel()
        traffic?.cancel()
        stopCore()
        Stats.save()
        runCatching { tun?.close() }
        tun = null
        controller = null
        current = null
        VpnStateHolder.set { VpnState() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- уведомление ----

    private fun startForegroundCompat(title: String, text: String) {
        val n = notification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        val p = current ?: return
        val mode = when (AppStore.data.value.mode) {
            SelectionMode.FASTEST -> "Быстрый"
            SelectionMode.PRIORITY -> "По приоритету"
            SelectionMode.MANUAL -> "Вручную"
        }
        val ping = AppStore.delays.value[p.id]?.takeIf { it > 0 }?.let { " · $it мс" }.orEmpty()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Подключено · $mode", p.name + ping))
    }

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .build()
    }
}
