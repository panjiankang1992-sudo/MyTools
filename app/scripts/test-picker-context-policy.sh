#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private pageLifecycleRevision: number = 0;' "$SOURCE"
grep -Fq 'private PickerContextCurrent(accountRevision: number, owner: string, lifecycleRevision: number): boolean' \
  "$SOURCE"

for method in SelectCopilotTextAttachment ImportBookSources PickLocalBooks DownloadRemoteFileWithPicker \
  UploadToolFile; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'const lifecycleRevision = this.pageLifecycleRevision;' || {
    echo "$method does not capture the page lifecycle" >&2
    exit 1
  }
  printf '%s\n' "$block" | grep -Fq 'PickerContextCurrent' || {
    echo "$method does not validate picker context" >&2
    exit 1
  }
done

for method in CreateToolArchive ExtractToolArchive ExportArchiveItem; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'lifecycleRevision !== this.pageLifecycleRevision' || {
    echo "$method does not reject stale lifecycle results" >&2
    exit 1
  }
done

echo 'Picker context policy tests passed'
