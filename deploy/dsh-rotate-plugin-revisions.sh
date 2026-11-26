#!/usr/bin/env bash
# 原地升级 DSH 后旋转插件包 rev，并重启服务与刷新上游 Cookie。
#
# 背景：组合包 URL 的 rev 由 client bundle 的 mtime/ctime/size 哈希而来，响应是
# 一年 immutable。若替换文件后没有立刻重启 dsh.service，旧进程会用新 rev 提供旧 body，
# 期间加载过页面的客户端会把旧代码按新 URL 缓存下来，之后只能靠换 URL 才能更新。
#
# 用法：sudo deploy/dsh-rotate-plugin-revisions.sh --apply
# 不带 --apply 时只统计和展示将要 touch 的文件。
set -euo pipefail

apply=0
[[ "${1:-}" == "--apply" ]] && apply=1

roots=(
  "${DSH_INSTALL_ROOT:-/opt/nodejs/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai}"
  "${DSH_PROFILE_ROOT:-/home/pankang/.dsh/profiles/web/node_modules}"
)

bundles=()
for root in "${roots[@]}"; do
  [[ -d "$root" ]] || continue
  while IFS= read -r file; do bundles+=("$file"); done < <(find "$root" -path "*/lib/client*.js" -type f | sort)
done

echo "client bundles: ${#bundles[@]}"
if (( ! apply )); then
  if ((${#bundles[@]} > 0)); then printf '%s\n' "${bundles[@]}" | head -5; fi
  echo "dry-run: pass --apply to rotate revisions"
  exit 0
fi

[[ "${EUID}" -eq 0 ]] || { echo "rotate revisions requires root" >&2; exit 1; }
((${#bundles[@]} > 0)) || { echo "no client bundles found" >&2; exit 1; }

touch "${bundles[@]}"
systemctl restart dsh
systemctl start mytools-dsh-cookie.service || true
echo "rotated ${#bundles[@]} bundles, restarted dsh and refreshed the upstream cookie"
