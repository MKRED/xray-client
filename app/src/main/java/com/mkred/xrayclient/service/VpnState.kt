package com.mkred.xrayclient.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class VpnState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val profileId: String? = null,
    val profileName: String? = null,
    val connectedAt: Long = 0,
    val message: String? = null,
)

/** Общее состояние VPN: сервис пишет, интерфейс читает. */
object VpnStateHolder {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun set(transform: (VpnState) -> VpnState) {
        _state.value = transform(_state.value)
    }
}
