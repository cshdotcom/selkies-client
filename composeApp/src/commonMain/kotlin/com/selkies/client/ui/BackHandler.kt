package com.selkies.client.ui

import androidx.compose.runtime.Composable

/**
 * 平台返回键处理（expect/actual）：
 * - Android：接系统返回手势/返回键
 * - Desktop：无实体返回键，空实现（界面仍提供返回按钮）
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
