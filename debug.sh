#!/data/data/com.termux/files/usr/bin/bash
# DeepTerminal 一键调试 — 运行后手动打开 APP
echo "══════ DeepTerminal 崩溃诊断 ══════"
logcat -c 2>/dev/null

# 后台持续捕获
logcat -v time -s PTY:* AndroidRuntime:* DEBUG:* libc:* *:F 2>/dev/null > /data/data/com.termux/files/usr/tmp/debug.log &
LOGCAT_PID=$!

echo ""
echo "日志已在后台监听"
echo "现在请手动打开 DeepTerminal APP"
echo ""
echo "如果闪退，等待 3 秒后按 Enter 查看日志"
read

kill $LOGCAT_PID 2>/dev/null
echo ""
echo "══════ 崩溃日志 ══════"
cat /data/data/com.termux/files/usr/tmp/debug.log
echo ""
echo "══════ 完成 ══════"
echo "将以上输出发给我"
