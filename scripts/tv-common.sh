#!/usr/bin/env bash
# Shared ADB operations. Source from scripts that use set -euo pipefail.
PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-127.0.0.1:15555}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.heytap.tv.launcher/com.oneplus.tv.launcher2.HomeLauncher}"
TVHOME=com.gathin.tvhome/.MainActivity

fail() { echo "错误：$*" >&2; exit 1; }
adb_tv() { "$ADB" -s "$SERIAL" "$@" </dev/null; }
tv_shell() { adb_tv shell "$@" | tr -d '\r'; }
require_tv() {
    [ "$(adb_tv get-state)" = device ] || fail "电视未连接，请先运行 connect-tv.sh"
    local identity
    identity=$(tv_shell getprop ro.serialno)
    [ -n "$identity" ] || fail "无法读取电视序列号，不能安全保存恢复记录"
    identity=$(printf '%s' "$identity" | shasum -a 256 | awk '{print $1}')
    STATE_DIR="${STATE_ROOT:-$PROJECT_ROOT/backup/state}/$identity"
    mkdir -p "$STATE_DIR"
}
resolve_home() {
    tv_shell cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1
}
start_home() {
    local output
    output=$(tv_shell am start -W -n "$1") || return 1
    printf '%s\n' "$output"
    printf '%s\n' "$output" | grep -q '^Status: ok$'
}
set_package_state() {
    local action=$1 package=$2 expected output
    case "$action" in
        enable) expected=enabled ;;
        disable-user) expected=disabled-user ;;
        *) return 1 ;;
    esac
    output=$(tv_shell pm "$action" --user 0 "$package") || return 1
    printf '%s\n' "$output"
    [[ "$output" == *"new state: $expected"* ]]
}
package_list() {
    [ -f "$1" ] || fail "找不到列表：$1"
    awk '{sub(/#.*/, ""); if (NF) print $1}' "$1"
}
validate_package() {
    [[ "$1" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$ ]] || fail "无效包名：$1"
}
install_apk() {
    local output remote status=0
    # A fresh path cannot accidentally install an APK left by an earlier failed upload.
    remote="/data/local/tmp/tvhome-$(date +%s)-$$-$RANDOM.apk"
    if ! adb_tv push "$1" "$remote" >/dev/null; then
        adb_tv shell rm -f "$remote" >/dev/null 2>&1 || true
        return 1
    fi
    output=$(tv_shell pm install -r "$remote") || status=$?
    adb_tv shell rm -f "$remote" >/dev/null 2>&1 || true
    printf '%s\n' "$output"
    [ "$status" -eq 0 ] && printf '%s\n' "$output" | grep -qx Success
}
