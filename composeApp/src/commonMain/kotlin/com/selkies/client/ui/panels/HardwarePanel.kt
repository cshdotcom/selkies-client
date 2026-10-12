package com.selkies.client.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.HardwareDevice

/**
 * 硬件面板：远程硬件透传的设备选择（全平台一致）
 * - 摄像头：单选（服务器虚拟摄像头一次一路），可搜索，切换即热重启上行
 * - 麦克风：多选混音（多个麦克风同时上行，客户端 PCM 平均），可搜索、全选
 * - 扬声器：输出设备选择
 * - 顶行开关：麦克风/摄像头上行总开关
 */
@Composable
fun HardwarePanel(session: SelkiesSession) {
    val micDevices by session.micDevices.collectAsState()
    val cameraDevices by session.cameraDevices.collectAsState()
    val speakerDevices by session.speakerDevices.collectAsState()
    val selectedMics by session.selectedMicIds.collectAsState()
    val selectedCamera by session.selectedCameraId.collectAsState()
    val micOn by session.micActive.collectAsState()
    val camOn by session.webcamActive.collectAsState()

    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        session.refreshHardwareDevices()
        // 枚举很快，稍等即可关 loading
        kotlinx.coroutines.delay(600)
        loading = false
    }

    val filteredMics = micDevices.filter {
        search.isBlank() || it.name.contains(search, true) || it.detail.contains(search, true)
    }
    val filteredCameras = cameraDevices.filter {
        search.isBlank() || it.name.contains(search, true) || it.detail.contains(search, true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 搜索 + 刷新
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索设备（名称/类型）") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = {
                loading = true
                session.refreshHardwareDevices()
            }) { Icon(Icons.Filled.Refresh, "刷新设备") }
        }
        if (loading) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(6.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("正在枚举设备…", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
        ) {
            // ---- 麦克风 ----
            item {
                DeviceHeader(
                    icon = Icons.Filled.Mic,
                    title = "麦克风（可多选混音）",
                    right = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (filteredMics.isNotEmpty()) {
                                FilterChip(
                                    selected = filteredMics.isNotEmpty() && filteredMics.all { it.id in selectedMics },
                                    onClick = {
                                        val allSelected = filteredMics.isNotEmpty() && filteredMics.all { it.id in selectedMics }
                                        session.selectMics(if (allSelected) emptyList() else filteredMics.map { it.id })
                                    },
                                    label = { Text(if (selectedMics.size > 1) "全选(${selectedMics.size})" else "全选") }
                                )
                                Spacer(Modifier.size(6.dp))
                            }
                            Switch(checked = micOn, onCheckedChange = {
                                if (it) session.startMicUplink() else session.stopMicUplink()
                            })
                        }
                    }
                )
            }
            if (filteredMics.isEmpty() && !loading) {
                item { EmptyDeviceRow("未找到麦克风设备") }
            }
            items(filteredMics, key = { "mic-" + it.id }) { dev ->
                DeviceRow(
                    icon = Icons.Filled.Mic,
                    device = dev,
                    selected = dev.id in selectedMics,
                    multiSelect = true,
                    onClick = {
                        val next = if (dev.id in selectedMics) selectedMics - dev.id else selectedMics + dev.id
                        session.selectMics(next)
                    }
                )
            }

            // ---- 摄像头 ----
            item {
                DeviceHeader(
                    icon = Icons.Filled.PhotoCamera,
                    title = "摄像头（单选 · 远程虚拟摄像头一次一路）",
                    right = {
                        Switch(checked = camOn, onCheckedChange = {
                            if (it) session.startWebcamUplink() else session.stopWebcamUplink()
                        })
                    }
                )
            }
            if (filteredCameras.isEmpty() && !loading) {
                item { EmptyDeviceRow("未找到摄像头设备") }
            }
            items(filteredCameras, key = { "cam-" + it.id }) { dev ->
                DeviceRow(
                    icon = Icons.Filled.Videocam,
                    device = dev,
                    selected = dev.id == selectedCamera,
                    multiSelect = false,
                    onClick = { session.selectCamera(dev.id) }
                )
            }

            // ---- 扬声器（输出） ----
            if (speakerDevices.isNotEmpty()) {
                item {
                    DeviceHeader(
                        icon = Icons.Filled.Speaker,
                        title = "音频输出（本机播放设备）",
                        right = {}
                    )
                }
                items(speakerDevices, key = { "spk-" + it.id }) { dev ->
                    DeviceRow(
                        icon = Icons.Filled.Speaker,
                        device = dev,
                        selected = false,
                        multiSelect = false,
                        onClick = {
                            com.selkies.client.platform.getPlatformServices().setSpeakerDevice(dev.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceHeader(
    icon: ImageVector,
    title: String,
    right: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    device: HardwareDevice,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (device.detail.isNotBlank()) {
                    Text(device.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 选中指示
            if (multiSelect) {
                Icon(
                    if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    if (selected) "已选择" else "未选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    if (selected) "使用中" else "未选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun EmptyDeviceRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
