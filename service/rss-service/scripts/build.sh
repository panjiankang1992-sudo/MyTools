#!/usr/bin/env bash
set -euo pipefail
# 使用调用者配置的 Java 21，不读取生产环境文件。
service_root="$(cd "$(dirname "$0")/../.." && pwd)"
mvn -f "$service_root/feed-core/pom.xml" clean install
mvn -f "$service_root/rss-service/pom.xml" clean package
