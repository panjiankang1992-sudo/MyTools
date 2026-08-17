#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$TEST_DIR"
}
trap cleanup EXIT

python3 "$APP_DIR/tests/remote_media_smoke_server.py" > "$TEST_DIR/port" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s "$TEST_DIR/port" ]] && break
  sleep 0.02
done
if [[ ! -s "$TEST_DIR/port" ]]; then
  echo 'Remote media smoke test server did not start' >&2
  exit 1
fi
port="$(head -1 "$TEST_DIR/port")"
output="$(MYTOOLS_SMOKE_BASE_URL="http://127.0.0.1:${port}" MYTOOLS_SMOKE_ALLOW_HTTP=true \
  MYTOOLS_SMOKE_ACCOUNT=test MYTOOLS_SMOKE_PASSWORD=test MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID=7 \
  MYTOOLS_SMOKE_MEDIA_PATH=/media/test.mp4 "$APP_DIR/scripts/smoke-remote-media.sh")"
[[ "$output" == 'MyTools remote media ticket and Range smoke test passed' ]]

if MYTOOLS_SMOKE_BASE_URL="http://127.0.0.1:${port}" MYTOOLS_SMOKE_ALLOW_HTTP=true \
  MYTOOLS_SMOKE_ACCOUNT=test MYTOOLS_SMOKE_PASSWORD=test MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID=bad \
  MYTOOLS_SMOKE_MEDIA_PATH=/media/test.mp4 "$APP_DIR/scripts/smoke-remote-media.sh" >/dev/null 2>&1; then
  echo 'Invalid account ID was accepted' >&2
  exit 1
fi

grep -Fq "header 'Range: bytes=0-0'" "$APP_DIR/scripts/smoke-remote-media.sh"
grep -Fq "chmod 600" "$APP_DIR/scripts/run-deployment-acceptance.sh"
echo 'Remote media deployment smoke tests passed'
