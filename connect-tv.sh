#!/usr/bin/env bash
# 连接电视并启动本机端口转发。
#
# 为什么需要它：新版 macOS 的「本地网络」隐私设置会拦截 Homebrew 版 adb，直连电视时报
# "No route to host"（其实端口是通的）。这里用系统自带的 nc 把 127.0.0.1:15555 转发到
# 电视的 5555，adb 只连本机回环地址，不受该限制。
#
# 需要 Python 3.9+。转发断开后可接受新的 ADB 连接；只管理本脚本的转发。
# 用法：
#   ./connect-tv.sh                    # 通过 mDNS 自动找电视
#   TV_IP=<电视IP> ./connect-tv.sh
#   ./connect-tv.sh --stop
set -euo pipefail
TV_IP="${TV_IP:-}"
TV_PORT="${TV_PORT:-5555}"
LOCAL_PORT="${LOCAL_PORT:-15555}"
PROJECT_ROOT=$(cd "$(dirname "$0")" && pwd)
ADB="${ADB:-adb}"
PYTHON="${PYTHON:-python3}"
# A short, per-user path keeps the Unix control socket below macOS's path limit.
STATEDIR="${TV_PROXY_STATE:-/tmp/tv-optimize-$UID-$LOCAL_PORT}"
if [ "${1:-}" = --stop ]; then
  exec "$PYTHON" "$PROJECT_ROOT/scripts/tv-proxy.py" stop --state "$STATEDIR"
fi
mkdir -p "$STATEDIR"

find_host() {
  local inst host
  inst=$(perl -e 'alarm 6; exec "dns-sd","-B","_adb._tcp","local."' 2>/dev/null \
         | awk '$2=="Add" {print $NF; exit}' || true)
  [ -n "$inst" ] || return 1
  host=$(perl -e 'alarm 6; exec "dns-sd","-L",$ARGV[0],"_adb._tcp","local."' "$inst" 2>/dev/null \
         | sed -n 's/.*can be reached at \([^ ]*\):\([0-9]*\).*/\1/p' | head -1 || true)
  [ -n "$host" ] && printf '%s\n' "${host%.}"
}

if [ -z "$TV_IP" ]; then
  HOST=$(find_host || true)
  if [ -n "${HOST:-}" ]; then
    TV_IP=$(perl -e 'alarm 5; exec "dns-sd","-G","v4",$ARGV[0]' "$HOST" 2>/dev/null \
            | awk '/ Add / {print $(NF-1); exit}' || true)
  fi
fi
[ -n "$TV_IP" ] || { echo "找不到电视，请用 TV_IP=<电视IP> $0"; exit 1; }
echo "电视: $TV_IP:$TV_PORT  ->  127.0.0.1:$LOCAL_PORT"

"$PYTHON" "$PROJECT_ROOT/scripts/tv-proxy.py" start \
  --host "$TV_IP" --port "$TV_PORT" --local-port "$LOCAL_PORT" --state "$STATEDIR"
"$PYTHON" "$PROJECT_ROOT/scripts/tv-proxy.py" verify \
  --adb "$ADB" --local-port "$LOCAL_PORT" --state "$STATEDIR"
