#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
REPOSITORY="$APP_DIR/entry/src/main/ets/features/reader/ReaderRepository.ets"

grep -Fq "reader_snapshot_v2_\${scope}" "$REPOSITORY"
grep -Fq "scope === ReaderRepository.GUEST_SCOPE" "$REPOSITORY"
grep -Fq "ReaderRepository.LEGACY_SNAPSHOT_KEY" "$REPOSITORY"
grep -Fq "旧快照无法证明云账户归属，只允许迁入设备访客区" "$REPOSITORY"
grep -Fq "raw.length > 80 * 1024 * 1024" "$REPOSITORY"
grep -Fq "new ReaderSnapshotNormalizer()" "$REPOSITORY"
grep -Fq "10000 - bookmarks.length - annotations.length" "$REPOSITORY"
grep -Fq "restored[0].bookSourceUrl !== sourceUrl" "$REPOSITORY"
grep -Fq "this.SwitchReaderStorageScope(readerContext, this.localAccountScope)" "$PAGE"
grep -Fq "this.SwitchReaderStorageScope(readerContext, 'device:v2:guest')" "$PAGE"
grep -Fq "this.PrepareReadingSyncForAccount(this.localAccountScope)" "$PAGE"
grep -Fq "^account:v2:[a-f0-9]{64}$" "$PAGE"

if grep -F "this.PrepareReadingSyncForAccount(session.username)" "$PAGE" >/dev/null; then
  echo "Reader synchronization must not identify accounts by username alone" >&2
  exit 1
fi

echo "Reader account scope policy tests passed"
