#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private accountOperationRevision: number = 0;' "$SOURCE"
grep -Fq 'private AccountOperationCurrent(revision: number, owner: string): boolean' "$SOURCE"
grep -Fq 'this.accountOperationRevision++;' "$SOURCE"
grep -Fq 'this.activeDownloadCancellation?.cancel();' "$SOURCE"

for method in LoadMarketApps LoadMarketDetail LoadToolFileDirectory LoadDeviceSessions LoadRemoteBookDirectory \
  LoadMediaSources LoadMediaDirectory UploadToolFile ConfirmToolOperation RevokeDeviceSession \
  RevokeOtherSessions; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'const revision = this.accountOperationRevision;' || {
    echo "$method does not capture the account revision" >&2
    exit 1
  }
  printf '%s\n' "$block" | grep -Fq 'this.AccountOperationCurrent(revision, owner)' || {
    echo "$method does not validate the account revision" >&2
    exit 1
  }
done

opds_block="$(sed -n '/private async LoadOpdsCatalog(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$opds_block" | grep -Fq 'const revision = this.accountOperationRevision;' || {
  echo 'LoadOpdsCatalog does not capture the account revision' >&2
  exit 1
}
printf '%s\n' "$opds_block" | grep -Fq 'this.OpdsOperationCurrent(revision, owner, navigationRevision)' || {
  echo 'LoadOpdsCatalog does not validate account and navigation revisions' >&2
  exit 1
}

download_block="$(sed -n '/private async DownloadRemoteFileWithPicker(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$download_block" | grep -Fq 'const revision = this.accountOperationRevision;' || {
  echo 'download does not capture the account revision' >&2
  exit 1
}
printf '%s\n' "$download_block" | grep -Fq 'cancellation.isCancelled()) return;' || {
  echo 'download progress callback does not reject stale or cancelled updates' >&2
  exit 1
}

echo 'Account operation revision policy tests passed'
