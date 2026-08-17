#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
preflight="${script_dir}/run-acceptance-preflight.sh"

bash -n "$preflight"
grep -Fq "overall='blocked'" "$preflight"
grep -Fq 'missingEnvironmentVariables:$missingDeployment' "$preflight"
grep -Fq 'chmod 600 "$report"' "$preflight"
grep -Fq '$(basename "$signed_hap")" == *unsigned*' "$preflight"
grep -Fq 'device_count="$($hdc_bin list targets' "$preflight"
grep -Fq "tr -d '\\r'" "$preflight"
grep -Fq 'line="${line%$' "${script_dir}/run-device-acceptance.sh"
if grep -Eq 'PASSWORD}|USERNAME}|MEDIA_PATH}' "$preflight"; then
  echo 'Preflight must not serialize secret environment values' >&2
  exit 1
fi

echo 'Acceptance preflight policy tests passed'
