#!/bin/bash
set -euo pipefail

# 使用多连接下载完整安装包，避免单连接带宽过低。
install_directory="$(mktemp -d /tmp/ollama-gpu-install.XXXXXX)"
archive_path="$install_directory/ollama-linux-amd64.tar.zst"
aria2c --max-connection-per-server=16 --split=16 --min-split-size=4M \
  --file-allocation=none --dir="$install_directory" \
  --out="$(basename "$archive_path")" \
  https://github.com/ollama/ollama/releases/latest/download/ollama-linux-amd64.tar.zst

# 下载完成后一次性解压，再重启服务加载 GPU runner。
zstd -dc "$archive_path" | tar -xf - -C /usr/local
systemctl restart ollama.service
