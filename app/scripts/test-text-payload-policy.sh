#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/reader/TextPayloadPolicy.ets"
LOADER="$APP_DIR/entry/src/main/ets/features/reader/ReaderContentLoader.ets"

cp "$POLICY" "$TEST_DIR/TextPayloadPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/TextPayloadPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/text_payload_policy_test.cjs" "$TEST_DIR/output/TextPayloadPolicy.js"

grep -Fq 'this.validateDecodedText(decoded.text)' "$LOADER"
grep -Fq 'this.validateDecodedText(decodedChunk)' "$LOADER"
grep -Fq 'content: chapters.length === 0 ? decodedChunk.trim()' "$LOADER"
grep -Fq 'trim <= Math.min(3, bytes.length - 1)' "$LOADER"
grep -Fq 'this.validateTextChunkReference(reference)' "$LOADER"
grep -Fq 'reference.path !== `${this.activeDirectory}/book.txt`' "$LOADER"
grep -Fq 'reference.offset > reference.fileSize - reference.length' "$LOADER"
grep -Fq 'stat.size !== reference.fileSize' "$LOADER"
grep -Fq 'await this.fileFingerprintAsync(reference.path, reference.fileSize, isCancelled,' "$LOADER"
grep -Fq 'reference.searchIndexPath !== `${this.searchIndexDirectory}/txt-${reference.fileSize}-${reference.fingerprint}.json`' "$LOADER"
grep -Fq 'while (offset < size)' "$LOADER"
grep -Fq 'const temporaryPath = `${path}.tmp-${++this.searchIndexWriteRevision}`' "$LOADER"
grep -Fq 'if (fs.accessSync(temporaryPath)) fs.unlinkSync(temporaryPath)' "$LOADER"
grep -Fq "if (isCancelled()) throw new Error('全文搜索已取消')" "$LOADER"
grep -Fq '!isCancelled() && storedIndex === undefined' "$LOADER"
grep -Fq 'buildingBlooms.push(await this.buildBloomHex(lower, isCancelled))' "$LOADER"
grep -Fq 'index % 32768 === 0' "$LOADER"
grep -Fq 'private async fileFingerprintAsync(path: string, size: number' "$LOADER"
grep -Fq 'private async indexLargeText(path: string, size: number' "$LOADER"
grep -Fq "const fingerprint = await this.fileFingerprintAsync(path, size, isCancelled, '图书加载已取消')" "$LOADER"
grep -Fq 'return await this.indexLargeText(cachePath, stat.size, context)' "$LOADER"
grep -Fq "await this.copyLocalBookFile(uri, cachePath, stat.size, 'TXT')" "$LOADER"
grep -Fq 'private async copyLocalBookFile(sourcePath: string, targetPath: string, expectedSize: number' "$LOADER"
grep -Fq 'if (!completed) this.removePartialDownload(targetPath)' "$LOADER"
grep -Fq "await this.copyLocalBookFile(uri, path, stat.size, 'PDF')" "$LOADER"
grep -Fq 'await this.copyLocalBookFile(uri, archivePath, stat.size, format.toUpperCase())' "$LOADER"
grep -Fq 'const sourceFingerprint = await this.fileFingerprintAsync(sourcePath, expectedSize,' "$LOADER"
grep -Fq 'sourceFingerprint !== copiedFingerprint' "$LOADER"
grep -Fq 'const targetFingerprint = await this.fileFingerprintAsync(targetPath, expectedSize,' "$LOADER"
grep -Fq 'targetFingerprint !== copiedFingerprint' "$LOADER"

echo "Text payload integration policy tests passed"
