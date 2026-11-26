#!/usr/bin/env bash
# 任务 3-2 的状态比对（**不依赖 diff**）。
#
# 为什么要单独一个脚本：本机 PATH 上的 `diff` 是 DevEco/OpenHarmony 工具链里的精简实现，
# 它不支持 `-u`/`-q`，而且**报"illegal option"时退出码仍是 0**——用 `if diff a b` 判断
# 会把"没比较"误判成"一致"。这里改用 python 逐行比对，退出码由 python 决定。
#
# 用法：bash 31-item2-compare-states.sh <before.txt> <after.txt>
set -euo pipefail

BEFORE="${1:?before file}"
AFTER="${2:?after file}"

python3 - "$BEFORE" "$AFTER" <<'PYEOF'
import difflib, sys
before = open(sys.argv[1], encoding='utf-8').read().splitlines()
after = open(sys.argv[2], encoding='utf-8').read().splitlines()
print(f"比对文件：{sys.argv[1]} vs {sys.argv[2]}（逐行，{len(before)} 行）")
if before == after:
    print("比对结果：**完全一致**（发布失败没有改变任何一项状态）")
    raise SystemExit(0)
print("比对结果：**存在差异**")
for line in difflib.unified_diff(before, after, 'before', 'after', lineterm=''):
    print(line)
raise SystemExit(1)
PYEOF
