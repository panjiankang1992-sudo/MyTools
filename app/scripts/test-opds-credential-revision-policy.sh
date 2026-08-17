#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private opdsCredentialRevision: number = 0;' "$SOURCE"
grep -Fq '@State opdsCredentialBusy: boolean = false;' "$SOURCE"
grep -Fq 'private OpdsCredentialOperationCurrent(accountRevision: number, owner: string, credentialRevision: number,' \
  "$SOURCE"

for method in ToggleOpdsCredentialPanel SaveOpdsCredential ClearOpdsCredential; do
  block="$(sed -n "/private async ${method}(/,/^  }/p" "$SOURCE")"
  printf '%s\n' "$block" | grep -Fq 'credentialRevision' || {
    echo "$method does not allocate or capture a credential revision" >&2
    exit 1
  }
  printf '%s\n' "$block" | grep -Fq 'OpdsCredentialOperationCurrent' || {
    echo "$method does not reject stale credential results" >&2
    exit 1
  }
done

save_block="$(sed -n '/private async SaveOpdsCredential(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$save_block" | grep -Fq 'const requestedUrl = this.opdsUrl.trim();' || {
  echo 'credential save does not freeze the catalog URL' >&2
  exit 1
}
printf '%s\n' "$save_block" | grep -Fq 'const password = this.opdsPassword;' || {
  echo 'credential save does not freeze the password' >&2
  exit 1
}

echo 'OPDS credential revision policy tests passed'
