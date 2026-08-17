#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private opdsNavigationRevision: number = 0;' "$SOURCE"
grep -Fq '@State opdsLoading: boolean = false;' "$SOURCE"
block="$(sed -n '/private async LoadOpdsCatalog(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$block" | grep -Fq 'const navigationRevision = ++this.opdsNavigationRevision;' || {
  echo 'OPDS navigation does not allocate a new revision' >&2
  exit 1
}
printf '%s\n' "$block" | grep -Fq 'const requestedUrl = url.trim();' || {
  echo 'OPDS navigation does not freeze the requested URL' >&2
  exit 1
}
printf '%s\n' "$block" | grep -Fq 'this.OpdsOperationCurrent(revision, owner, navigationRevision)' || {
  echo 'OPDS navigation does not validate its revision' >&2
  exit 1
}
if printf '%s\n' "$block" | grep -Fq 'if (this.mediaLoading) return'; then
  echo 'OPDS still rejects a newer navigation while loading' >&2
  exit 1
fi
cover_block="$(sed -n '/private async CacheOpdsCovers(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$cover_block" | grep -Fq 'entries: OpdsBookEntry[]' || {
  echo 'OPDS cover caching still mutates the global catalog directly' >&2
  exit 1
}

echo 'OPDS navigation revision policy tests passed'
