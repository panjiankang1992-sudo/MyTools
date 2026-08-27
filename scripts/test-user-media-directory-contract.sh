#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
migration_script="${project_root}/service/media-library-service/packages/media_migrate_legacy_items/1.1.0/scripts/main.py"
remount_script="${project_root}/deploy/scripts/remount-resource-storage.sh"

grep -Fq 'parts[5] == "media"' "${migration_script}"
grep -Fq 'resource/<username>/media/yyyyMM/yyyyMMdd' "${migration_script}"
grep -Fq '[[ -d "${resource_root}/${resource_username}/media" ]]' "${remount_script}"

if grep -Fq '[[ -d "${resource_root}/media" ]] ||' "${remount_script}"; then
  echo "legacy global media root must not satisfy the mounted layout contract" >&2
  exit 1
fi

echo "user media directory contract policy passed"
