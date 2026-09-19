#!/usr/bin/env bash
set -euo pipefail
# 固定虚拟机与证据路径，不操作真机；点击来自即时布局。
task_hdc=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
task_dir="$(cd "$(dirname "$0")/.." && pwd)/build/acceptance/chapter-adaptation-option2"
device() { "$task_hdc" -t 127.0.0.1:15555 "$@"; }
layout() {
  device shell uitest dumpLayout -p /data/local/tmp/adaptation-v2.json >/dev/null
  device file recv /data/local/tmp/adaptation-v2.json "$task_dir/current-layout.json" >/dev/null
}
case "${1:-layout}" in
  layout)
    layout
    jq -r '..|objects|select(has("text"))|select(.text!="")|[.text[0:100],.bounds,.enabled]|@json' "$task_dir/current-layout.json" ;;
  click)
    layout
    task_bounds="$(jq -er --arg label "$2" '[..|objects|select(.text?==$label)|.bounds]|last // error("label missing")' "$task_dir/current-layout.json")"
    read -r task_x task_y <<<"$(jq -nr --arg b "$task_bounds" '$b|[scan("[0-9]+")|tonumber]|"\(((.[0]+.[2])/2)|floor) \(((.[1]+.[3])/2)|floor)"')"
    device shell uitest uiInput click "$task_x" "$task_y" ;;
  capture)
    [[ "$2" =~ ^[a-z0-9-]+$ ]] || exit 2
    layout
    device shell snapshot_display -f "/data/local/tmp/adaptation-$2.jpeg" >/dev/null
    device file recv "/data/local/tmp/adaptation-$2.jpeg" "$task_dir/$2.jpeg" >/dev/null
    cp "$task_dir/current-layout.json" "$task_dir/$2-layout.json"
    echo "$task_dir/$2.jpeg" ;;
  *) exit 2 ;;
esac
