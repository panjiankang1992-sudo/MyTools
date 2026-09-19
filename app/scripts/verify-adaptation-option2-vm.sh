#!/usr/bin/env bash
set -euo pipefail

# 仅操作本地 HarmonyOS 虚拟机；不提交改编，不改书架或账号配置。
task_target="${MYTOOLS_ADAPTATION_VM_TARGET:-127.0.0.1:15555}"
[[ "$task_target" =~ ^127\.0\.0\.1:[0-9]+$ ]] || exit 2
task_hdc=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
task_app_root="$(cd "$(dirname "$0")/.." && pwd)"
task_evidence="$task_app_root/build/acceptance/chapter-adaptation-option2"
mkdir -p "$task_evidence"
device() { "$task_hdc" -t "$task_target" "$@"; }
layout() {
  device shell uitest dumpLayout -p /data/local/tmp/adaptation-option2-layout.json >/dev/null
  device file recv /data/local/tmp/adaptation-option2-layout.json "$task_evidence/current-layout.json" >/dev/null
}
wait_text() {
  for task_try in $(seq 1 15); do
    layout
    if jq -e --arg label "$1" '.. | objects | select(.text? == $label)' "$task_evidence/current-layout.json" >/dev/null; then return; fi
    sleep 1
  done
  echo "Expected UI label not found: $1" >&2; return 1
}
click_text() {
  wait_text "$1"
  local task_bounds task_x task_y
  task_bounds="$(jq -r --arg label "$1" '[.. | objects | select(.text? == $label) | .bounds] | last' "$task_evidence/current-layout.json")"
  read -r task_x task_y <<<"$(jq -nr --arg bounds "$task_bounds" '$bounds | [scan("[0-9]+") | tonumber] | "\(((.[0]+.[2])/2)|floor) \(((.[1]+.[3])/2)|floor)"')"
  device shell uitest uiInput click "$task_x" "$task_y" >/dev/null
}
capture() {
  layout
  device shell snapshot_display -f "/data/local/tmp/adaptation-option2-$1.jpeg" >/dev/null
  device file recv "/data/local/tmp/adaptation-option2-$1.jpeg" "$task_evidence/$1.jpeg" >/dev/null
  echo "Captured: $1"
}

# 从冷启动后的真实书架开始，选择已有 V1—V3 的后端验收章节。
click_text '截胡女主，主角美母上门找我借钱'
sleep 2
device shell uitest uiInput click 665 1800 >/dev/null
click_text '目录'
wait_text '1重生大反派'
capture catalog
# 此按钮位置已通过目录截图与布局核对；章节标题点击与改编按钮互不替代。
device shell uitest uiInput click 995 759 >/dev/null
wait_text '查看详情'
capture list
click_text '查看详情'
wait_text '阅读改编内容'
sleep 2
capture detail
click_text '阅读改编内容'
wait_text '改编正文'
capture reading
device shell uitest uiInput keyEvent Back >/dev/null
wait_text '改编详情'
device shell uitest uiInput keyEvent Back >/dev/null
wait_text '新建改编'
click_text '新建改编'
wait_text '改编要求'
sleep 2
capture form
echo 'Navigation and capture complete; generation was NOT submitted.'
