#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
loader="$root_dir/app/entry/src/main/ets/features/reader/ReaderContentLoader.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private activeRequest?: http.HttpRequest = undefined;' "$loader"
grep -Fq 'cancel(): void {' "$loader"
grep -Fq 'this.activeRequest?.destroy();' "$loader"
grep -Fq 'this.assertNotCancelled();' "$loader"
grep -Fq 'if (this.activeRequest === request) this.activeRequest = undefined;' "$loader"

# 取消只负责中断请求，目录应等待流回调关闭文件后由加载流程清理。
cancel_body="$(sed -n '/  cancel(): void {/,/^  }/p' "$loader")"
if grep -Fq 'cleanupDirectory' <<<"$cancel_body"; then
  echo 'cancel must not remove a workspace while a stream may still own its file handle' >&2
  exit 1
fi

grep -Fq 'private readerOpeningLoader?: ReaderContentLoader = undefined;' "$page"
grep -Fq 'this.readerOpeningLoader?.cancel();' "$page"
grep -Fq 'if (this.readerOpeningLoader === loader) this.readerOpeningLoader = undefined;' "$page"

cancel_site_count="$(grep -Fc 'this.readerOpeningLoader?.cancel();' "$page")"
if [[ "$cancel_site_count" -lt 3 ]]; then
  echo 'reader loader cancellation must cover replacement, close, and page teardown' >&2
  exit 1
fi

echo 'reader loader cancellation policy tests passed'
