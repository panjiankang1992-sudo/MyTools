#!/usr/bin/env bash
set -euo pipefail

required=(MYTOOLS_DEVICE_HAP)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 2
  fi
done
if [[ ! -f "$MYTOOLS_DEVICE_HAP" ]] || [[ "$MYTOOLS_DEVICE_HAP" != *.hap ]]; then
  echo 'MYTOOLS_DEVICE_HAP must point to an existing HAP file' >&2
  exit 2
fi
if [[ "$(basename "$MYTOOLS_DEVICE_HAP")" == *unsigned* ]]; then
  echo 'Unsigned HAP is not accepted for device evidence' >&2
  exit 2
fi
if ! command -v jq >/dev/null || ! command -v python3 >/dev/null || ! command -v java >/dev/null; then
  echo 'jq, python3 and java are required' >&2
  exit 2
fi

bundle_name="${MYTOOLS_DEVICE_BUNDLE_NAME:-com.yuyutian.mytools}"
ability_name="${MYTOOLS_DEVICE_ABILITY_NAME:-EntryAbility}"
if [[ ! "$bundle_name" =~ ^[A-Za-z][A-Za-z0-9_.]{2,255}$ ]] ||
  [[ ! "$ability_name" =~ ^[A-Za-z][A-Za-z0-9_.]{0,255}$ ]]; then
  echo 'Bundle or ability name is invalid' >&2
  exit 2
fi

hdc_bin="${MYTOOLS_HDC_BIN:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc}"
sign_tool="${MYTOOLS_HAP_SIGN_TOOL:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar}"
if [[ ! -x "$hdc_bin" ]] || [[ ! -f "$sign_tool" ]]; then
  echo 'DevEco hdc or HAP signature verification tool was not found' >&2
  exit 2
fi

umask 077
work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

if ! java -jar "$sign_tool" verify-app -inFile "$MYTOOLS_DEVICE_HAP" \
  -outCertChain "$work_dir/cert-chain.cer" -outProfile "$work_dir/profile.p7b" \
  >"$work_dir/verify.log" 2>&1; then
  echo 'HAP signature verification failed' >&2
  exit 1
fi

mapfile_compat() {
  local line
  while IFS= read -r line; do
    line="${line%$'\r'}"
    [[ -n "$line" && "$line" != '[Empty]' ]] && printf '%s\n' "$line"
  done
}
targets="$($hdc_bin list targets | mapfile_compat)"
target_count="$(grep -c . <<<"$targets" || true)"
target="${MYTOOLS_DEVICE_TARGET:-}"
if [[ -n "$target" ]]; then
  if ! grep -Fxq "$target" <<<"$targets"; then
    echo 'Requested HarmonyOS device is not online' >&2
    exit 2
  fi
elif [[ "$target_count" == '1' ]]; then
  target="$targets"
else
  echo 'Exactly one device must be online, or set MYTOOLS_DEVICE_TARGET' >&2
  exit 2
fi

device() { "$hdc_bin" -t "$target" "$@"; }
if ! device install -r "$MYTOOLS_DEVICE_HAP" >"$work_dir/install.log" 2>&1; then
  echo 'Signed HAP installation failed' >&2
  exit 1
fi
device shell aa force-stop "$bundle_name" >/dev/null 2>&1 || true
if ! device shell aa start -a "$ability_name" -b "$bundle_name" >"$work_dir/start.log" 2>&1; then
  echo 'EntryAbility cold start failed' >&2
  exit 1
fi

pid=''
for _ in $(seq 1 50); do
  pid="$(device shell pidof "$bundle_name" 2>/dev/null | tr -d '\r\n ' || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] && break
  sleep 0.1
done
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
  echo 'Application process was not observed after launch' >&2
  exit 1
fi

evidence_dir="${MYTOOLS_DEVICE_EVIDENCE_DIR:-$(cd "$(dirname "$0")/.." && pwd)/build/acceptance}"
mkdir -p "$evidence_dir"
report="${evidence_dir}/device-acceptance-$(date -u '+%Y%m%dT%H%M%SZ').json"
hap_hash="$(python3 - "$MYTOOLS_DEVICE_HAP" <<'PY'
import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], "rb") as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        h.update(block)
print(h.hexdigest())
PY
)"
target_hash="$(printf '%s' "$target" | shasum -a 256 | awk '{print $1}')"
jq -n --arg schemaVersion '1' --arg generatedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg bundleName "$bundle_name" --arg abilityName "$ability_name" --arg hapSha256 "$hap_hash" \
  --arg targetSha256 "$target_hash" --arg result 'passed' \
  '{schemaVersion:$schemaVersion,generatedAt:$generatedAt,bundleName:$bundleName,abilityName:$abilityName,
    hapSha256:$hapSha256,targetSha256:$targetSha256,checks:{signature:"passed",install:"passed",
    coldStart:"passed",processObserved:"passed"},result:$result}' > "$report"
chmod 600 "$report"
echo "Device acceptance evidence: ${report}"
