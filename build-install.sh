#!/usr/bin/env bash
# Build first, then install and verify HOME; roll back if switching fails.
set -euo pipefail
source "$(dirname "$0")/scripts/tv-common.sh"
cd "$PROJECT_ROOT"
export ADB SERIAL
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export DEBUG_KEYSTORE="$PROJECT_ROOT/launcher/app/debug.keystore"
[ -f launcher/local.properties ] || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > launcher/local.properties
if [ ! -f "$DEBUG_KEYSTORE" ]; then
    if [ -f "$HOME/.android/debug.keystore" ]; then
        cp "$HOME/.android/debug.keystore" "$DEBUG_KEYSTORE"
    else
        "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$DEBUG_KEYSTORE" \
            -alias androiddebugkey -storepass android -keypass android \
            -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    fi
fi
(cd launcher && ./gradlew assembleRelease --console=plain -q)
APK=launcher/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || fail "找不到 $APK"
mkdir -p dist backup
cp "$APK" dist/tvhome.apk
require_tv
OLD_HOME=$(resolve_home)
case "$OLD_HOME" in
    "$TVHOME"|"$STOCK_LAUNCHER") ;;
    *) fail "当前 HOME 为 ${OLD_HOME}；请用 STOCK_LAUNCHER 指定真实原桌面组件" ;;
esac
STOCK_PKG=${STOCK_LAUNCHER%%/*}
STOCK_WAS_ENABLED=$(tv_shell pm list packages -e | grep -Fxc "package:$STOCK_PKG" || true)
if [ "$OLD_HOME" != "$TVHOME" ] && [ ! -f "$STATE_DIR/home-before.txt" ]; then
    printf '%s\n' "$OLD_HOME" > "$STATE_DIR/home-before.txt"
fi

if [ "$(tv_shell getprop service.tvapi.enable_launcher_install)" != 1 ]; then
    bash fixapp/build.sh
fi
install_apk "$APK" || fail "APK 安装失败，未切换桌面"
tv_shell cmd package compile -m speed -f com.gathin.tvhome || true
start_home "$TVHOME" || fail "新桌面无法启动，未禁用原桌面"

# From here any error restores the pre-switch HOME and stock package state.
rollback() {
    local status=$?
    trap - EXIT
    if [ "$status" -ne 0 ]; then
        echo "切换失败，正在恢复之前的桌面…" >&2
        if [ "$STOCK_WAS_ENABLED" = 1 ]; then set_package_state enable "$STOCK_PKG" || true; fi
        tv_shell cmd package set-home-activity --user 0 "$OLD_HOME" || true
        start_home "$OLD_HOME" || true
    fi
    exit "$status"
}
trap rollback EXIT
set_package_state disable-user "$STOCK_PKG" || fail "禁用原桌面失败"
tv_shell cmd package set-home-activity --user 0 "$TVHOME"
[ "$(resolve_home)" = "$TVHOME" ] || fail "HOME 校验失败"
start_home "$TVHOME" || fail "切换后桌面启动失败"
trap - EXIT
adb_tv uninstall com.gathin.tvfix >/dev/null 2>&1 || true
# Screenshot is diagnostic, not a reason to undo a verified installation.
if adb_tv shell screencap -p /sdcard/tvhome.png && adb_tv pull /sdcard/tvhome.png backup/launcher-after.png; then
    echo "截图：backup/launcher-after.png"
fi
echo "安装成功，HOME 已验证：$TVHOME"
