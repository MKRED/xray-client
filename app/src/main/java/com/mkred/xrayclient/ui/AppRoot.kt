package com.mkred.xrayclient.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Главная", Icons.Power),
    Tab("Серверы", Icons.Servers),
    Tab("Источники", Icons.Link),
    Tab("Статистика", Icons.Chart),
    Tab("Настройки", Icons.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel, onToggleVpn: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    Scaffold(
        containerColor = C.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = C.Surface2) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title, maxLines = 1, softWrap = false, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.AccentText,
                            selectedTextColor = C.AccentText,
                            indicatorColor = C.AccentBg,
                            unselectedIconColor = C.Muted,
                            unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(vm, onToggleVpn, onOpenServers = { tab = 1 }, modifier = m)
            1 -> ServersScreen(vm, m)
            2 -> SourcesScreen(vm, onToggleVpn, m)
            3 -> StatsScreen(vm, m)
            else -> SettingsScreen(vm, m)
        }
    }
}
