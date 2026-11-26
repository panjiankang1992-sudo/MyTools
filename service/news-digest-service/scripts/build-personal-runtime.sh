#!/usr/bin/env bash
# 打包可独立执行的自用采集、DSH 分析与发送脚本；输出目录必须是新目录。
set -euo pipefail
service_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output="${1:?Usage: build-personal-runtime.sh NEW_OUTPUT_DIRECTORY [VERSION]}"
version="${2:-1.5.3}"
mvn -q -f "$service_dir/pom.xml" package -DskipTests
"${PYTHON:-python3}" - "$service_dir" "$output" "$version" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import sys

service, output = map(Path, sys.argv[1:3])
version = sys.argv[3]
output.mkdir(mode=0o700)
(output / "scripts").mkdir()
shutil.copyfile(service / "target/news-digest-service-0.1.0-SNAPSHOT.jar", output / "news-digest-service.jar")
shutil.copyfile(service / "examples/personal-sources.json", output / "sources.json")
shutil.copyfile(service / "packages/news_resolve_region/1.2.0/scripts/main.py", output / "region_probe.py")
for name in ("weekly_job.py", "weekly_report.py", "weekly_sources.py", "personal_daily.py", "personal_report.py", "public_news.py", "publish_rss.py", "rss_article_details.py", "extract_article.py", "news_analysis.py", "news_infographic.py", "send_personal_mail.py", "dsh-news.patch.yml", "dsh-news-session.patch.yml", "dsh_news_session.mjs", "dsh_news_workspace.mjs", "requirements-personal-report.txt"):
    shutil.copyfile(service / "scripts" / name, output / "scripts" / name)
shutil.copytree(service / "skills/analyze-weekly-news", output / "skills/analyze-weekly-news")
shutil.copytree(service / "skills/analyze-daily-news", output / "skills/analyze-daily-news")
shutil.copytree(service / "skills/analyze-rss-articles", output / "skills/analyze-rss-articles")
files = {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(output.rglob("*")) if path.is_file()}
(output / "runtime-manifest.json").write_text(json.dumps({"version": "news-personal-" + version, "files": files}, indent=2) + "\n")
print(json.dumps({"runtime": str(output.resolve()), "files": len(files)}))
PY
