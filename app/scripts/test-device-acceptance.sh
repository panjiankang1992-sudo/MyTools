#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/bin" "$TEST_DIR/evidence"
cp "$APP_DIR/tests/device_acceptance_mock_java.sh" "$TEST_DIR/bin/java"
chmod +x "$TEST_DIR/bin/java" "$APP_DIR/tests/device_acceptance_mock_hdc.sh"
printf 'signed-hap-fixture' > "$TEST_DIR/entry-default-signed.hap"
printf 'unsigned-hap-fixture' > "$TEST_DIR/entry-default-unsigned.hap"
printf 'jar-fixture' > "$TEST_DIR/sign-tool.jar"

output="$(PATH="$TEST_DIR/bin:$PATH" MYTOOLS_DEVICE_HAP="$TEST_DIR/entry-default-signed.hap" \
  MYTOOLS_HDC_BIN="$APP_DIR/tests/device_acceptance_mock_hdc.sh" MYTOOLS_HAP_SIGN_TOOL="$TEST_DIR/sign-tool.jar" \
  MYTOOLS_DEVICE_EVIDENCE_DIR="$TEST_DIR/evidence" "$APP_DIR/scripts/run-device-acceptance.sh")"
report="${output#Device acceptance evidence: }"
[[ -f "$report" ]]
jq -e '.result == "passed" and .checks.signature == "passed" and
  .checks.install == "passed" and .checks.coldStart == "passed" and
  .checks.processObserved == "passed" and (.targetSha256 | test("^[a-f0-9]{64}$"))' "$report" >/dev/null
mode="$(stat -f '%Lp' "$report")"
[[ "$mode" == '600' ]]
if PATH="$TEST_DIR/bin:$PATH" MYTOOLS_DEVICE_HAP="$TEST_DIR/entry-default-unsigned.hap" \
  MYTOOLS_HDC_BIN="$APP_DIR/tests/device_acceptance_mock_hdc.sh" MYTOOLS_HAP_SIGN_TOOL="$TEST_DIR/sign-tool.jar" \
  "$APP_DIR/scripts/run-device-acceptance.sh" >/dev/null 2>&1; then
  echo 'Unsigned HAP filename was accepted' >&2
  exit 1
fi
grep -Fq 'verify-app' "$APP_DIR/scripts/run-device-acceptance.sh"
grep -Fq 'aa start -a' "$APP_DIR/scripts/run-device-acceptance.sh"
echo 'Device acceptance policy tests passed'
