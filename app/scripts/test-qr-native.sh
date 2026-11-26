#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-qr-test.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cc_bin="${CC:-cc}"
cxx_bin="${CXX:-c++}"
flags=()
if [[ "$(uname)" == Darwin ]]; then
  cc_bin="$(xcrun --find clang)"
  cxx_bin="$(xcrun --find clang++)"
  flags+=("-isysroot" "$(xcrun --sdk macosx --show-sdk-path)")
fi
native="$app_root/entry/src/main/cpp"
objects=()
for source in "$native"/vendor/qr/quirc/*.c "$native"/vendor/qr/qrcodegen/*.c; do
  object="$work/$(basename "$source").o"
  "$cc_bin" "${flags[@]}" -DQUIRC_MAX_REGIONS=8192 -O2 -c "$source" -o "$object"
  objects+=("$object")
done
"$cxx_bin" "${flags[@]}" -std=c++17 -O2 -I"$native" "$native/qr_engine_core.cpp" "$app_root/tests/qr-native/core_test.cpp" "${objects[@]}" -o "$work/test"
"$work/test"
