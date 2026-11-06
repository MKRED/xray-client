package com.mkred.xrayclient.ui

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.mkred.xrayclient.service.VpnStatus
import com.mkred.xrayclient.service.VpnStateHolder
import com.mkred.xrayclient.service.XrayVpnService

class MainActivity : ComponentActivity() {

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
        setContent {
            AppTheme {
                AppRoot(vm, onToggleVpn = ::toggleVpn)
            }
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
