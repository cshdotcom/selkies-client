package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.GamepadInfo
import com.selkies.client.core.gamepadSnapshot
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.delay

/**
 * 手柄面板（v2.5.0，对齐官方 GamepadVisualizer）：
 * - 已连接手柄列表（最多 4 槽位，同服务器）
 * - 按钮实时高亮（W3C 17 键）+ 双摇杆 XY 可视化 + 扳机条
 * - 「测试振动」按钮（本地直测 rumble 通路）
 */
@Composable
fun GamepadPanel(session: SelkiesSession) {
    var pads by remember { mutableStateOf<List<GamepadInfo>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    // 50ms 刷新（20fps 可视化足够）
    LaunchedEffect(Unit) {
        while (true) {
            pads = gamepadSnapshot()
            tick++
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (pads.isEmpty()) {
            SectionCard("未检测到手柄") {
                Text(
                    "将手柄通过 USB 或蓝牙连接到本设备后，此处会自动出现。\n" +
                            "· Android：按手柄上任意键唤醒\n" +
                            "· 桌面：即插即用（XInput/蓝牙）\n" +
                            "· 服务器共 4 个手柄槽位（与官方一致）\n" +
                            "· 远程游戏里的振动会驱动本机手柄马达",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        pads.forEach { pad ->
            SectionCard("手柄 ${pad.index + 1}：${pad.name}") {
                // 摇杆 + 扳机
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StickView("左摇杆", pad.axes.getOrNull(0) ?: 0f, pad.axes.getOrNull(1) ?: 0f)
                    StickView("右摇杆", pad.axes.getOrNull(2) ?: 0f, pad.axes.getOrNull(3) ?: 0f)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TriggerBar("LT", pad.axes.getOrNull(4) ?: 0f, Modifier.weight(1f))
                    TriggerBar("RT", pad.axes.getOrNull(5) ?: 0f, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                // 按钮（W3C 顺序分组）
                val groups = listOf(
                    listOf("A" to 0, "B" to 1, "X" to 2, "Y" to 3),
                    listOf("LB" to 4, "RB" to 5, "LT键" to 6, "RT键" to 7),
                    listOf("Back" to 8, "Start" to 9, "LS" to 10, "RS" to 11),
                    listOf("↑" to 12, "↓" to 13, "←" to 14, "→" to 15, "☰" to 16)
                )
                groups.forEach { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        group.forEach { (label, idx) ->
                            val pressed = (pad.buttons.getOrNull(idx) ?: 0f) > 0.5f
                            PadKey(label, pressed)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    // 本地振动测试（验证 rumble 通路：面板 → 平台 → 手柄马达）
                    session.platformTestRumble(pad.index)
                }) { Text("测试振动（${pad.index}）") }
            }
        }
    }
}

@Composable
private fun StickView(label: String, x: Float, y: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // 中心十字
            Box(
                Modifier
                    .size(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), CircleShape)
            )
            // 摇杆头（随轴值摆动：±1 → ±32dp）
            Box(
                modifier = Modifier
                    .offset(x = (x.coerceIn(-1f, 1f) * 32).dp, y = (y.coerceIn(-1f, 1f) * 32).dp)
                    .size(22.dp)
                    .background(
                        if (x != 0f || y != 0f) Color(0xFF60A5FA) else MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$label (${String.format(java.util.Locale.US, "%.2f, %.2f", x, y)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TriggerBar(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                String.format(java.util.Locale.US, "%.0f%%", (value.coerceIn(0f, 1f)) * 100),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(Color(0xFF4ADE80), RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun PadKey(label: String, pressed: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (pressed) Color(0xFF4ADE80) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(width = 52.dp, height = 34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (pressed) FontWeight.Bold else FontWeight.Normal,
                color = if (pressed) Color(0xFF052E16) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
