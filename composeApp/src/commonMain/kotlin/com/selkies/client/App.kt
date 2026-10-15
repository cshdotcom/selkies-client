package com.selkies.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.selkies.client.core.SettingsStore
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.PlatformServices
import com.selkies.client.platform.getPlatformServices
import com.selkies.client.theme.SelkiesTheme
import com.selkies.client.ui.AddServerScreen
import com.selkies.client.ui.ServerListScreen
import com.selkies.client.ui.SessionScreen
import com.selkies.client.ui.SettingsScreen
import com.selkies.client.ui.panels.HotkeyManagerScreen

/** 导航路由 */
sealed class Screen {
    object ServerList : Screen()
    data class AddEdit(val serverId: String?) : Screen()
    data class Session(val serverId: String) : Screen()
    object Settings : Screen()
    object HotkeyManager : Screen()
}

/** 全局应用状态 */
class AppState(
    val platform: PlatformServices = getPlatformServices(),
    val store: SettingsStore = SettingsStore(getPlatformServices().configDir())
) {
    var themeMode by mutableStateOf(store.settings.value.themeMode)
    var currentScreen by mutableStateOf<Screen>(Screen.ServerList)
    /** 数据版本号：servers/hotkeys/settings 变化时递增，驱动 Compose 重组 */
    var dataVersion by mutableStateOf(0)

    init {
        store.loadAll()
        store.settings.onChange { s ->
            themeMode = s.themeMode
            dataVersion++
        }
        store.servers.onChange { dataVersion++ }
        store.hotkeys.onChange { dataVersion++ }
    }

    fun navigate(screen: Screen) {
        currentScreen = screen
    }
}

@Composable
fun App() {
    val state = remember { AppState() }
    SelkiesTheme(themeMode = state.themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = state.currentScreen) {
                is Screen.ServerList -> ServerListScreen(state)
                is Screen.AddEdit -> AddServerScreen(state, screen.serverId)
                is Screen.Session -> {
                    val server = state.store.servers.value.firstOrNull { it.id == screen.serverId }
                    if (server != null) {
                        SessionScreen(state, server)
                    } else {
                        ServerListScreen(state)
                    }
                }
                is Screen.Settings -> SettingsScreen(state)
                is Screen.HotkeyManager -> HotkeyManagerScreen(state)
            }
        }
    }
}
