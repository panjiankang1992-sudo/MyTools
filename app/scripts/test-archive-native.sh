#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-archive-build.XXXXXX)"
trap 'rm -rf "$work"' EXIT
extra=()
if [[ "$(uname)" == Darwin ]]; then
  extra+=("-DCMAKE_OSX_SYSROOT=$(xcrun --sdk macosx --show-sdk-path)")
fi
cmake -S "$app_root/tests/archive-native" -B "$work" "${extra[@]}" > "$work/config.log" 2>&1 || { cat "$work/config.log"; exit 1; }
cmake --build "$work" -j 8 > "$work/build.log" 2>&1 || { tail -80 "$work/build.log"; exit 1; }
python3 "$app_root/tests/archive-native/verify.py" "$work/archive_test" "$work/archive_fixture_7zz"
