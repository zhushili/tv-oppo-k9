#!/usr/bin/env bash
# 构建、安装并运行「安装限制修复」小工具。
#
# 背景：OPPO 电视的框架层会拦截任何声明了 android.intent.category.HOME 的第三方 APK：
#   INSTALL_FAILED_DUPLICATE_PERMISSION: App installation is banned, because of launcher categories
# 实际判断在 TvApiService 里：
#   1. 读取系统属性 service.tvapi.enable_launcher_install，为 "1" 时直接放行；
#   2. 否则只允许 assets/LauncherWhiteList 里的 OPPO 桌面。
# TvApiService 只允许「系统 uid」或特定进程名调用，本工具把自己的进程名伪装成
# com.heytap.tv.remote，再通过 TvApiManager 的 ModuleDevice 命令（cmdType=30）写入该属性。
set -euo pipefail
source "$(dirname "$0")/../scripts/tv-common.sh"
cd "$PROJECT_ROOT/fixapp"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

[ -x "$BT/aapt2" ] || { echo "找不到 Android build-tools: $BT"; exit 1; }

rm -rf build && mkdir -p build/classes build/dex
"$JAVA_HOME/bin/javac" -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$SDK/platforms/android-34/android.jar" \
  -d build/classes src/com/gathin/tvfix/FixActivity.java

"$BT/d8" --lib "$SDK/platforms/android-34/android.jar" --min-api 26 \
  --output build/dex build/classes/com/gathin/tvfix/FixActivity.class

"$BT/aapt2" link -o build/fix-unsigned.apk --manifest AndroidManifest.xml \
  -I "$SDK/platforms/android-34/android.jar" --min-sdk-version 26 --target-sdk-version 27
zip -j build/fix-unsigned.apk build/dex/classes.dex >/dev/null
"$BT/zipalign" -f 4 build/fix-unsigned.apk build/fix-aligned.apk
KEYSTORE="${DEBUG_KEYSTORE:-$PROJECT_ROOT/launcher/app/debug.keystore}"
[ -f "$KEYSTORE" ] || { echo "缺少 debug keystore：$KEYSTORE"; exit 1; }
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out fix.apk build/fix-aligned.apk

echo "== 安装修复工具"
install_apk fix.apk || fail "辅助工具安装失败"

echo "== 解除桌面安装限制"
"$ADB" -s "$SERIAL" shell am start -n com.gathin.tvfix/.FixActivity </dev/null >/dev/null
sleep 2
PROP=$("$ADB" -s "$SERIAL" shell getprop service.tvapi.enable_launcher_install </dev/null | tr -d '\r')
echo "service.tvapi.enable_launcher_install=$PROP"
[ "$PROP" = "1" ] || { echo "设置失败，看看 logcat：adb logcat -s TVFix"; exit 1; }
