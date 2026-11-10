package com.mkred.xrayclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.mkred.xrayclient.R
import com.mkred.xrayclient.core.AutoCheck
import com.mkred.xrayclient.core.Net
import com.mkred.xrayclient.core.NetworkState
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
        private const val ACTION_RELOAD = "reload"
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

        /** Перезапустить ядро на том же сервере (изменились правила маршрутизации). */
        fun reload(ctx: Context) {
            if (VpnStateHolder.state.value.status == VpnStatus.CONNECTED) send(ctx, ACTION_RELOAD)
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastMobile: Boolean? = null
    /** Порт локального прокси для запросов приложения через туннель. */
    private var localPort: Int? = null
    /** Защищает ядро: нельзя читать счётчики, пока оно останавливается. */
    private val coreLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "Команда: ${intent?.action ?: "запуск системой"}")
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { shutdown() }
                return START_NOT_STICKY
            }
            ACTION_RESELECT -> scope.launch { reselect("изменены настройки") }
            ACTION_RETUNNEL -> scope.launch { retunnel() }
            ACTION_RELOAD -> scope.launch { reloadCore() }
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
        AutoCheck.stop()
        unwatchScreen()
        unwatchNetwork()
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
        ServerSelector.reset()
        val choice = ServerSelector.pick(null)
        val profile = choice?.profile
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
        localPort = runCatching { Net.freePort() }.getOrNull()

        if (!startCore(profile)) {
            fail(VpnStateHolder.state.value.message ?: "Xray не запустился: проверьте сервер")
            return@withLock
        }
        Stats.startSession()
        Stats.recordSwitch(
            SwitchEvent(
                System.currentTimeMillis(), null, profile.name, SwitchKind.CONNECT,
                "режим «${modeName(data.mode)}» · ${choice.reason}",
            ),
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

    private suspend fun reloadCore() = lock.withLock {
        val p = current ?: return@withLock
        AppLog.i(TAG, "Перезапуск ядра (изменились правила маршрутизации)")
        stopCore()
        startCore(p)
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
            synchronized(coreLock) { synchronized(XrayCore.startLock) { c.startLoop(XrayConfig.forVpn(p, AppStore.data.value.logLevel, localPort, AppStore.data.value.bypassRu), fd) } }
            Net.proxyPort = localPort
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

    private fun stopCore() = synchronized(coreLock) {
        Net.proxyPort = null
        runCatching { controller?.stopLoop() }
    }

    /**
     * @param cause null — плановая проверка; иначе причина внеплановой (настройки, сеть):
     *              тогда переключаемся сразу, без ожидания подтверждения.
     */
    private suspend fun reselect(cause: String? = null) = lock.withLock {
        if (tun == null) return@withLock
        val choice = ServerSelector.pick(current, immediate = cause != null) ?: return@withLock
        if (choice.profile.id == current?.id) return@withLock
        if (cause != null) switchTo(choice.profile, SwitchKind.SETTINGS, "$cause · ${choice.reason}")
        else switchTo(choice.profile, choice.kind, choice.reason)
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
                AutoCheck.onSecond(down, up)
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
            combine(AppStore.data, AppStore.delays, VpnStateHolder.state, Stats.speeds) { d, delays, st, speeds ->
                listOf(d.mode, d.metric, st.profileId, st.profileId?.let { delays[it] }, st.profileId?.let { speeds[it]?.lastOrNull() })
            }
                .distinctUntilChanged()
                .collect { if (current != null) updateNotification() }
        }
    }

    /** Следим за сменой Wi-Fi ↔ мобильная сеть: от неё зависят блоки «только в мобильной сети». */
    private fun watchNetwork() {
        if (networkCallback != null) return
        lastMobile = NetworkState.isMobile()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val mobile = NetworkState.isMobile()
                if (mobile == lastMobile) return
                lastMobile = mobile
                AppLog.i(TAG, "Сеть: ${if (mobile) "мобильная" else "Wi-Fi/другая"}")
                val d = AppStore.data.value
                if (d.mode == SelectionMode.PRIORITY && d.blocks.any { it.mobileOnly }) {
                    scope.launch { reselect("сменилась сеть") }
                }
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { AppLog.w(TAG, "Не удалось следить за сетью", it) }
    }

    private fun unwatchNetwork() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private val autoHost = object : AutoCheck.Host {
        override val current: Profile? get() = this@XrayVpnService.current
        override suspend fun switchTo(p: Profile, kind: SwitchKind, reason: String) {
            lock.withLock {
                if (tun != null && this@XrayVpnService.current?.id != p.id) this@XrayVpnService.switchTo(p, kind, reason)
            }
        }
    }

    private var screenReceiver: android.content.BroadcastReceiver? = null

    /** Экран включили — сразу проверяем, выключили — проверяем реже. */
    private fun watchScreen() {
        if (screenReceiver != null) return
        AutoCheck.screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> { AutoCheck.screenOn = true; AutoCheck.requestProbe() }
                    Intent.ACTION_SCREEN_OFF -> AutoCheck.screenOn = false
                }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { screenReceiver = r }
    }

    private fun unwatchScreen() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    private fun startMonitor() {
        startNotifier()
        startTraffic()
        watchNetwork()
        watchScreen()
        AutoCheck.start(scope, autoHost)
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
        AutoCheck.stop()
        unwatchScreen()
        unwatchNetwork()
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
        val quality = AppStore.data.value.metric == com.mkred.xrayclient.data.Metric.QUALITY
        val mode = when (AppStore.data.value.mode) {
            SelectionMode.FASTEST -> if (quality) "Лучший" else "Быстрый"
            SelectionMode.PRIORITY -> "По приоритету"
            SelectionMode.MANUAL -> "Вручную"
        }
        val ping = if (quality) " · качество " + com.mkred.xrayclient.core.Quality.of(p).label
        else AppStore.delays.value[p.id]?.takeIf { it > 0 }?.let { " · $it мс" }.orEmpty()
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
