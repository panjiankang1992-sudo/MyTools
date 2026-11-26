#!/usr/bin/env bash
# 反向验证：代次守卫的回归测试是否**真的有鉴别力**。
#
# 步骤：
#   1. 原样跑目标测试 —— 期望通过（守卫在）。
#   2. 把守卫改成 `if (false)` —— 期望**失败**（旧回包被写回）。
#   3. 还原并再次跑 —— 期望通过（按字节比对确认还原无损）。
#
# 用法：bash docs/verification/toolbox-admin-completion-2026-10-07/verify-generation-guard.sh
set -uo pipefail

WEB="$(cd "$(dirname "$0")/../../../toolbox-web" && pwd)"
OUT="$(cd "$(dirname "$0")" && pwd)"
TARGET="src/views/admin/TextbookEditorView.vue"
TEST="tests/component/admin-editor-mount.test.ts"
ORIGINAL='    if (generation !== completionGeneration) {'
DISABLED='    if (false) {'
BACKUP="$(mktemp)"

cleanup() {
  # 无论中间发生什么，都必须把源码还原；否则"验证"本身会留下一个被削弱的守卫。
  if [ -f "$BACKUP" ]; then
    cp "$BACKUP" "$WEB/$TARGET"
    rm -f "$BACKUP"
  fi
}
trap cleanup EXIT

cd "$WEB"
cp "$TARGET" "$BACKUP"

echo "=== [1/3] 守卫在位：应通过 ===" | tee "$OUT/01-with-guard.log"
npx vitest run "$TEST" 2>&1 | tee -a "$OUT/01-with-guard.log"
echo "exit=${PIPESTATUS[0]}" | tee -a "$OUT/01-with-guard.log"

echo "=== [2/3] 守卫被去掉（if (false)）：应失败 ===" | tee "$OUT/02-guard-removed.log"
python3 - "$TARGET" "$ORIGINAL" "$DISABLED" <<'PYEOF' | tee -a "$OUT/02-guard-removed.log"
import sys
from pathlib import Path
path, original, disabled = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text(encoding='utf-8')
assert text.count(original) == 1, f'守卫锚点出现 {text.count(original)} 次，拒绝改动'
path.write_text(text.replace(original, disabled), encoding='utf-8')
print('已把 `if (generation !== completionGeneration) {` 改成 `if (false) {`')
PYEOF
npx vitest run "$TEST" 2>&1 | tee -a "$OUT/02-guard-removed.log"
echo "exit=${PIPESTATUS[0]}" | tee -a "$OUT/02-guard-removed.log"

echo "=== [3/3] 还原：应再次通过 ===" | tee "$OUT/03-restored.log"
cp "$BACKUP" "$WEB/$TARGET"
diff "$BACKUP" "$WEB/$TARGET" >> "$OUT/03-restored.log" 2>&1 \
  && echo "还原后与改动前逐字节一致" >> "$OUT/03-restored.log"
npx vitest run "$TEST" 2>&1 | tee -a "$OUT/03-restored.log"
echo "exit=${PIPESTATUS[0]}" | tee -a "$OUT/03-restored.log"
