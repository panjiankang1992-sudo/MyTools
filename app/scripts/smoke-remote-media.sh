#!/usr/bin/env bash
set -euo pipefail

required=(MYTOOLS_SMOKE_BASE_URL MYTOOLS_SMOKE_ACCOUNT MYTOOLS_SMOKE_PASSWORD
  MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID MYTOOLS_SMOKE_MEDIA_PATH)
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
if [[ ! "$MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID" =~ ^[1-9][0-9]{0,19}$ ]]; then
  echo 'MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID must be a positive numeric account ID' >&2
  exit 2
fi

base_url="${MYTOOLS_SMOKE_BASE_URL%/}"
python3 - "$base_url" "${MYTOOLS_SMOKE_ALLOW_HTTP:-false}" "$MYTOOLS_SMOKE_MEDIA_PATH" <<'PY'
import sys
from urllib.parse import urlsplit
base, allow_http, path = sys.argv[1:]
parsed = urlsplit(base)
allowed = parsed.scheme == "https" or (parsed.scheme == "http" and allow_http == "true")
if not allowed or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
    raise SystemExit("Smoke target must be a credential-free HTTPS base URL; HTTP is local-development only")
if not path.startswith("/") or path == "/" or len(path) > 4096 or "\\" in path or "//" in path:
    raise SystemExit("Media path must be a canonical absolute remote file path")
segments = path.split("/")[1:]
if any(not segment or segment in (".", "..") for segment in segments):
    raise SystemExit("Media path contains an unsafe segment")
if any(ord(char) < 32 or ord(char) == 127 for char in path):
    raise SystemExit("Media path contains a control character")
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
  '{account:$account,password:$password,deviceName:"HarmonyOS remote media smoke test"}')"
login_status="$(request_json POST '/api/auth/login' "$work_dir/login.json" '' "$login_payload")"
if [[ ! "$login_status" =~ ^2 ]]; then
  echo "Remote media smoke login failed with HTTP ${login_status}" >&2
  exit 1
fi
access_token="$(jq -er '.data.accessToken' "$work_dir/login.json")"

query="$(python3 - "$MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID" "$MYTOOLS_SMOKE_MEDIA_PATH" <<'PY'
import sys
from urllib.parse import urlencode
print(urlencode({"accountId": sys.argv[1], "path": sys.argv[2]}))
PY
)"
unauthorized_status="$(request_json POST "/api/app/v1/media/tickets?${query}" "$work_dir/unauthorized.json")"
if [[ "$unauthorized_status" != '401' ]]; then
  echo "Unauthenticated media ticket returned HTTP ${unauthorized_status}, expected 401" >&2
  exit 1
fi

ticket_status="$(request_json POST "/api/app/v1/media/tickets?${query}" "$work_dir/ticket.json" "$access_token")"
if [[ ! "$ticket_status" =~ ^2 ]]; then
  echo "Media ticket issue failed with HTTP ${ticket_status}" >&2
  exit 1
fi
ticket="$(jq -er '.data.ticket | select(test("^[a-f0-9]{32}$"))' "$work_dir/ticket.json")"
stream_path="$(jq -er --arg ticket "$ticket" \
  '.data.streamPath | select(. == ("/api/app/v1/media/tickets/" + $ticket))' "$work_dir/ticket.json")"
jq -e '.code == "0000" and (.data.expiresAt | type == "string")' "$work_dir/ticket.json" >/dev/null

range_status="$(curl --silent --show-error --output "$work_dir/range.bin" --dump-header "$work_dir/range.headers" \
  --write-out '%{http_code}' --max-time "${MYTOOLS_SMOKE_MEDIA_TIMEOUT_SECONDS:-60}" \
  --header 'Range: bytes=0-0' "${base_url}${stream_path}")"
if [[ "$range_status" != '206' ]]; then
  echo "Media byte range returned HTTP ${range_status}, expected 206" >&2
  exit 1
fi
if [[ "$(wc -c < "$work_dir/range.bin" | tr -d ' ')" != '1' ]]; then
  echo 'Media byte range did not return exactly one byte' >&2
  exit 1
fi
headers="$(tr -d '\r' < "$work_dir/range.headers")"
if ! grep -Eiq '^accept-ranges:[[:space:]]*bytes[[:space:]]*$' <<<"$headers" ||
  ! grep -Eiq '^content-range:[[:space:]]*bytes[[:space:]]+0-0/[1-9][0-9]*[[:space:]]*$' <<<"$headers" ||
  ! grep -Eiq '^cache-control:.*no-store' <<<"$headers" ||
  ! grep -Eiq '^x-content-type-options:[[:space:]]*nosniff[[:space:]]*$' <<<"$headers"; then
  echo 'Media byte range response is missing required security or Range headers' >&2
  exit 1
fi

metrics_status="$(request_json GET "${stream_path}/metrics" "$work_dir/metrics.json" "$access_token")"
if [[ ! "$metrics_status" =~ ^2 ]] || ! jq -e '.code == "0000" and
  (.data.transferredBytes | type == "number" and . >= 1) and
  (.data.activeStreams | type == "number" and . >= 0) and
  (.data.lastTransferTime | type == "number" and . >= 0)' "$work_dir/metrics.json" >/dev/null; then
  echo "Media metrics contract failed with HTTP ${metrics_status}" >&2
  exit 1
fi

logout_status="$(request_json POST '/api/auth/logout' "$work_dir/logout.json" "$access_token")"
if [[ ! "$logout_status" =~ ^2 ]]; then
  echo "Remote media smoke logout failed with HTTP ${logout_status}" >&2
  exit 1
fi
access_token=''
revoked_status="$(curl --silent --show-error --output "$work_dir/revoked.json" --write-out '%{http_code}' \
  --max-time 30 --header 'Range: bytes=0-0' "${base_url}${stream_path}")"
if [[ "$revoked_status" != '401' ]]; then
  echo "Revoked-session media ticket returned HTTP ${revoked_status}, expected 401" >&2
  exit 1
fi

echo 'MyTools remote media ticket and Range smoke test passed'
