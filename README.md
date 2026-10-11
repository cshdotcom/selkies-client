# Selkies 原生远程桌面客户端 v2.0.1

原生 Android + 桌面（x86_64 / ARM64）双端客户端，100% 原生实现（**非 WebView 套壳**），直连 Selkies v2 WebSocket 流协议。深色/浅色主题可切换，两端页面与功能完全一致。

> **v2.0.1 修复说明（重要）**：v2.0.0 存在三个叠加的连接层致命缺陷，导致完全无法连接。本版已全部修复，并用**真实服务器完成无头端到端验证**（连接 → 认证 → MODE → 分辨率协商 → START_VIDEO → VIDEO_STARTED → H.264 视频帧接收 → 剪贴板下行）：
> 1. Ktor `WebSockets` 插件从未安装到 HttpClient —— `webSocketSession` 直接抛异常；
> 2. WebSocket URL 拼接遗漏主机名（生成 `https:///api/...`）—— 必然 UnknownHostException；
> 3. `webSocketSession` 使用 `https://` 而非 `wss://` —— 引擎按普通 GET 处理，无 101 升级。
>
> 另补齐官方网页客户端必带的 `tabId` 标识（缺失会导致服务器不为本客户端启动视频捕获）。

---

## 交付清单

| 文件 | 说明 |
|------|------|
| `Selkies-Android-v2.0.1.apk` | Android 签名正式包（minSdk 26 / Android 8.0+，13MB） |
| `Selkies-Android-v2.0.1-debug.apk` | Android 调试包（可用 logcat 排查） |
| `Selkies-Desktop-windows-x64.zip` | Windows x64 便携完整包（自带 Java 21，双击 `launch.bat` 即用；Win-ARM64 可经 x64 模拟运行） |
| `Selkies-Desktop-linux-x86_64.zip` | Linux x86_64 便携完整包（自带 Java 21，运行 `launch.sh`） |
| `Selkies-Desktop-linux-arm64.zip` | Linux ARM64 便携完整包（树莓派/ARM 主机可用） |
| `Selkies-Desktop-all.jar` | 通用 JAR（任意装有 Java 17+ 的系统，含全部平台原生库，235MB） |
| `Selkies-Client-source.zip` | 完整源码（含 Gradle 工程与签名密钥） |

### 签名密钥（务必自行保管）
```
文件:   keystore/release.keystore
别名:   selkies
库密码: selkies2024
密钥密码: selkies2024
```
> 发布前建议更换为你自己的密钥；密钥丢失将无法对同一应用身份发升级包。

---

## 快速开始

### Android
1. 安装 APK（允许"未知来源"）；
2. 添加服务器：`https://你的域名` 或 `http://IP:端口`（内网自签证书请打开"允许不受信任的 TLS 证书"）；
3. 填写 Basic Auth 用户名/密码（或切换为令牌认证），点击"测试连接"验证；
4. 保存并点击服务器卡片即进入远程桌面。

### 桌面（以 Windows 为例）
1. 解压 `Selkies-Desktop-windows-x64.zip`；
2. 双击 `Selkies-Desktop/launch.bat`（Linux 为 `launch.sh`）；
3. 其余与 Android 完全一致（页面相同、功能不减）。

---

## 功能总览（与官方网页前端逐项对齐）

### 连接与流
- 双路径探测：`/api/websockets` → 旧版回退 `/api/ws`
- Basic Auth / Bearer 令牌认证；401/403/409/Cloudflare 拦截均给出**具体**中文原因（彻底告别"未预料的错误"）
- HTTP 301/302 重定向自动跟随（保留认证头），模拟网页 UA/Origin 最大化反代兼容
- 可选"允许不受信任证书"（自签 TLS）
- 断线自动重连（指数退避），START_VIDEO 看门狗（3 秒无帧自动重发，3 次后请求关键帧），卡顿自动请求关键帧
- H.264 视频硬解（Android MediaCodec / 桌面 FFmpeg），JPEG 条带回退渲染
- Opus 音频下行（48kHz），音量调节
- `CLIENT_FRAME_ACK` 背压、`_stats` 实时统计（帧率/延迟/码率/分辨率）
- SETTINGS 全量对齐官方字段（displayId / tabId / displayScale / useCssScaling / audioRedundancy / keyboardLayout…）

### 输入
- **输入法自然化**：进入会话自动唤起系统输入法；隐藏输入宿主逐键实时发送（`co,end`）；支持回车/退格；无"发送"按钮；桌面端逐键文本框
- **物理键盘直通**：Android `dispatchKeyEvent` 键码映射 X11 keysym；桌面 AWT 分发器全局直通（可开关）
- **触屏手势**：单击=左键、双击=双击、长按=右键、拖动=左键拖动、双指滚动（50px/格）、双指捏合=Ctrl+滚轮缩放
- **触屏鼠标模式**：虚拟指针相对移动，适合精细操作
- **桌面鼠标完整支持**：左/中/右键、拖动、滚轮（含横向）
- **特殊键面板**：Ctrl/Alt/Shift/Super/AltGr 粘滞修饰键 + Ctrl→，F1-F12、Esc、Tab、PrtSc、ScrLk、Pause、Menu、SysRq、Home/End/PgUp/PgDn/Insert/Delete、方向键、小键盘
- **快捷键系统**：内置 57 个常用快捷键（系统/浏览器/文本/终端四类，含锁定、终端、关闭窗口、截图、Ctrl+C/R/D/Z 等）+ 自定义快捷键 + 物理键录制
- 手柄协议已实现（js,c/b/a/d/h 消息层）

### 剪贴板（双向同步 + 设置）
- 本地复制 → 自动发送远程（可关）；远程剪贴板 → 自动应用本地（可关）
- 分块大文本/二进制传输（cws/cwd/cwe + cbs/cbd/cbe），图片 PNG 支持
- 剪贴板面板：手动获取/发送/复制到本机/直接打字
- 机密剪贴板（clipboard_secret）不落地

### 文件
- 远程目录浏览（/api/files 列表解析、面包屑返回）
- 多文件上传（8MB 分片、断点续传头、进度条、失败重试提示）
- 流式下载（进度显示，保存到应用下载目录）

### 远程硬件直通
- **麦克风上行**：Android AudioRecord 24kHz + MediaCodec Opus；桌面 TargetDataLine + FFmpeg Opus（0x02 帧）
- **摄像头上行**：Android CameraX + MediaCodec H.264（0x06 帧）；桌面 FFmpeg 采集 MJPEG；响应服务器 `CAPTURE_DEMAND` 按需开启，`WEBCAM_KEYFRAME` 关键帧请求
- **远程打印**：服务器推送 print_document → 自动拉取 PDF → Android 系统打印界面（PdfRenderer 渲染）/ 桌面打印对话框

### 界面
- 深色 / 浅色 / 跟随系统三种主题（Material You 配色），设置即时生效
- 服务器列表多选批量操作（删除/复制/导出/导入 JSON）
- 全屏沉浸模式、工具栏自动隐藏与唤出
- 会话统计面板 + 手动分辨率设置（1280x720/1920x1080/2560x1440 快捷档）
- 仅查看（View-only）模式

---

## 从源码构建

```bash
# 前置：JDK 21（含 javac）、Android SDK (platform 34 + build-tools 34)
unzip Selkies-Client-source.zip -d selkies-client && cd selkies-client
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Android APK（已配置自动签名，密钥在 keystore/ 下）
./gradlew :composeApp:assembleRelease

# 桌面通用 JAR（含全平台原生库）
./gradlew :composeApp:desktopFatJar

# 无头协议端到端验证（连真实服务器，CI 可用）
./gradlew :composeApp:probeProtocol \
  -PprobeUrl=https://你的服务器 -PprobeUser=用户 -PprobePass=密码
# 成功标准：✅ 协议端到端验证通过（含 H264 帧计数）
```

技术栈：Kotlin 2.0.21 · Compose Multiplatform 1.7.1（Android + JVM 桌面共享同一 UI）· Ktor 2.3(OkHttp) · JavaCV/FFmpeg 7.1 · MediaCodec/CameraX · kotlinx.serialization

## 协议参考（审计自官方源码）

连接层、SETTINGS、`r,WxH`、`START_VIDEO`、二进制帧（0x01 音频 / 0x03 JPEG 条带 / 0x04 视频[高低半字节=编解码与帧型] / 0x05 gzip / 0x02 麦克风 / 0x06 摄像头）、输入动词（kd/ku/kh/kr、m/m2 含滚轮位、co,end、js,*、sf/sfe）、剪贴板家族、`/api/upload` 分片头、`/api/files`、`/api/print` 等全部细节已在源码 `core/` 目录内注释化实现。

## 常见问题

- **连接失败"认证失败(401)"**：用户名/密码错误；或服务器处于令牌模式——改用令牌认证。
- **"未找到 WebSocket 端点"**：确认服务为 Selkies v2 WebSocket 模式（若服务器在 WebRTC 模式会明确提示 409）。
- **自签证书报 TLS 错误**：编辑服务器开启"允许不受信任的 TLS 证书"。
- **Android 麦克风不可用**：需要 Android 10+（Opus 编码器），且授权麦克风权限。
- **Windows ARM64**：使用 windows-x64 包，系统自动 x64 模拟；原生 ARM64 加速支持 Linux 包。
