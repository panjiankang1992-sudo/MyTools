#!/usr/bin/env bash
set -euo pipefail

# 统一通过系统SSH配置、密钥文件或ssh-agent访问远端，不接收命令行密码。
ssh_host="${MYTOOLS_SSH_HOST:-ssh.yuyutian.top}"
ssh_port="${MYTOOLS_SSH_PORT:-22}"
ssh_user="${MYTOOLS_SSH_USER:-}"
ssh_identity="${MYTOOLS_SSH_IDENTITY:-}"

usage() {
  printf '%s\n' \
    'Usage:' \
    '  MYTOOLS_SSH_USER=<user> scripts/remote-ssh.sh connect' \
    '  MYTOOLS_SSH_USER=<user> scripts/remote-ssh.sh run -- <command> [args...]' \
    '  MYTOOLS_SSH_USER=<user> scripts/remote-ssh.sh upload <local-file> <remote-path>' \
    '' \
    'Optional: MYTOOLS_SSH_HOST, MYTOOLS_SSH_PORT, MYTOOLS_SSH_IDENTITY.'
}

if [[ -z "$ssh_user" ]]; then
  printf 'MYTOOLS_SSH_USER is required\n' >&2
  usage >&2
  exit 2
fi

ssh_options=(
  -p "$ssh_port"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=3
  -o StrictHostKeyChecking=accept-new
)
scp_options=(
  -P "$ssh_port"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
if [[ -n "$ssh_identity" ]]; then
  ssh_options+=(-i "$ssh_identity")
  scp_options+=(-i "$ssh_identity")
fi

action="${1:-}"
case "$action" in
  connect)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    exec ssh "${ssh_options[@]}" "${ssh_user}@${ssh_host}"
    ;;
  run)
    shift
    if [[ "${1:-}" == '--' ]]; then shift; fi
    [[ $# -gt 0 ]] || { usage >&2; exit 2; }
    exec ssh "${ssh_options[@]}" "${ssh_user}@${ssh_host}" -- "$@"
    ;;
  upload)
    [[ $# -eq 3 ]] || { usage >&2; exit 2; }
    local_file="$2"
    remote_path="$3"
    [[ -f "$local_file" ]] || { printf 'Local file not found: %s\n' "$local_file" >&2; exit 2; }
    exec scp "${scp_options[@]}" -- "$local_file" "${ssh_user}@${ssh_host}:${remote_path}"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
