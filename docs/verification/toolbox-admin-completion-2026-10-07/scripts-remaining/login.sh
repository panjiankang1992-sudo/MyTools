#!/usr/bin/env bash
# 在指定实例上完成管理员登录（CSRF → 登录），把 Cookie 罐与 CSRF 令牌留到工作目录。
# 用法：bash login.sh <BASE_URL> <WORK_DIR>
set -euo pipefail

BASE="${1:?BASE_URL required}"
DIR="${2:?WORK_DIR required}"
mkdir -p "$DIR"
JAR="$DIR/cookies.txt"
rm -f "$JAR"

curl -s -c "$JAR" "$BASE/admin/v1/auth/csrf" > "$DIR/csrf.json"
NONCE=$(jq -r '.data.csrfToken' "$DIR/csrf.json")
curl -s -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
  -H "X-Toolbox-Admin-Csrf: $NONCE" \
  -d '{"account":"local-admin","password":"local-admin"}' \
  "$BASE/admin/v1/auth/login" > "$DIR/login.json"
CSRF=$(jq -r '.data.csrfToken' "$DIR/login.json")
printf '%s' "$CSRF" > "$DIR/csrf.txt"
echo "login code=$(jq -r '.code' "$DIR/login.json") user=$(jq -r '.data.username' "$DIR/login.json")"
