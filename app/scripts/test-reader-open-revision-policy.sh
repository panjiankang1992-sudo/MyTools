#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private readerOpenRevision: number = 0;' "$SOURCE"
open_block="$(sed -n '/private async OpenBook(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$open_block" | grep -Fq 'const openRevision = ++this.readerOpenRevision;' || {
  echo 'reader open does not allocate an operation revision' >&2
  exit 1
}
printf '%s\n' "$open_block" | grep -Fq 'const accountRevision = this.accountOperationRevision;' || {
  echo 'reader open does not capture the account revision' >&2
  exit 1
}
printf '%s\n' "$open_block" | grep -Fq 'const loader = new ReaderContentLoader();' || {
  echo 'reader open does not own an isolated content-loader workspace' >&2
  exit 1
}
printf '%s\n' "$open_block" | grep -Fq 'ApplyOpenedReaderDocument' || {
  echo 'reader documents are not committed through the guarded boundary' >&2
  exit 1
}
printf '%s\n' "$open_block" | grep -Fq 'OpenPreparedPdf' || {
  echo 'prepared PDFs are not committed through the guarded boundary' >&2
  exit 1
}
grep -Fq 'private ReaderOpenCurrent(openRevision: number, accountRevision: number, owner: string,' "$SOURCE"
grep -Fq 'private AdoptReaderContentLoader(loader: ReaderContentLoader): void' "$SOURCE"
grep -Fq 'this.readerContentLoader.cleanup();' "$SOURCE"
grep -Fq 'this.readerContentLoader = loader;' "$SOURCE"
close_block="$(sed -n '/private CloseReader(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$close_block" | grep -Fq 'this.readerOpenRevision++;' || {
  echo 'closing the reader does not invalidate an in-flight open' >&2
  exit 1
}

echo 'Reader open revision policy tests passed'
