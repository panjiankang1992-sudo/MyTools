#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
INDEX_FILE="$ROOT_DIR/app/entry/src/main/ets/pages/Index.ets"

grep -Fq "mediaCatalogDirectoryParentId: string = '__media_root__'" "$INDEX_FILE"
grep -Fq "directoryId: '__media__', name: 'media'" "$INDEX_FILE"
grep -Fq '`media/${directory.parentDirectoryName}/${directory.name}`' "$INDEX_FILE"

echo "media catalog directory root policy passed"
