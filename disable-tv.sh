#!/usr/bin/env bash
# Disable only enabled packages; journal them before changing device state.
set -euo pipefail
source "$(dirname "$0")/scripts/tv-common.sh"
LIST="${1:?用法: $0 <debloat.txt>}"
PACKAGES=$(package_list "$LIST")
for pkg in $PACKAGES; do validate_package "$pkg"; done
require_tv
JOURNAL="$STATE_DIR/debloat.txt"
touch "$JOURNAL"
tv_shell pm list packages -d > "$STATE_DIR/disabled-before.txt"
ENABLED=$(tv_shell pm list packages -e)
for pkg in $PACKAGES; do
    if ! printf '%s\n' "$ENABLED" | grep -Fqx "package:$pkg"; then
        echo "跳过未安装或已禁用的包：$pkg"
        continue
    fi
    # Write first: an interrupted ADB response must still leave a recovery entry.
    grep -qxF "$pkg" "$JOURNAL" || printf '%s\n' "$pkg" >> "$JOURNAL"
    set_package_state disable-user "$pkg" || fail "禁用失败：${pkg}；恢复记录：$JOURNAL"
done
echo "完成。恢复命令：$PROJECT_ROOT/restore.sh"
