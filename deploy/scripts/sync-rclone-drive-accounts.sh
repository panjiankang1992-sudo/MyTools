#!/usr/bin/env bash
set -euo pipefail

RCLONE_CONFIG="${RCLONE_CONFIG:-/opt/code/DownloadBot/runtime/rclone/rclone.conf}"
DRIVE_USERNAME="${1:-${MYTOOLS_DRIVE_USERNAME:-}}"
DRIVE_ACCOUNTS="${RCLONE_DRIVE_ACCOUNTS:-}"
MYSQL_HOST="${MYTOOLS_DB_HOST:-127.0.0.1}"
MYSQL_PORT="${MYTOOLS_DB_PORT:-3306}"
MYSQL_USER="${MYTOOLS_DB_USER:-root}"
MYSQL_PASSWORD="${MYTOOLS_DB_PASSWORD:-}"

[[ -n "$DRIVE_USERNAME" ]] || { echo "Missing MyTools username." >&2; exit 2; }
[[ -n "$DRIVE_ACCOUNTS" ]] || { echo "Missing RCLONE_DRIVE_ACCOUNTS." >&2; exit 2; }
[[ -n "$MYSQL_PASSWORD" ]] || { echo "Missing MYTOOLS_DB_PASSWORD." >&2; exit 2; }
[[ -f "$RCLONE_CONFIG" ]] || { echo "Rclone config does not exist." >&2; exit 2; }

available_remotes="$(/usr/local/bin/rclone listremotes --config "$RCLONE_CONFIG")"
IFS=',' read -r -a account_entries <<< "$DRIVE_ACCOUNTS"
upstreams=""

for entry in "${account_entries[@]}"; do
  remote_key="${entry%%=*}"
  display_name="${entry#*=}"
  [[ "$remote_key" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "Invalid rclone remote key." >&2; exit 2; }
  [[ -n "$display_name" && ${#display_name} -le 64 ]] || { echo "Invalid drive display name." >&2; exit 2; }
  grep -Fxq "${remote_key}:" <<< "$available_remotes" || {
    echo "Configured rclone remote is missing: ${remote_key}" >&2
    exit 3
  }
  upstreams+="${upstreams:+ }${remote_key}=${remote_key}:"
done

/usr/local/bin/rclone config create mytools-drive combine upstreams "$upstreams" \
  --config "$RCLONE_CONFIG" --non-interactive >/dev/null

mysql_args=(--protocol=TCP --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" my_tools)
user_id="$(MYSQL_PWD="$MYSQL_PASSWORD" mysql "${mysql_args[@]}" --batch --skip-column-names \
  --execute "SELECT id FROM t_user WHERE username = '$(printf '%s' "$DRIVE_USERNAME" | sed "s/'/''/g")' LIMIT 1")"
[[ "$user_id" =~ ^[1-9][0-9]*$ ]] || { echo "MyTools user was not found." >&2; exit 4; }

for entry in "${account_entries[@]}"; do
  remote_key="${entry%%=*}"
  display_name="${entry#*=}"
  escaped_name="$(printf '%s' "$display_name" | sed "s/'/''/g")"
  MYSQL_PWD="$MYSQL_PASSWORD" mysql "${mysql_args[@]}" --execute \
    "INSERT INTO drive_account (id, user_id, display_name, remote_key, read_only, enabled, status, create_time, update_time)
     VALUES (UUID_SHORT(), ${user_id}, '${escaped_name}', '${remote_key}', 1, 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
     ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), enabled = 1, status = 'ACTIVE', update_time = CURRENT_TIMESTAMP(6);"
done

echo "Rclone drive accounts synchronized."
