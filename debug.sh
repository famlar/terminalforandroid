#!/data/data/com.termux/files/usr/bin/bash
# DeepTerminal 自动调试脚本
# 用法: bash debug.sh

APP="com.sshterminal"
echo "╔══════════════════════════════════╗"
echo "║  DeepTerminal 自动调试工具      ║"
echo "╚══════════════════════════════════╝"
echo ""

# --- 检查无线调试 ---
WIFI_ADB=$(getprop service.adb.tcp.port 2>/dev/null)
if [ -n "$WIFI_ADB" ]; then
    echo "[*] 无线调试端口: $WIFI_ADB"
    adb connect 127.0.0.1:$WIFI_ADB 2>/dev/null
    echo "[*] ADB 已连接"
else
    echo "[!] 无线调试未开启。使用直接 logcat。"
    echo "    开启方法: 开发者选项 → 无线调试 → 开启"
    echo "    然后: adb connect 127.0.0.1:端口号"
fi
echo ""

# --- 清理旧日志 ---
logcat -c 2>/dev/null
echo "[*] 日志已清空"

# --- 如果有 ADB，尝试启动 APP ---
if adb devices 2>/dev/null | grep -q "device$"; then
    echo "[*] 正在启动 $APP ..."
    adb shell am start -n $APP/.MainActivity 2>/dev/null
    adb shell am force-stop $APP 2>/dev/null
    sleep 1
    adb shell am start -n $APP/.MainActivity 2>/dev/null
    echo "[*] 如果 APP 未启动，请手动打开"
else
    echo "[*] 请手动打开 DeepTerminal APP"
fi

echo ""
echo "══════ 实时日志监控 (Ctrl+C 退出) ══════"
echo ""

# --- 监控过滤 ---
# PTY:*  = JNI 日志
# AndroidRuntime:* = 崩溃
# termux-pty-wrapper:* = 可能的相关标签
logcat -v time -s PTY:* AndroidRuntime:* DEBUG:* 2>/dev/null | while read line; do
    # 高亮崩溃
    if echo "$line" | grep -qi "FATAL\|SIGSEGV\|SIGABRT\|crash\|Process.*died"; then
        echo "💥💥💥 崩溃！💥💥💥"
        echo "$line"
        echo "═══════════════════════════════════"
        echo "崩溃位置已捕获，按 Ctrl+C 退出"
        echo "═══════════════════════════════════"
    fi
    
    # PTY 步骤
    if echo "$line" | grep -q "PTY"; then
        echo "🔧 $line"
    fi
    
    # 异常
    if echo "$line" | grep -qi "exception\|error"; then
        echo "⚠️  $line"
    fi
done
