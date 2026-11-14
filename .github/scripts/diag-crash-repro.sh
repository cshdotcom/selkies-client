#!/usr/bin/env bash
# 录屏闪退诊断脚本（由 crash-repro.yml 的 android-emulator-runner 调用）
# 说明：android-emulator-runner 会把 script 逐行用 sh -c 执行，
# 多行 if/fi 块会被拆散导致 Syntax error，所以全部逻辑放这个独立文件。
set +e

cd "$GITHUB_WORKSPACE" || { echo "cd GITHUB_WORKSPACE 失败"; pwd; exit 1; }
echo "=== cwd ==="
pwd

echo "=== ls release 目录 ==="
ls -la composeApp/build/outputs/apk/release/ 2>&1

echo "=== 找 APK ==="
APK="composeApp/build/outputs/apk/release/composeApp-release.apk"
if [ ! -f "$APK" ]; then
  echo "主路径缺失，find 兜底"
  APK=$(find "$GITHUB_WORKSPACE" -name "composeApp-release.apk" -type f 2>/dev/null | head -1)
fi
echo "APK=$APK"
if [ ! -f "$APK" ]; then
  echo "APK 缺失，列出 build/outputs 全树："
  find composeApp/build/outputs -type f 2>/dev/null | head -20
  exit 1
fi

echo "=== 安装 APK ==="
adb install -r "$APK" 2>&1 | tail -3

echo "=== 预授权 POST_NOTIFICATIONS（避免 MainActivity 弹窗阻塞）==="
adb shell pm grant com.selkies.client android.permission.POST_NOTIFICATIONS 2>&1 || true

echo "=== 清空 logcat ==="
adb logcat -c

echo "=== 启动 MainActivity ==="
adb shell am start -W -n com.selkies.client/.MainActivity 2>&1 | tail -5
sleep 8

echo "=== 进程状态（启动后）==="
adb shell ps -A | grep -i selkies || echo "WARN: selkies 进程未找到——可能启动即崩"

echo "=== 清空 logcat 再触发 RecordingService ==="
adb logcat -c

# shell uid 直接 start-foreground-service 会被拒（Requires permission not exported），
# 先 adb root（google_apis 镜像支持 userdebug root），uid 0 可启动未导出组件，
# 服务 onCreate/onStartCommand 仍在应用进程内跑，等效应用内 startForegroundService。
echo "=== adb root ==="
adb root 2>&1 | tail -1
adb wait-for-device
sleep 3
adb shell id -u 2>&1 | tail -1

echo "=== 触发 RecordingService（root）==="
adb shell am start-foreground-service -n com.selkies.client/.platform.RecordingService 2>&1 | tail -3
sleep 15

echo "=== 进程状态（触发后）==="
adb shell ps -A | grep -i selkies || echo "ALERT: selkies 进程消失——RecordingService 触发即崩"

echo "=== 抓取日志（写绝对路径）==="
adb logcat -b crash -d > "$GITHUB_WORKSPACE/crash-buffer.log"
adb logcat -b main -d > "$GITHUB_WORKSPACE/main-buffer.log"
adb shell dumpsys activity services com.selkies.client > "$GITHUB_WORKSPACE/service-state.txt"
adb shell dumpsys notification --noredact > "$GITHUB_WORKSPACE/notif-state.txt"
echo "=== 文件行数 ==="
wc -l "$GITHUB_WORKSPACE/crash-buffer.log" "$GITHUB_WORKSPACE/main-buffer.log" "$GITHUB_WORKSPACE/service-state.txt" "$GITHUB_WORKSPACE/notif-state.txt"

exit 0
