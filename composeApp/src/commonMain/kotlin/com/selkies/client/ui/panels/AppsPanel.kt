package com.selkies.client.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.delay

/**
 * 远程应用启动面板（对齐官方前端 Apps 面板）：
 * - 展示服务器下发的应用列表（server_apps）
 * - 点击即通过 cmd,<command> 在远端启动
 * - 支持手动输入任意命令执行（服务器须开启 command_enabled）
 */
@Composable
fun AppsPanel(session: SelkiesSession) {
    val apps by session.apps.collectAsState()
    var manualCmd by remember { mutableStateOf("") }
    var launched by remember { mutableStateOf<String?>(null) }

    // 提示自动消失
    LaunchedEffect(launched) {
        if (launched != null) {
            delay(2000)
            launched = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (launched != null) {
            Text(
                "已发送启动：$launched",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = manualCmd,
            onValueChange = { manualCmd = it },
            label = { Text("远程命令（cmd,）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                TextButton(
                    enabled = manualCmd.isNotBlank(),
                    onClick = {
                        session.sendCommand(manualCmd.trim())
                        launched = manualCmd.trim()
                        manualCmd = ""
                    }
                ) { Text("执行") }
            }
        )
        Spacer(Modifier.height(10.dp))
        if (apps.isEmpty()) {
            Text(
                "服务器未下发应用列表（server_apps 为空）。\n可上方直接输入命令启动远程程序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(apps, key = { it.first + it.second }) { (name, cmd) ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.Medium)
                                if (cmd.isNotBlank() && cmd != name) {
                                    Text(
                                        cmd,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = {
                                session.sendCommand(if (cmd.isBlank()) name else cmd)
                                launched = name
                            }) {
                                Icon(Icons.Filled.Launch, "启动", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
