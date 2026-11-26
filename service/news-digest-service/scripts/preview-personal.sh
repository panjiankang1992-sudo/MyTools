#!/usr/bin/env bash
# 生成自用新闻预览，仅使用临时内存数据库，不启动定时器或发送消息。
set -euo pipefail
service_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:?Usage: preview-personal.sh OUTPUT_DIRECTORY [SOURCES_JSON [REGION_REPORT_JSON]]}"
sources_file="${2:-$service_dir/examples/personal-sources.json}"
region_arguments=()
if [[ -n "${3:-}" ]]; then region_arguments=("$3"); fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
snapshot_dir="$(mktemp -d)"
trap 'rm -rf -- "$snapshot_dir"' EXIT

# 使用独立编译快照，避免其他 Maven 任务重编译时影响正在采集的预览进程。
mvn -q -f "$service_dir/pom.xml" test-compile dependency:build-classpath \
  -Dmdep.outputFile="$snapshot_dir/classpath.txt"
cp -R "$service_dir/target/classes" "$snapshot_dir/classes"
cp -R "$service_dir/target/test-classes" "$snapshot_dir/test-classes"
classpath="$snapshot_dir/classes:$snapshot_dir/test-classes:$(cat "$snapshot_dir/classpath.txt")"
"$java_bin" -Dnews.illustrated="${NEWS_ILLUSTRATED:-false}" -cp "$classpath" com.yuyutian.mytools.news.PersonalPreviewLauncher "$sources_file" "$output_dir" "${region_arguments[@]}"
