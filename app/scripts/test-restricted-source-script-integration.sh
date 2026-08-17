#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$APP_DIR/entry/src/main/ets/features/reader/RestrictedSourceScript.ets"
POLICY="$APP_DIR/entry/src/main/ets/features/reader/BookSourceRulePolicy.ets"
SEARCH="$APP_DIR/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
READER="$APP_DIR/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"

grep -Fq "MAX_SCRIPT_LENGTH: number = 4096" "$RUNTIME"
grep -Fq "MAX_VALUE_LENGTH: number = 5 * 1024 * 1024" "$RUNTIME"
grep -Fq "MAX_CALLS: number = 16" "$RUNTIME"
grep -Fq "MAX_REPLACEMENT_WORK: number = 8 * 1024 * 1024" "$RUNTIME"
grep -Fq "受限脚本必须跟在声明式选择器之后" "$RUNTIME"
grep -Fq "列表规则不允许字符串后处理" "$POLICY"
grep -Fq "this.rulePolicy.transform(rules, key, value)" "$SEARCH"
grep -Fq "this.validateRestrictedScripts(rules)" "$SEARCH"
grep -Fq "this.requireDeclarativeRules(source)" "$READER"
grep -Fq "this.rulePolicy.transform(source.ruleContent, 'content', value)" "$READER"
grep -Fq "this.transformValue(source.ruleContent, 'content'" "$READER"

if grep -E "\beval\s*\(|new Function|runJavaScript|ArkWeb|WebView" "$RUNTIME" >/dev/null; then
  echo "Restricted source scripts must not use a JavaScript engine" >&2
  exit 1
fi

echo "Restricted source script integration tests passed"
