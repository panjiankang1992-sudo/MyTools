#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cache="$root_dir/app/entry/src/main/ets/shared/network/SafeRemoteCoverCache.ets"

grep -Fq "import util from '@ohos.util';" "$cache"
grep -Fq 'util.generateRandomUUID(true)' "$cache"
grep -Fq '缓存实例之间也必须隔离临时文件' "$cache"
grep -Fq '跨实例同键任务可能已提交相同有效缓存' "$cache"
grep -Fq 'if (fs.accessSync(finalPath) && fs.statSync(finalPath).size > 2)' "$cache"
grep -Fq '!Number.isSafeInteger(declared) || declared < 0' "$cache"
grep -Fq '响应长度无效' "$cache"
grep -Fq '导航事务拥有的可取消封面不共享Promise' "$cache"
grep -Fq 'SafeRemoteCoverCache.MAX_TOTAL_BYTES, '\''远程封面'\'', cancellation)' "$cache"

echo 'safe cover cache concurrency policy tests passed'
