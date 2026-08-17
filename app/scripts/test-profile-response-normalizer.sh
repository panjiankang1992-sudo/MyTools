#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
NORMALIZER="$APP_DIR/entry/src/main/ets/features/profile/ProfileResponseNormalizer.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/profile/ProfileModels.ets"
API="$APP_DIR/entry/src/main/ets/features/profile/ProfileApi.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

mkdir -p "$TEST_DIR/profile"
cp "$NORMALIZER" "$TEST_DIR/profile/ProfileResponseNormalizer.ts"
cp "$MODELS" "$TEST_DIR/profile/ProfileModels.ts"
node "$TSC_BIN" "$TEST_DIR/profile/ProfileResponseNormalizer.ts" "$TEST_DIR/profile/ProfileModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/profile_response_normalizer_test.cjs" \
  "$TEST_DIR/output/ProfileResponseNormalizer.js"

grep -Fq "this.client.get('/api/user/info', cancellation)" "$API"
grep -Fq 'this.RefreshProfileIdentity();' "$PAGE"
grep -Fq 'new util.Base64Helper().decodeSync' "$PAGE"
grep -Fq 'image.createImageSource(bytes.buffer as ArrayBuffer)' "$PAGE"
grep -Fq 'this.profileAvatarPixelMap = pixelMap;' "$PAGE"
grep -Fq 'this.activeProfileCancellation?.cancel();' "$PAGE"

echo 'Profile response integration policy tests passed'
