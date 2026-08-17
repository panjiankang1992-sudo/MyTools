#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BOOK_STORE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceCredentialStore.ets"
OPDS_STORE="$APP_DIR/entry/src/main/ets/features/reader/OpdsCredentialStore.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
SEARCH_ENGINE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
READER_ENGINE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"

for store in "$BOOK_STORE" "$OPDS_STORE"; do
  grep -Fq "selectScope(scope: string): void" "$store"
  grep -Fq "^account:v2:[a-f0-9]{64}$" "$store"
  grep -Fq "const alias = this.currentAlias();" "$store"
  grep -Fq "this.accountScope.substring('account:v2:'.length)" "$store"
  grep -Fq "旧凭据无法证明云账户归属，只在访客区继续使用原槽" "$store"
done

grep -Fq "mytools.book-source.credentials.v2.\${suffix}" "$BOOK_STORE"
grep -Fq "mytools.opds.basic.v2.\${suffix}" "$OPDS_STORE"
grep -Fq "this.bookSourceCredentialStore.selectScope(scope)" "$PAGE"
grep -Fq "this.opdsCredentialStore.selectScope(scope)" "$PAGE"
grep -Fq "this.OpdsCatalogPreferenceKey()" "$PAGE"
grep -Fq "opds_catalog_url_\${this.readerStorageScope.substring('account:v2:'.length)}" "$PAGE"
grep -Fq "new BookSourceSearchEngine(this.bookSourceCredentialStore)" "$PAGE"
grep -Fq "new BookSourceReaderEngine(this.remoteCoverCache, this.bookSourceCredentialStore)" "$PAGE"
grep -Fq "constructor(credentialStore: BookSourceCredentialStore)" "$SEARCH_ENGINE"
grep -Fq "constructor(imageCache: SafeRemoteCoverCache, credentialStore: BookSourceCredentialStore)" "$READER_ENGINE"

if grep -F "new BookSourceCredentialStore()" "$SEARCH_ENGINE" "$READER_ENGINE" >/dev/null; then
  echo "Book source engines must not create an unscoped credential store" >&2
  exit 1
fi

echo "Reader credential account scope policy tests passed"
