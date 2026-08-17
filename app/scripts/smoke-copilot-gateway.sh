#!/usr/bin/env bash
set -euo pipefail

required=(MYTOOLS_SMOKE_BASE_URL MYTOOLS_SMOKE_ACCOUNT MYTOOLS_SMOKE_PASSWORD)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 2
  fi
done
if ! command -v curl >/dev/null || ! command -v jq >/dev/null || ! command -v python3 >/dev/null; then
  echo 'curl, jq and python3 are required' >&2
  exit 2
fi

base_url="${MYTOOLS_SMOKE_BASE_URL%/}"
python3 - "$base_url" "${MYTOOLS_SMOKE_ALLOW_HTTP:-false}" <<'PY'
import sys
from urllib.parse import urlsplit
value, allow_http = sys.argv[1:]
parsed = urlsplit(value)
allowed = parsed.scheme == "https" or (parsed.scheme == "http" and allow_http == "true")
if not allowed or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
    raise SystemExit("Smoke target must be a credential-free HTTPS base URL; HTTP is local-development only")
PY

umask 077
work_dir="$(mktemp -d)"
access_token=''
cleanup() {
  if [[ -n "$access_token" ]]; then
    curl --silent --output /dev/null --max-time 10 --request POST \
      --header "Authorization: Bearer ${access_token}" "${base_url}/api/auth/logout" || true
  fi
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

request_json() {
  local method="$1" path="$2" output="$3" authorization="${4:-}" payload="${5:-}"
  local args=(--silent --show-error --output "$output" --write-out '%{http_code}' --max-time 30
    --request "$method" --header 'Accept: application/json')
  [[ -n "$authorization" ]] && args+=(--header "Authorization: Bearer ${authorization}")
  [[ -n "$payload" ]] && args+=(--header 'Content-Type: application/json' --data "$payload")
  curl "${args[@]}" "${base_url}${path}"
}

login_payload="$(jq -n --arg account "$MYTOOLS_SMOKE_ACCOUNT" --arg password "$MYTOOLS_SMOKE_PASSWORD" \
  '{account:$account,password:$password,deviceName:"HarmonyOS Copilot smoke test"}')"
login_status="$(request_json POST '/api/auth/login' "$work_dir/login.json" '' "$login_payload")"
if [[ ! "$login_status" =~ ^2 ]]; then
  echo "Copilot smoke login failed with HTTP ${login_status}" >&2
  exit 1
fi
access_token="$(jq -er '.data.accessToken' "$work_dir/login.json")"

unauthorized_status="$(request_json GET '/api/app/v1/copilot/config' "$work_dir/unauthorized.json")"
if [[ "$unauthorized_status" != '401' ]]; then
  echo "Unauthenticated Copilot config returned HTTP ${unauthorized_status}, expected 401" >&2
  exit 1
fi

config_status="$(request_json GET '/api/app/v1/copilot/config' "$work_dir/config.json" "$access_token")"
if [[ ! "$config_status" =~ ^2 ]]; then
  echo "Copilot config failed with HTTP ${config_status}" >&2
  exit 1
fi
jq -e '.code == "0000" and (.data.enabled | type == "boolean") and
  (.data.model | type == "string") and (.data.model | length >= 1 and length <= 160) and
  ([paths(scalars) as $p | ($p[-1] | tostring | ascii_downcase)] |
    all(. != "providerurl" and . != "provider-url" and . != "upstreamurl" and
      . != "apikey" and . != "api-key" and . != "providerapikey"))' "$work_dir/config.json" >/dev/null
model="$(jq -er '.data.model' "$work_dir/config.json")"
enabled="$(jq -er '.data.enabled' "$work_dir/config.json")"
chat_payload="$(jq -n --arg model "$model" '{model:$model,stream:true,temperature:0,
  messages:[{role:"user",content:"Reply with exactly MYTOOLS_COPILOT_SMOKE_OK"}]}')"

if [[ "$enabled" != 'true' ]]; then
  disabled_status="$(request_json POST '/api/app/v1/copilot/chat' "$work_dir/disabled.json" \
    "$access_token" "$chat_payload")"
  if [[ "$disabled_status" != '503' ]] || ! jq -e '.code == "91001"' "$work_dir/disabled.json" >/dev/null; then
    echo "Disabled Copilot contract failed with HTTP ${disabled_status}" >&2
    exit 1
  fi
  if [[ "${MYTOOLS_COPILOT_SMOKE_ALLOW_DISABLED:-false}" != 'true' ]]; then
    echo 'Copilot gateway is disabled; disabled contract passed but live SSE was not exercised' >&2
    exit 3
  fi
  echo 'MyTools Copilot disabled-gateway contract smoke test passed'
  exit 0
fi

invalid_payload="$(jq -n --arg model "$model" '{model:$model,stream:false,messages:[]}')"
invalid_status="$(request_json POST '/api/app/v1/copilot/chat' "$work_dir/invalid.json" \
  "$access_token" "$invalid_payload")"
if [[ "$invalid_status" != '400' ]] || ! jq -e '.code == "91002"' "$work_dir/invalid.json" >/dev/null; then
  echo "Invalid Copilot request returned HTTP ${invalid_status}, expected 400/91002" >&2
  exit 1
fi

chat_status="$(curl --silent --show-error --output "$work_dir/chat.sse" --dump-header "$work_dir/chat.headers" \
  --write-out '%{http_code}' --max-time "${MYTOOLS_COPILOT_SMOKE_TIMEOUT_SECONDS:-120}" \
  --request POST --header 'Accept: text/event-stream' --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${access_token}" --data "$chat_payload" \
  "${base_url}/api/app/v1/copilot/chat")"
if [[ ! "$chat_status" =~ ^2 ]]; then
  echo "Copilot SSE request failed with HTTP ${chat_status}" >&2
  exit 1
fi
if ! tr -d '\r' < "$work_dir/chat.headers" | grep -Eiq '^content-type:[[:space:]]*text/event-stream'; then
  echo 'Copilot gateway did not return text/event-stream' >&2
  exit 1
fi
body_size="$(wc -c < "$work_dir/chat.sse" | tr -d ' ')"
if (( body_size == 0 || body_size > 10485760 )); then
  echo "Copilot SSE body size is invalid: ${body_size}" >&2
  exit 1
fi
python3 - "$work_dir/chat.sse" <<'PY'
import json
import sys
from pathlib import Path

payloads = []
done = False
useful = False
for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if not line.startswith("data:"):
        continue
    data = line[5:].strip()
    if data == "[DONE]":
        done = True
        continue
    if not data:
        continue
    value = json.loads(data)
    if "error" in value:
        raise SystemExit("Copilot SSE contains a provider error")
    payloads.append(value)
    for choice in value.get("choices", []):
        delta = choice.get("delta", {})
        if delta.get("content") or delta.get("tool_calls") or delta.get("function_call"):
            useful = True
if not payloads or not useful or not done:
    raise SystemExit("Copilot SSE lacks useful deltas or the [DONE] terminator")
PY

echo 'MyTools Copilot live gateway SSE smoke test passed'
