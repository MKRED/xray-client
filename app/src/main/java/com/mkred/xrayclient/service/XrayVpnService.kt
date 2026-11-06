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
import android.util.Log
import androidx.core.content.ContextCompat
import com.mkred.xrayclient.R
import com.mkred.xrayclient.core.ServerSelector
import com.mkred.xrayclient.core.XrayConfig
import com.mkred.xrayclient.core.XrayCore
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** Пересобрать выбор сервера сейчас (например, после смены режима). */
        fun reselect(ctx: Context) {
            if (VpnStateHolder.state.value.status != VpnStatus.DISCONNECTED) send(ctx, ACTION_RESELECT)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { shutdown() }
                return START_NOT_STICKY
            }
            ACTION_RESELECT -> scope.launch { reselect() }
            // ACTION_START, а также запуск системой («Постоянная VPN»)
            else -> {
                startForegroundCompat(getString(R.string.app_name), "Подключение…")
                scope.launch { connect() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Система отозвала VPN (например, включили другой VPN)
        scope.launch { shutdown() }
    }

    override fun onDestroy() {
        runCatching { controller?.stopLoop() }
        runCatching { tun?.close() }
        scope.cancel()
        VpnStateHolder.set { VpnState(message = it.message) }
        super.onDestroy()
    }

    // ---- подключение ----

    private suspend fun connect() = lock.withLock {
        if (tun != null) return@withLock
        VpnStateHolder.set { it.copy(status = VpnStatus.CONNECTING, message = "Выбор сервера…") }

        val profile = ServerSelector.pick()
        if (profile == null) {
            fail("Нет серверов — добавьте подписку")
            return@withLock
        }

        val iface = establish() ?: run {
            fail("Не удалось создать VPN-интерфейс")
            return@withLock
        }
        tun = iface
        controller = XrayCore.newController { Log.i(TAG, it) }

        if (!startCore(profile)) {
            fail("Xray не запустился: проверьте сервер")
            return@withLock
        }
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
        // Само приложение (и ядро Xray внутри него) идёт мимо туннеля, иначе будет петля
        .addDisallowedApplication(packageName)
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
        .establish()

    private fun startCore(p: Profile): Boolean {
        val fd = tun?.fd ?: return false
        val c = controller ?: return false
        return try {
            c.startLoop(XrayConfig.forVpn(p), fd)
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
            updateNotification(p)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startLoop", e)
            false
        }
    }

    /** Переключение на другой сервер без пересоздания VPN-интерфейса. */
    private fun switchTo(p: Profile) {
        Log.i(TAG, "switch ${current?.name} -> ${p.name}")
        runCatching { controller?.stopLoop() }
        if (!startCore(p)) VpnStateHolder.set { it.copy(message = "Не удалось переключиться на ${p.name}") }
    }

    private suspend fun reselect() = lock.withLock {
        if (tun == null) return@withLock
        val next = ServerSelector.pick(current) ?: return@withLock
        if (next.id != current?.id) switchTo(next)
    }

    /** Периодическая проверка: переключаемся, если текущий упал или нашёлся лучше. */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(AppStore.data.value.checkIntervalSec.coerceAtLeast(10) * 1000L)
                if (AppStore.data.value.mode == SelectionMode.MANUAL) continue
                runCatching { reselect() }.onFailure { Log.w(TAG, "monitor", it) }
            }
        }
    }

    private suspend fun fail(msg: String) {
        Log.w(TAG, msg)
        shutdown()
        VpnStateHolder.set { it.copy(message = msg) }
    }

    private suspend fun shutdown() {
        monitor?.cancel()
        runCatching { controller?.stopLoop() }
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

    private fun updateNotification(p: Profile) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Подключено", p.name))
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
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .build()
    }
}
