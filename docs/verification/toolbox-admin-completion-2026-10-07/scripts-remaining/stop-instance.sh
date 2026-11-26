#!/usr/bin/env bash
# 只停止**本任务自己的**验证实例：先核对端口占用者的 TOOLBOX_DB_NAME，
# 名字不符就拒绝停止（防止误杀其它验收线在同一端口范围跑的实例）。
# 用法：bash stop-instance.sh <PORT> <EXPECTED_DB_NAME>
set -uo pipefail

PORT="${1:?port required}"
EXPECT="${2:?expected db name required}"
PID=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)
if [ -z "$PID" ]; then
  echo "port $PORT 当前空闲，无需停止"
  exit 0
fi
DB=$(ps eww -p "$PID" -o command= 2>/dev/null | tr ' ' '\n' | grep '^TOOLBOX_DB_NAME=' | head -1 | cut -d= -f2)
echo "port $PORT -> PID $PID, TOOLBOX_DB_NAME=${DB:-<未知>}"
case "${DB:-}" in
  "$EXPECT") kill "$PID"; echo "已停止本任务实例 PID $PID" ;;
  *) echo "拒绝停止：期望 TOOLBOX_DB_NAME=$EXPECT，实际=${DB:-<未知>}"; exit 1 ;;
esac
