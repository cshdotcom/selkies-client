# Selkies 原生远程桌面客户端 v2.5.0

原生 Android + 桌面（x86_64 / ARM64）双端客户端，100% 原生实现（**非 WebView 套壳**），直连 Selkies v2 WebSocket 流协议。深色/浅色主题可切换，两端页面与功能完全一致。

> **v2.5.0 更新（统计/音量/截图录屏/手柄/剪贴板 全量增强）**：
> 1. **统计面板全面对齐官方网页端**：连接详情（地址/时长/模式/角色）+ **交互式趋势图**（FPS 双序列/带宽/RTT/解码/上屏，按住图表出现十字线查看历史值，官方 stream-graph 同款）+ **官方 12 项管线 tile**（编码/管线/发送/解码/上屏/音频缓冲/码率/峰值/已收/丢帧/未上屏帧/关键帧请求）+ **上行/下行连接统计**（麦克风/摄像头上行带宽图、累计字节、下行全量带宽）+ 客户端解码器统计 + 主机资源条形图。
> 2. **应用内音量独立调节 + 静音**：工具栏「音量」弹窗滑条（感知曲线），只调本应用播放音量，**不依赖系统音量**；桌面端为 PCM 增益、Android 为 AudioTrack 流音量。
> 3. **截图 / 录屏（WS 流直采，零系统权限）**：直接从 WebSocket 已编码流录制——H.264/H.265 零转码直通 MP4/MKV（画质无损、CPU≈0），JPEG 模式自动转码，可选含远程声音（Opus 音轨）；**Android 前台服务保活，退后台照样录**；退出会话/退出应用自动保存；杀进程前经 onDestroy 兜底保存。截图走解码 Surface 直取（PixelCopy/Skia），同样无需系统截图权限。
> 4. **自动分类存储 + 时间戳命名 + 保存引导**：截图 → Pictures/Selkies、录屏 → Movies/Selkies、下载 → Download/Selkies（Android 29+ MediaStore 免权限）；文件名自动带时间戳不覆盖；保存/下载完成后弹窗引导「打开 / 所在文件夹 / 复制路径」；媒体库面板可浏览/删除/分享全部已保存文件；存储位置可在设置中自定义。
> 5. **手柄功能完整**：桌面端**新增 LWJGL GLFW 全平台手柄**（Windows XInput/macOS/Linux，标准布局映射 + 死区 + 4ms 轮询）；Android 手柄加固（4 槽位上限对齐服务器、js,h 心跳保活、**rumble 振动直达人柄马达**）；手柄面板实时可视化（摇杆 XY 圆盘/扳机条/17 键高亮）+ 一键振动测试。
> 6. **剪贴板双向修复**：根因一——剪贴板面板会顶掉会话层监听器（打开过面板后自动同步即失效），改为多监听器共存；根因二——Android 10+ 后台禁读写剪贴板，新增焦点追赶机制（回前台自动补写挂起的远程剪贴板 + 补推后台期间复制的本地内容）。
> 7. **麦克风闪退修复**：防重入状态机（权限回调/双击/服务器并发触发不再开双线程）、Throwable 级捕获（native 层 Error 也不闪退）、错误回调回主线程、stop 等待线程退出。
> 8. **FPS 悬浮条**（设置开关）：视频区左上实时显示 FPS/带宽/延迟（官方 StreamStrip 等效）；录屏中红点+时长指示器。
>

> **v2.3.0 更新（真机反馈全量修复：点击/自适应/光标/触屏/键盘/文件/分辨率/黑屏）**：
> 1. **点击与自适应屏幕（根因修复）**：视频 SurfaceView 拉伸变形导致触摸坐标与画面错位——现为 aspectRatio 等比容器（画面永不变形，坐标严格一致）；帧头宽高实时同步流尺寸，stream_resolution 缺失也能正确映射。
> 2. **零延迟触屏（不灵敏修复）**：弃用系统手势双击等待（旧版每次点击延迟 ~300ms），重写为 Initial 通道直发——手指按下立即发送 m 事件；长按/双指轻点=右键，双指滚动/捏合缩放；触摸板模式轻点=左键、双击后按住拖动。
> 3. **本地光标**：解析服务器 `cursor,{curdata}` 形状推送（PNG+热点，按 handle 去重）+ 本地位置回显，屏幕上永远可见真实远程光标。
> 4. **重开不再黑屏卡死**：回前台 SurfaceView 重建即强制请求关键帧 + 会话级帧停顿看门狗（3s 无帧自动 REQUEST_KEYFRAME）。
> 5. **文件下载 401/400 修复**：下载流携带认证头（401 根因）；路径全程保持百分号编码（中文/空格文件名 400 根因）；目录判定用 href 尾斜杠（秒开，无 301 探测）；面包屑路径显示；下载完成可打开/分享/另存为。
> 6. **键盘双模式**：默认「分类模式」（常用/功能/导航/小键盘/媒体/扩展/文本，指尖尺寸大键自动适配宽度）；一键切换「完整模式」（151 键 23 单位真实布局，可横向滚动）；双指捏合/± 按钮缩放并持久化；页签可横向滑动。
> 7. **每连接分辨率**：服务器条目可独立设置 自适应屏幕/1920×1080/1280×720/2560×1440/自定义；会话内统计面板还可「适应本机屏幕」即时切换。
> 8. **系统输入法重设计**：选它即直接唤起本机输入法，文字先显示在输入区，点「发送」一次性提交到远程；附控制键（回车/Tab/Esc/方向/复制粘贴）直发行。
> 9. **工具栏可滑动**；远程打印改为主线程调起系统打印界面（旧版无声失败）；麦克风/摄像头错误即时 toast；编码器详情（backend/encoder）显示。
>
> **v2.0.1 修复说明（历史）**：v2.0.0 存在三个叠加的连接层致命缺陷（WebSockets 插件未安装 / URL 遗漏主机名 / https:// 误用作 ws://），全部修复并真实服务器端到端验证。


> **v2.2.0 更新（世界最完整键盘）**：
> 1. **151 键完整悬浮键盘**——主键盘页 103 键（功能行/完整主区/导航区/方向键/数字小键盘，NumLk 真实切换、+/Enter 真实 2u 高键）+ 媒体页 24 键（音量/播放/主页/邮件/搜索/计算器/电源/唤醒/弹出/F13-F24）+ 扩展页 24 键（SysRq/Break/Compose/日韩键/小键盘扩展）；23 单位统一网格列严格对齐；可拖动、双指捏合缩放、折叠悬浮球、长按重复；修饰键单次粘滞+双击锁定。
> 2. **输入方式可选**——远程控制时可选：悬浮完整键盘 / 系统输入法（支持中文）/ 快捷按钮栏（CTL/ALT/WIN 粘滞 + TAB/ESC/ENTER 单发 + 固定快捷键一键直发）；选择被记住，长按"键盘"重新选择。
> 3. **快捷键创建用完整键盘选择**（修饰键粘滞+主键点选成组）；任意快捷键可固定到快捷按钮栏。
> 4. **官方前端 API 全量校对补齐**——LOST_FRAME 丢帧上报、远程应用启动面板（server_apps + cmd,）、视频高级设置（jpeg_quality/video_fullcolor/use_cpu/force_aligned_resolution/video_streaming_mode/paint-over 系列）。
> 5. 文本合成条：与输入法等效（可调系统 IME 输中文，co,end 提交）；对官方 softButtons 的完整扩展。
> 6. 全部回归通过：协议 E2E / 连接回归（RST/正常关/守卫）/ 视频解码 0 错 / Linux GUI 实测（151 键布局像素级校验、修饰键粘滞高亮、输入方式选择器、被顶替友好提示与重试）。

---

## 交付清单

| 文件 | 说明 |
|------|------|
| `Selkies-Android-v2.3.0.apk` | Android 签名正式包（minSdk 26 / Android 8.0+，13MB） |
| `Selkies-Android-v2.3.0-debug.apk` | Android 调试包（可用 logcat 排查） |
| `Selkies-Desktop-windows-x64-v2.3.0.zip` | Windows x64 便携完整包（自带 Java 21，双击 `launch.bat` 即用；Win-ARM64 可经 x64 模拟运行） |
| `Selkies-Desktop-linux-x86_64-v2.3.0.zip` | Linux x86_64 便携完整包（自带 Java 21，运行 `launch.sh`） |
| `Selkies-Desktop-linux-arm64-v2.3.0.zip` | Linux ARM64 便携完整包（树莓派/ARM 主机可用） |
| `Selkies-Desktop-all.jar` | 通用 JAR（任意装有 Java 17+ 的系统，含全部平台原生库） |
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
1. 解压 `Selkies-Desktop-windows-x64-v2.3.0.zip`；
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
