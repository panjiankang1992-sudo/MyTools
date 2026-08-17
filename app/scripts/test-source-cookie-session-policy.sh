#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/SourceCookieSessionPolicy.ets"
store="$root_dir/app/entry/src/main/ets/features/reader/BookSourceCredentialStore.ets"
search_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
reader_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/SourceCookieSessionPolicy.ts"
node "$tsc_bin" "$test_dir/SourceCookieSessionPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/SourceCookieSessionPolicy.js" \
  "$root_dir/app/tests/source_cookie_session_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).SourceCookieSessionPolicy);
NODE

grep -Fq 'private readonly sessionCookies: Map<string, string>' "$store"
grep -Fq 'if (this.accountScope !== scope) {' "$store"
grep -Fq 'this.scopeRevision++;' "$store"
grep -Fq 'captureSessionCookies(sourceUrl: string, targetUrl: string, value: string, revision: number)' "$store"
grep -Fq "if (sessionCookie !== undefined) headers['Cookie'] = sessionCookie;" "$store"
grep -Fq 'if (record !== undefined && record.origin === targetOrigin)' "$store"
grep -Fq "this.cookiePolicy.merge(this.sessionCookies.get(key) ?? '', value)" "$store"
grep -Fq 'this.sessionCookies.delete(key);' "$store"
grep -Fq 'private scopeRevision: number = 0;' "$store"
grep -Fq 'currentScopeRevision(): number' "$store"
grep -Fq 'scopeRevisionCurrent(revision: number): boolean' "$store"
grep -Fq 'if (!this.scopeRevisionCurrent(revision)) return {};' "$store"
grep -Fq 'key.startsWith(`${normalizedSourceUrl}|`)' "$store"
grep -Fq 'credentialRevision);' "$search_engine"
grep -Fq 'credentialRevision);' "$reader_engine"
test "$(grep -Fc 'scopeRevisionCurrent(credentialRevision)' "$reader_engine")" -ge 2

echo 'source cookie session policy tests passed'
