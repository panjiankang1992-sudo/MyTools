#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd "${script_directory}/../.." && pwd)"

install -o root -g root -m 0755 \
  "${project_directory}/deploy/scripts/remount-resource-storage.sh" \
  /usr/local/sbin/mytools-resource-remount
install -o root -g root -m 0644 \
  "${project_directory}/deploy/systemd/mytools-resource-remount.service" \
  /etc/systemd/system/mytools-resource-remount.service
install -o root -g root -m 0644 \
  "${project_directory}/deploy/systemd/mytools-resource-remount.timer" \
  /etc/systemd/system/mytools-resource-remount.timer

systemctl daemon-reload
systemctl enable --now mytools-resource-remount.timer
systemctl start mytools-resource-remount.service
