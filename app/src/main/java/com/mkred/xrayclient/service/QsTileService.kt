package com.mkred.xrayclient.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Плитка в шторке уведомлений: включить / выключить VPN. */
class QsTileService : TileService() {

    companion object {
        /** Попросить систему обновить плитку (например, VPN включили из приложения). */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, QsTileService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watcher?.cancel()
        watcher = scope.launch { VpnStateHolder.state.collect { render(it) } }
    }

    override fun onStopListening() {
        watcher?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val status = VpnStateHolder.state.value.status
        AppLog.i("Tile", "Нажатие, состояние $status")
        if (status != VpnStatus.DISCONNECTED) {
            XrayVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            // Разрешения на VPN ещё нет — его можно выдать только из приложения
            openApp(connect = true)
            return
        }
        XrayVpnService.start(this)
    }

    private fun render(s: VpnState) {
        val tile = qsTile ?: return
        tile.state = when (s.status) {
            VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnStatus.CONNECTING -> Tile.STATE_ACTIVE
            VpnStatus.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        tile.label = "Xray VPN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (s.status) {
                VpnStatus.CONNECTED -> s.profileName ?: "Подключено"
                VpnStatus.CONNECTING -> "Подключение…"
                VpnStatus.DISCONNECTED -> "Отключено"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(connect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT, connect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
