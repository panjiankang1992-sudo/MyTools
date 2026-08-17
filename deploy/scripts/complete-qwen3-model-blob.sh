#!/bin/bash
set -euo pipefail

digest="8e5b594748dc24e34e8c258bb5ae0352f60dbe3f58cd4e1e34d87dead4b67648"
blob_directory="/home/pankang/.ollama/models/blobs"
partial_path="$blob_directory/sha256-$digest-partial"
final_path="$blob_directory/sha256-$digest"
missing_offset=2265730214
missing_size=205975474
connection_count=16
chunk_size=$(((missing_size + connection_count - 1) / connection_count))
download_directory="$(mktemp -d /tmp/qwen3-blob-range.XXXXXX)"
blob_url="https://registry.ollama.ai/v2/huihui_ai/qwen3-vl-abliterated/blobs/sha256:$digest"

# 并行下载唯一缺失的大分片。
for ((index = 0; index < connection_count; index++)); do
  relative_start=$((index * chunk_size))
  if ((relative_start >= missing_size)); then
    break
  fi
  relative_end=$((relative_start + chunk_size - 1))
  if ((relative_end >= missing_size)); then
    relative_end=$((missing_size - 1))
  fi
  absolute_start=$((missing_offset + relative_start))
  absolute_end=$((missing_offset + relative_end))
  curl --fail --location --retry 5 --range "$absolute_start-$absolute_end" \
    "$blob_url" --output "$download_directory/$index.part" &
done
wait

# 按原始偏移写回稀疏文件，并校验最终模型摘要。
for ((index = 0; index < connection_count; index++)); do
  part_path="$download_directory/$index.part"
  if [[ ! -f "$part_path" ]]; then
    continue
  fi
  relative_start=$((index * chunk_size))
  dd if="$part_path" of="$partial_path" bs=4M seek=$((missing_offset + relative_start)) \
    oflag=seek_bytes conv=notrunc status=none
done

actual_digest="$(sha256sum "$partial_path" | awk '{print $1}')"
if [[ "$actual_digest" != "$digest" ]]; then
  echo "Model digest mismatch: $actual_digest" >&2
  exit 1
fi
mv "$partial_path" "$final_path"
ollama pull huihui_ai/qwen3-vl-abliterated:4b
