#!/usr/bin/env bash
set -euo pipefail

device_uuid="${MYTOOLS_RESOURCE_UUID:-AAFCDF66FCDF2B79}"
mount_point="${MYTOOLS_RESOURCE_MOUNT_POINT:-/opt/extend}"
resource_root="${MYTOOLS_RESOURCE_ROOT:-/opt/extend/resource}"
resource_username="${MYTOOLS_RESOURCE_USERNAME:-yuyutian}"
device_link="/dev/disk/by-uuid/${device_uuid}"

resource_layout_ready() {
  [[ -d "${resource_root}/.thumbnails" ]] &&
    { [[ -d "${resource_root}/media" ]] || [[ -d "${resource_root}/${resource_username}/media" ]]; }
}

if mountpoint -q "${mount_point}"; then
  if resource_layout_ready; then
    exit 0
  fi
  if [[ ! -e "${device_link}" ]]; then
    logger -t mytools-resource-remount "Resource mount is stale and the device is not connected"
    exit 0
  fi
  # 设备重新接入后先解除失效挂载，再按 fstab 参数恢复。
  umount --lazy "${mount_point}"
fi

if [[ ! -e "${device_link}" ]]; then
  logger -t mytools-resource-remount "Resource device is not connected"
  exit 0
fi

mkdir -p "${mount_point}"
# fstab 是挂载参数唯一来源，确保人工挂载和自动恢复一致。
systemctl reset-failed opt-extend.mount 2>/dev/null || true
mount "${mount_point}"

if ! mountpoint -q "${mount_point}"; then
  logger -t mytools-resource-remount "Resource storage mount command completed without a mount"
  exit 1
fi
if ! resource_layout_ready; then
  logger -t mytools-resource-remount "Resource storage mounted but required directories are missing"
  exit 1
fi

logger -t mytools-resource-remount "Resource storage mounted successfully"
