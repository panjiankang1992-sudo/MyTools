#!/usr/bin/env bash
set -euo pipefail

# 从总环境文件中提取 MCP 所需的最小权限配置，不复制 SMTP、IMAP 或数据库凭据。
source_file="${1:-/opt/yuyutian/mytools/config/services.env}"
target_file="${2:-/opt/yuyutian/mytools/config/messaging-mcp.env}"
target_owner="${3:-mytools}"
target_group="${4:-mytools}"
temporary_file="$(mktemp)"
trap 'rm -f "$temporary_file"' EXIT

awk -F= '
  /^MESSAGING_INTERNAL_TOKEN=/ { print; token = 1 }
  /^MESSAGING_EMAIL_OWNER_ID=/ {
    print "MESSAGING_MCP_OWNER_ID=" substr($0, index($0, "=") + 1); owner = 1
  }
  /^MESSAGING_EMAIL_ACCOUNT_KEY=/ {
    print "MESSAGING_MCP_EMAIL_ACCOUNT_KEY=" substr($0, index($0, "=") + 1)
  }
  END {
    if (!token || !owner) exit 3
  }
' "$source_file" > "$temporary_file"
printf '%s\n' 'MESSAGING_URL=http://127.0.0.1:23250' >> "$temporary_file"
install -o "$target_owner" -g "$target_group" -m 0600 "$temporary_file" "$target_file"
