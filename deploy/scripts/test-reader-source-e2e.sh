#!/usr/bin/env bash
set -euo pipefail

backend_url="${BACKEND_URL:-http://127.0.0.1:23110}"
runtime_url="${READER_RUNTIME_URL:-http://127.0.0.1:23120}"
backend_env="${BACKEND_ENV:-/opt/yuyutian/app/MyTools/backend/mytools-prod.env}"
runtime_env="${RUNTIME_ENV:-/opt/yuyutian/app/MyTools/reader-runtime/runtime.env}"
user_id="${1:?user id is required}"
keyword="${2:-斗罗大陆}"
candidate_limit="${CANDIDATE_LIMIT:-40}"

set -a
source "$backend_env"
source "$runtime_env"
set +a

token="$(MYSQL_PWD="$MYTOOLS_DB_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -B my_tools \
  -e "SELECT access_token FROM t_token WHERE user_id=$user_id AND status='ACTIVE' ORDER BY update_time DESC LIMIT 1")"
if [[ -z "$token" ]]; then
  echo "No active access token for user $user_id" >&2
  exit 2
fi

result_file="$(mktemp)"
trap 'rm -f "$result_file"' EXIT

start_response="$(curl --max-time 20 -fsS -X POST "$backend_url/api/app/v1/reader/source-search" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg keyword "$keyword" '{keyword:$keyword,page:1}')")"
task_id="$(jq -r '.data.taskId // empty' <<<"$start_response")"
if [[ -z "$task_id" ]]; then
  echo "Failed to start source search" >&2
  exit 3
fi

offset=0
deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
  response="$(curl --max-time 20 -fsS \
    "$backend_url/api/app/v1/reader/source-search/$task_id?offset=$offset&limit=200" \
    -H "Authorization: Bearer $token")"
  jq -c '.data.results[]?' <<<"$response" >>"$result_file"
  offset="$(jq -r '.data.nextOffset' <<<"$response")"
  result_count="$(jq -sc 'map(.sourceUrl) | unique | length' "$result_file")"
  status="$(jq -r '.data.status' <<<"$response")"
  if (( result_count >= candidate_limit )) || [[ "$status" != RUNNING ]]; then
    break
  fi
  sleep 2
done

checked=0
while IFS= read -r candidate; do
  ((checked += 1))
  source_url="$(jq -r '.sourceUrl' <<<"$candidate")"
  book_url="$(jq -r '.bookUrl' <<<"$candidate")"
  book_name="$(jq -r '.name' <<<"$candidate")"
  catalog="$(curl --max-time 12 -fsS -G "$runtime_url/reader3/getChapterList" \
    -H "X-Secure-Key: $READER_RUNTIME_SECURE_KEY" -H "X-User-NS: $user_id" \
    --data-urlencode "bookUrl=$book_url" --data-urlencode "bookSourceUrl=$source_url" 2>/dev/null || true)"
  chapter_count="$(jq -r 'if .isSuccess == true and (.data|type) == "array" then (.data|length) else 0 end' \
    <<<"${catalog:-{}}" 2>/dev/null || true)"
  chapter_count="${chapter_count:-0}"
  if (( chapter_count < 2 )); then
    continue
  fi
  chapter_url="$(jq -r '.data[0].url // empty' <<<"$catalog")"
  content="$(curl --max-time 12 -fsS -G "$runtime_url/reader3/getBookContent" \
    -H "X-Secure-Key: $READER_RUNTIME_SECURE_KEY" -H "X-User-NS: $user_id" \
    --data-urlencode "chapterUrl=$chapter_url" --data-urlencode "bookSourceUrl=$source_url" 2>/dev/null || true)"
  content_chars="$(jq -r 'if .isSuccess == true and (.data|type) == "string" then (.data|length) else 0 end' \
    <<<"${content:-{}}" 2>/dev/null || true)"
  content_chars="${content_chars:-0}"
  echo "candidate=$book_name chapters=$chapter_count contentChars=$content_chars source=$source_url" >&2
  if (( content_chars < 100 )); then
    continue
  fi
  backend_catalog="$(curl --max-time 30 -fsS -X POST "$backend_url/api/app/v1/reader/source-runtime/catalog" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg sourceUrl "$source_url" --arg bookUrl "$book_url" \
      '{sourceUrl:$sourceUrl,bookUrl:$bookUrl}')")"
  backend_chapters="$(jq -r 'if .code == "0000" and (.data.chapters|type) == "array" then (.data.chapters|length) else 0 end' \
    <<<"$backend_catalog")"
  backend_chapter_url="$(jq -r '.data.chapters[0].resourceUri // empty' <<<"$backend_catalog")"
  if (( backend_chapters < 2 )) || [[ -z "$backend_chapter_url" ]]; then
    continue
  fi
  backend_content="$(curl --max-time 30 -fsS -X POST "$backend_url/api/app/v1/reader/source-runtime/content" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg sourceUrl "$source_url" --arg chapterUrl "$backend_chapter_url" \
      '{sourceUrl:$sourceUrl,chapterUrl:$chapterUrl,chapterIndex:0}')")"
  backend_content_chars="$(jq -r 'if .code == "0000" and (.data.text|type) == "string" then (.data.text|length) else 0 end' \
    <<<"$backend_content")"
  backend_html_tags="$(jq -r '.data.text | test("<[^>]+>")' <<<"$backend_content")"
  if (( backend_content_chars < 100 )) || [[ "$backend_html_tags" != false ]]; then
    continue
  fi
  jq -nc --arg taskId "$task_id" --arg name "$book_name" --arg sourceUrl "$source_url" \
    --arg bookUrl "$book_url" --arg chapterUrl "$chapter_url" --argjson chapters "$chapter_count" \
    --argjson contentChars "$backend_content_chars" --argjson checked "$checked" \
    '{taskId:$taskId,name:$name,sourceUrl:$sourceUrl,bookUrl:$bookUrl,chapterUrl:$chapterUrl,
      chapters:$chapters,contentChars:$contentChars,htmlSanitized:true,checkedCandidates:$checked}'
  exit 0
done < <(jq -sc 'unique_by(.sourceUrl)[]' "$result_file" | head -n "$candidate_limit")

echo "No readable source found after checking $checked candidates" >&2
exit 4
