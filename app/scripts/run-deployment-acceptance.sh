#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
evidence_dir="${MYTOOLS_SMOKE_EVIDENCE_DIR:-${script_dir}/../build/acceptance}"
mkdir -p "$evidence_dir"
umask 077
timestamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
report="${evidence_dir}/deployment-acceptance-$(date -u '+%Y%m%dT%H%M%SZ').json"
results='[]'

run_check() {
  local name="$1" script="$2"
  local started ended status='passed' exit_code=0
  started="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  if "$script"; then
    exit_code=0
  else
    exit_code=$?
    status='failed'
  fi
  ended="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  results="$(jq -c --arg name "$name" --arg status "$status" --arg started "$started" --arg ended "$ended" \
    --argjson exitCode "$exit_code" '. + [{name:$name,status:$status,startedAt:$started,endedAt:$ended,exitCode:$exitCode}]' \
    <<<"$results")"
  [[ "$status" == 'passed' ]]
}

overall='passed'
run_check 'backend-authentication' "${script_dir}/smoke-backend-auth.sh" || overall='failed'
run_check 'remote-media-range' "${script_dir}/smoke-remote-media.sh" || overall='failed'
if [[ "${MYTOOLS_SMOKE_INCLUDE_COPILOT:-true}" == 'true' ]]; then
  run_check 'copilot-gateway-sse' "${script_dir}/smoke-copilot-gateway.sh" || overall='failed'
fi

jq -n --arg schemaVersion '1' --arg generatedAt "$timestamp" --arg overall "$overall" \
  --arg targetOrigin "$(python3 - "${MYTOOLS_SMOKE_BASE_URL:-}" <<'PY'
import sys
from urllib.parse import urlsplit
p = urlsplit(sys.argv[1])
print(f"{p.scheme}://{p.hostname}" + (f":{p.port}" if p.port else "") if p.scheme and p.hostname else "invalid")
PY
)" --argjson checks "$results" \
  '{schemaVersion:$schemaVersion,generatedAt:$generatedAt,targetOrigin:$targetOrigin,overall:$overall,checks:$checks}' \
  > "$report"
chmod 600 "$report"
echo "Deployment acceptance evidence: ${report}"
[[ "$overall" == 'passed' ]]
