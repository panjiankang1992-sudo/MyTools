#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-dsh-auth.XXXXXX)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/dsh" "$work/connectivity"
cp "$app_root/entry/src/main/ets/features/dsh/DshWebAuthPolicy.ets" "$work/dsh/DshWebAuthPolicy.ts"
cp "$app_root/entry/src/main/ets/features/dsh/DshWebCapabilityPolicy.ets" "$work/dsh/DshWebCapabilityPolicy.ts"
cp "$app_root/entry/src/main/ets/features/connectivity/TrustedLanEndpointPolicy.ets" "$work/connectivity/TrustedLanEndpointPolicy.ts"
cp "$app_root/entry/src/main/ets/features/connectivity/Ipv6EndpointPolicy.ets" "$work/connectivity/Ipv6EndpointPolicy.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$work/dsh/DshWebCapabilityPolicy.ts" --target ES2020 --module commonjs --outDir "$work/output" --skipLibCheck
node "$app_root/tests/dsh_web_auth_policy_test.cjs" "$work/output/dsh/DshWebAuthPolicy.js"
node "$app_root/tests/dsh_web_capability_policy_test.cjs" "$work/output/dsh/DshWebCapabilityPolicy.js"
node "$app_root/tests/dsh_secure_connection_test.cjs" "$app_root/entry/src/main/ets/features/dsh/DshApi.ets" "$work/output/dsh/DshWebAuthPolicy.js"
