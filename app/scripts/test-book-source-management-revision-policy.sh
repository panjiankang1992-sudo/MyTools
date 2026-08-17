#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private sourceManagementRevision: number = 0;' "$SOURCE"
grep -Fq '@State sourceCredentialBusy: boolean = false;' "$SOURCE"
grep -Fq 'private SourceManagementCurrent(accountRevision: number, owner: string, managementRevision: number)' \
  "$SOURCE"

for method in OpenBookSourceCredential SaveBookSourceCredential ClearBookSourceCredential DeleteBookSource \
  CheckBookSourceHealth; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'managementRevision' || {
    echo "$method does not capture the source-management revision" >&2
    exit 1
  }
  printf '%s\n' "$block" | grep -Eq 'Source(Management|Credential)Current' || {
    echo "$method does not reject stale source-management results" >&2
    exit 1
  }
done

save_block="$(sed -n '/private async SaveBookSourceCredential(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$save_block" | grep -Fq 'const header = this.sourceCredentialHeader;' || {
  echo 'credential save does not freeze the header' >&2
  exit 1
}
printf '%s\n' "$save_block" | grep -Fq 'const value = this.sourceCredentialValue;' || {
  echo 'credential save does not freeze the secret' >&2
  exit 1
}

echo 'Book-source management revision policy tests passed'
