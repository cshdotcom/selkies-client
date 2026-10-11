package com.selkies.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.Screen
import com.selkies.client.ui.panels.SectionCard
import com.selkies.client.ui.panels.SettingSwitch

/**
 * 应用设置：深浅色主题、剪贴板、输入法、触屏、音频
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AppState) {
    // ⚠️ MutableStateFlowCompat 已用 mutableStateOf 背书：组合中读取 .value 自动跟踪变化
    val settings = state.store.settings.value

    PlatformBackHandler { state.navigate(Screen.ServerList) }

    fun toggle(mutate: (com.selkies.client.model.AppSettings) -> Unit) {
        state.store.updateSettings(mutate)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { state.navigate(Screen.ServerList) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("外观主题") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = settings.themeMode == "system",
                        onClick = { updateTheme(state, "system") },
                        label = { Text("跟随系统") }
                    )
                    FilterChip(
                        selected = settings.themeMode == "light",
                        onClick = { updateTheme(state, "light") },
                        label = { Text("浅色") }
                    )
                    FilterChip(
                        selected = settings.themeMode == "dark",
                        onClick = { updateTheme(state, "dark") },
                        label = { Text("深色") }
                    )
                }
                Text(
                    "深色模式适合夜间与 OLED 屏幕省电；跟随系统将自动切换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("剪贴板同步") {
                SettingSwitch("本地复制 → 自动发送到远程", settings.clipboardAutoSend) {
                    toggle { it.clipboardAutoSend = !it.clipboardAutoSend } // ⚠️ 注意：it 为新副本，直接赋值入参布尔
                }
                SettingSwitch("远程剪贴板 → 自动应用到本地", settings.clipboardAutoReceive) {
                    toggle { it.clipboardAutoReceive = !it.clipboardAutoReceive }
                }
                SettingSwitch("允许二进制剪贴板（图片等）", settings.clipboardBinaryEnabled) {
                    toggle { it.clipboardBinaryEnabled = !it.clipboardBinaryEnabled }
                }
            }

            SectionCard("输入") {
                SettingSwitch("进入会话自动唤起输入法", settings.autoShowIme) {
                    toggle { it.autoShowIme = !it.autoShowIme }
                }
                SettingSwitch("默认触屏鼠标模式（显示指针）", settings.touchMouseMode) {
                    toggle { it.touchMouseMode = !it.touchMouseMode }
                }
                SettingSwitch("物理键盘直通远程", settings.physicalKeyboardPassthrough) {
                    toggle { it.physicalKeyboardPassthrough = !it.physicalKeyboardPassthrough }
                }
                SettingSwitch("手柄支持", settings.gamepadEnabled) {
                    toggle { it.gamepadEnabled = !it.gamepadEnabled }
                }
            }

            SectionCard("音频与硬件") {
                SettingSwitch("允许麦克风（远程可请求）", settings.micEnabled) {
                    toggle { it.micEnabled = !it.micEnabled }
                }
                SettingSwitch("允许摄像头（远程可请求）", settings.webcamEnabled) {
                    toggle { it.webcamEnabled = !it.webcamEnabled }
                }
                var volume by remember { mutableFloatStateOf(settings.speakerVolume) }
                Text("扬声器音量：${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        settings.speakerVolume = it
                        state.store.saveSettings()
                    },
                    valueRange = 0f..1f
                )
            }

            SectionCard("视频") {
                SettingSwitch("显示帧率悬浮层", settings.showFpsOverlay) {
                    toggle { it.showFpsOverlay = !it.showFpsOverlay }
                }
                SettingSwitch("卡顿时自动请求关键帧", settings.requestKeyframeOnStall) {
                    toggle { it.requestKeyframeOnStall = !it.requestKeyframeOnStall }
                }
            }

            SectionCard("关于") {
                Text("Selkies 原生客户端", fontWeight = FontWeight.Bold)
                Text(
                    "版本 ${state.platform.appVersion()} · ${state.platform.platformName}\n" +
                            "原生 Kotlin/Compose 实现，非 WebView 封装。\n" +
                            "协议：Selkies v2 WebSocket 流模式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun updateTheme(state: AppState, mode: String) {
    // 走 updateSettings（复制+重赋值）触发 onChange → AppState.themeMode/dataVersion 同步，
    // 设置页 FilterChip 的选中态才能可靠刷新（原地改 var 字段不会触发任何通知）
    state.store.updateSettings { it.themeMode = mode }
}
