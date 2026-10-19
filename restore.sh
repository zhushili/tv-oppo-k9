#!/usr/bin/env bash
# Default: undo recorded changes for this device. An explicit list is the legacy fallback.
set -euo pipefail
source "$(dirname "$0")/scripts/tv-common.sh"
require_tv
LIST="${1:-$STATE_DIR/debloat.txt}"
if [ -f "$LIST" ]; then
    PACKAGES=$(package_list "$LIST")
    for pkg in $PACKAGES; do validate_package "$pkg"; done
    for pkg in $PACKAGES; do
        set_package_state enable "$pkg" || fail "恢复失败：${pkg}；保留记录供重试"
    done
    # Do not discard the journal until every package is restored.
    if [ "$LIST" = "$STATE_DIR/debloat.txt" ]; then : > "$LIST"; fi
elif [ -n "${1:-}" ]; then
    fail "找不到列表：$LIST"
fi

# The original HOME is recorded before installing; old deployments can name a list explicitly.
if [ -f "$STATE_DIR/home-before.txt" ]; then
    HOME_COMPONENT=$(cat "$STATE_DIR/home-before.txt")
elif [ -n "${1:-}" ]; then
    HOME_COMPONENT=$STOCK_LAUNCHER
else
    echo "没有桌面切换记录；旧版部署可运行 ./restore.sh debloat.txt"
    exit 0
fi
[[ "$HOME_COMPONENT" == */* ]] || fail "无效 HOME 记录"
set_package_state enable "${HOME_COMPONENT%%/*}" || fail "无法启用原桌面"
tv_shell cmd package set-home-activity --user 0 "$HOME_COMPONENT"
[ "$(resolve_home)" = "$HOME_COMPONENT" ] || fail "原桌面未恢复为默认，记录已保留"
start_home "$HOME_COMPONENT" || fail "原桌面启动失败，记录已保留"
rm -f "$STATE_DIR/home-before.txt"
echo "原桌面已恢复。"
