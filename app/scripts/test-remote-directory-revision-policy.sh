#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

for field in toolDirectoryRevision remoteBookDirectoryRevision mediaDirectoryRevision; do
  grep -Fq "private ${field}: number = 0;" "$SOURCE" || {
    echo "missing $field" >&2
    exit 1
  }
done

for method in LoadToolFileDirectory LoadMediaDirectory; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'const sourceId = this.selectedMediaSourceId;' || {
    echo "$method does not freeze the source" >&2
    exit 1
  }
  printf '%s\n' "$block" | grep -Fq 'directoryRevision' || {
    echo "$method does not validate navigation revision" >&2
    exit 1
  }
done

book_block="$(sed -n '/private async LoadRemoteBookDirectory(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_block" | grep -Fq 'const sourceId = this.selectedRemoteBookSourceId;' || {
  echo 'LoadRemoteBookDirectory does not freeze the dedicated ebook source' >&2
  exit 1
}
printf '%s\n' "$book_block" | grep -Fq 'directoryRevision' || {
  echo 'LoadRemoteBookDirectory does not validate navigation revision' >&2
  exit 1
}

if sed -n '/private async LoadToolFileDirectory(/,/^  }/p' "$SOURCE" | grep -Fq 'this.toolLoading ||'; then
  echo 'tool navigation still rejects a newer request while loading' >&2
  exit 1
fi
if sed -n '/private async LoadRemoteBookDirectory(/,/^  }/p' "$SOURCE" | grep -Fq 'this.mediaLoading) return'; then
  echo 'book navigation still rejects a newer request while loading' >&2
  exit 1
fi
if sed -n '/private async LoadMediaDirectory(/,/^  }/p' "$SOURCE" | grep -Fq 'this.mediaLoading ||'; then
  echo 'media navigation still rejects a newer request while loading' >&2
  exit 1
fi

echo 'Remote directory revision policy tests passed'
