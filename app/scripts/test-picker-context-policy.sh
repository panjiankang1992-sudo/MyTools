#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private pageLifecycleRevision: number = 0;' "$SOURCE"
grep -Fq 'private PickerContextCurrent(accountRevision: number, owner: string, lifecycleRevision: number): boolean' \
  "$SOURCE"

for method in SelectCopilotTextAttachment ImportBookSources DownloadRemoteFileWithPicker \
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

# 本地图书选择结果来自系统授权；页面重建后仍应合并用户已经明确选择的文件。
local_book_block="$(sed -n '/private async PickLocalBooks(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$local_book_block" | grep -Fq 'const selected = await this.localBookPicker.select' || {
  echo 'PickLocalBooks does not use the account-scoped local picker' >&2
  exit 1
}
printf '%s\n' "$local_book_block" | grep -Fq 'const merged = new Map<string, Book>();' || {
  echo 'PickLocalBooks does not preserve existing shelf entries' >&2
  exit 1
}

for method in CreateToolArchive ExtractToolArchive ExportArchiveItem; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'lifecycleRevision !== this.pageLifecycleRevision' || {
    echo "$method does not reject stale lifecycle results" >&2
    exit 1
  }
done

echo 'Picker context policy tests passed'
