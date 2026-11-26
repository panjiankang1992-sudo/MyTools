#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteImageGesturePolicy.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/RemoteImageGesturePolicy.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteImageGesturePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_image_gesture_policy_test.cjs" "$TEST_DIR/output/RemoteImageGesturePolicy.js"

grep -Fq "PinchGesture({ fingers: 2 })" "$PAGE"
grep -Fq "PanGesture({ fingers: 1" "$PAGE"
grep -Fq "this.imageGesturePolicy.swipeDirection" "$PAGE"
grep -Fq "this.EndRemoteViewerVerticalSwipe(event.offsetX, event.offsetY, event.velocityY)" "$PAGE"
grep -Fq "this.UpdateRemoteViewerVerticalSwipe(event.offsetX, event.offsetY)" "$PAGE"
grep -Fq 'this.MediaViewerAdjacentPage(this.mediaViewerSlideNextUri, 1)' "$PAGE"
grep -Fq '.translate({ y: this.mediaViewerDragOffsetY })' "$PAGE"
grep -Fq 'this.getUIContext().animateTo({ duration: direction === 0 ? 200 : 260, curve: Curve.EaseOut,' "$PAGE"
grep -Fq "this.queuedImageNavigationDirection = direction" "$PAGE"
grep -Fq "this.ChangeRemoteImage(queuedDirection)" "$PAGE"
grep -Fq "this.ResetRemoteImageTransform()" "$PAGE"

echo "Remote image gesture integration policy tests passed"

cd "$APP_DIR/.."
node app/tests/remote_image_slide_test.cjs
