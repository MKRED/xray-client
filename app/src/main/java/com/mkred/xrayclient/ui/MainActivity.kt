package com.mkred.xrayclient.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mkred.xrayclient.log.AppLog
import com.mkred.xrayclient.service.VpnStatus
import com.mkred.xrayclient.service.VpnStateHolder
import com.mkred.xrayclient.service.XrayVpnService

class MainActivity : ComponentActivity() {

    companion object {
        /** Открыто из плитки, когда для подключения нужно сначала выдать разрешение VPN. */
        const val EXTRA_CONNECT = "connect"
    }

    private val vm: MainViewModel by viewModels()

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) XrayVpnService.start(this)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val crash = runCatching { AppLog.lastCrash.takeIf { it.exists() }?.readText() }.getOrNull()
        setContent {
            AppTheme {
                var crashText by remember { mutableStateOf(crash) }
                val text = crashText
                if (text != null) {
                    CrashScreen(
                        text,
                        onResetStats = {
                            runCatching { java.io.File(filesDir, "stats.json").delete() }
                            runCatching { AppLog.lastCrash.delete() }
                            // Перезапуск процесса, чтобы статистика загрузилась заново
                            startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            Runtime.getRuntime().exit(0)
                        },
                        onContinue = {
                            runCatching { AppLog.lastCrash.delete() }
                            crashText = null
                        },
                    )
                } else {
                    AppRoot(vm, onToggleVpn = ::toggleVpn)
                }
            }
        }
        handleConnectExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConnectExtra(intent)
    }

    private fun handleConnectExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
            intent.removeExtra(EXTRA_CONNECT)
            if (VpnStateHolder.state.value.status == VpnStatus.DISCONNECTED) toggleVpn()
        }
    }

    private fun toggleVpn() {
        if (VpnStateHolder.state.value.status != VpnStatus.DISCONNECTED) {
            XrayVpnService.stop(this)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else XrayVpnService.start(this)
    }
}
