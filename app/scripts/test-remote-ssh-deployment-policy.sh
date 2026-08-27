#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
remote_ssh="$root_dir/scripts/remote-ssh.sh"
deploy_doc="$root_dir/docs/DEPLOY.md"

grep -Fq 'ssh.yuyutian.top' "$remote_ssh"
grep -Fq 'BatchMode=yes' "$remote_ssh"
grep -Fq 'StrictHostKeyChecking=accept-new' "$remote_ssh"
grep -Fq 'MYTOOLS_SSH_IDENTITY' "$remote_ssh"
grep -Fq 'scripts/remote-ssh.sh run' "$deploy_doc"
grep -Fq '/opt/yuyutian/mytools/releases/current' "$deploy_doc"
if grep -Fq '/opt/code/MyTools' "$deploy_doc"; then
  echo 'Remote deployment must not assume a Git checkout under /opt/code' >&2
  exit 1
fi

if grep -Eqi 'feishu|lark' "$root_dir/scripts/remote-ssh.sh" "$deploy_doc"; then
  echo 'Remote deployment must not depend on Feishu or Lark' >&2
  exit 1
fi
if grep -Eqi '<password>|MYTOOLS_SSH_PASSWORD|password=' "$remote_ssh"; then
  echo 'Remote SSH entry must not accept passwords from argv or environment' >&2
  exit 1
fi
if find "$root_dir/scripts" -maxdepth 1 -type f -name 'ssh_*' -print -quit | grep -q .; then
  echo 'Legacy password-based SSH helpers must stay removed' >&2
  exit 1
fi

echo 'remote SSH deployment policy tests passed'
