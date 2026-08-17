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

request() {
  local method="$1"
  local path="$2"
  local output="$3"
  local authorization="${4:-}"
  local payload="${5:-}"
  local args=(--silent --show-error --output "$output" --write-out '%{http_code}'
    --request "$method" --header 'Accept: application/json')
  if [[ -n "$authorization" ]]; then
    args+=(--header "Authorization: Bearer ${authorization}")
  fi
  if [[ -n "$payload" ]]; then
    args+=(--header 'Content-Type: application/json' --data "$payload")
  fi
  curl "${args[@]}" "${base_url}${path}"
}

assert_2xx() {
  local status="$1"
  local step="$2"
  if [[ ! "$status" =~ ^2 ]]; then
    echo "${step} failed with HTTP ${status}" >&2
    exit 1
  fi
}

health_status="$(request GET '/actuator/health' "$work_dir/health.json")"
assert_2xx "$health_status" 'Health check'

login_payload="$(jq -n \
  --arg account "$MYTOOLS_SMOKE_ACCOUNT" \
  --arg password "$MYTOOLS_SMOKE_PASSWORD" \
  '{account:$account,password:$password,deviceName:"HarmonyOS smoke test"}')"
login_status="$(request POST '/api/auth/login' "$work_dir/login.json" '' "$login_payload")"
assert_2xx "$login_status" 'Login'
access_token="$(jq -er '.data.accessToken' "$work_dir/login.json")"
refresh_token="$(jq -er '.data.refreshToken' "$work_dir/login.json")"

current_status="$(request GET '/api/tokens/current' "$work_dir/current.json" "$access_token")"
assert_2xx "$current_status" 'Current session lookup'
jq -e '.data.id != null and .data.status == "ACTIVE"' "$work_dir/current.json" >/dev/null

refresh_status="$(request POST '/api/auth/refresh' "$work_dir/refresh.json" "$refresh_token")"
assert_2xx "$refresh_status" 'Token rotation'
new_access_token="$(jq -er '.data.accessToken' "$work_dir/refresh.json")"
new_refresh_token="$(jq -er '.data.refreshToken' "$work_dir/refresh.json")"
if [[ "$new_access_token" == "$access_token" || "$new_refresh_token" == "$refresh_token" ]]; then
  echo 'Token rotation returned an unchanged token' >&2
  exit 1
fi
access_token="$new_access_token"

replay_status="$(request POST '/api/auth/refresh' "$work_dir/replay.json" "$refresh_token")"
if [[ "$replay_status" != '401' ]]; then
  echo "Consumed refresh token replay returned HTTP ${replay_status}, expected 401" >&2
  exit 1
fi

logout_status="$(request POST '/api/auth/logout' "$work_dir/logout.json" "$new_access_token")"
assert_2xx "$logout_status" 'Logout'
access_token=''
revoked_status="$(request GET '/api/tokens/current' "$work_dir/revoked.json" "$new_access_token")"
if [[ "$revoked_status" != '401' ]]; then
  echo "Revoked access token returned HTTP ${revoked_status}, expected 401" >&2
  exit 1
fi

echo 'MyTools backend authentication smoke test passed'
