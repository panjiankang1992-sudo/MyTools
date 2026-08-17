#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
app_dir="$(cd "${script_dir}/.." && pwd)"
evidence_dir="${MYTOOLS_PREFLIGHT_EVIDENCE_DIR:-${app_dir}/build/acceptance}"
hdc_bin="${MYTOOLS_HDC_BIN:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc}"
sign_tool="${MYTOOLS_HAP_SIGN_TOOL:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar}"

if ! command -v jq >/dev/null || ! command -v python3 >/dev/null; then
  echo 'jq and python3 are required' >&2
  exit 2
fi

umask 077
mkdir -p "$evidence_dir"
report="${evidence_dir}/acceptance-preflight-$(date -u '+%Y%m%dT%H%M%SZ').json"

signed_hap="${MYTOOLS_DEVICE_HAP:-}"
hap_state='missing'
if [[ -n "$signed_hap" && -f "$signed_hap" && "$signed_hap" == *.hap ]]; then
  if [[ "$(basename "$signed_hap")" == *unsigned* ]]; then
    hap_state='unsigned'
  else
    hap_state='candidate'
  fi
fi

toolchain_state='missing'
[[ -x "$hdc_bin" && -f "$sign_tool" ]] && toolchain_state='available'

device_count=0
if [[ -x "$hdc_bin" ]]; then
  device_count="$($hdc_bin list targets 2>/dev/null | tr -d '\r' |
    awk 'NF && $0 != "[Empty]" {count++} END {print count+0}')"
fi
device_state='missing'
if [[ -n "${MYTOOLS_DEVICE_TARGET:-}" ]]; then
  device_state='requested'
elif [[ "$device_count" == '1' ]]; then
  device_state='single'
elif [[ "$device_count" -gt 1 ]]; then
  device_state='ambiguous'
fi

deployment_required=(MYTOOLS_SMOKE_BASE_URL MYTOOLS_SMOKE_USERNAME MYTOOLS_SMOKE_PASSWORD \
  MYTOOLS_SMOKE_MEDIA_ACCOUNT_ID MYTOOLS_SMOKE_MEDIA_PATH)
missing_deployment=()
for name in "${deployment_required[@]}"; do
  [[ -n "${!name:-}" ]] || missing_deployment+=("$name")
done
if [[ "${MYTOOLS_SMOKE_INCLUDE_COPILOT:-true}" == 'true' && -z "${MYTOOLS_SMOKE_COPILOT_PROMPT:-}" ]]; then
  missing_deployment+=(MYTOOLS_SMOKE_COPILOT_PROMPT)
fi

device_ready=false
[[ "$hap_state" == 'candidate' && "$toolchain_state" == 'available' && \
  ( "$device_state" == 'single' || "$device_state" == 'requested' ) ]] && device_ready=true
deployment_ready=false
[[ "${#missing_deployment[@]}" == '0' ]] && deployment_ready=true
overall='blocked'
[[ "$device_ready" == true && "$deployment_ready" == true ]] && overall='ready'

missing_json="$(printf '%s\n' "${missing_deployment[@]:-}" | jq -Rsc 'split("\n") | map(select(length > 0))')"
jq -n --arg schemaVersion '1' --arg generatedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg overall "$overall" --arg hap "$hap_state" --arg toolchain "$toolchain_state" \
  --arg device "$device_state" --argjson deviceCount "$device_count" --argjson deviceReady "$device_ready" \
  --argjson deploymentReady "$deployment_ready" --argjson missingDeployment "$missing_json" \
  '{schemaVersion:$schemaVersion,generatedAt:$generatedAt,overall:$overall,
    device:{ready:$deviceReady,hap:$hap,toolchain:$toolchain,state:$device,count:$deviceCount},
    deployment:{ready:$deploymentReady,missingEnvironmentVariables:$missingDeployment}}' > "$report"
chmod 600 "$report"
echo "Acceptance preflight evidence: ${report}"
[[ "$overall" == 'ready' ]]
