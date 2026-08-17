#!/usr/bin/env bash
# 将 MyTools 与 DownloadBot 的生产存储根目录从旧路径切换到新路径。

set -Eeuo pipefail

OLD_ROOT="${OLD_ROOT:-/opt/custom/OpenClaw}"
NEW_ROOT="${NEW_ROOT:-/opt/extend/resource}"
MYTOOLS_ENV_FILE="${MYTOOLS_ENV_FILE:-/opt/yuyutian/app/MyTools/backend/mytools-prod.env}"
DOWNLOADBOT_DIR="${DOWNLOADBOT_DIR:-/opt/code/DownloadBot}"
DOWNLOADBOT_CONFIG="${DOWNLOADBOT_CONFIG:-${DOWNLOADBOT_DIR}/configs/config.yaml}"
DOWNLOADBOT_UNIT_FILE="${DOWNLOADBOT_UNIT_FILE:-/etc/systemd/system/downloadbot.service}"
MYTOOLS_SERVICE="${MYTOOLS_SERVICE:-mytools-backend.service}"
DOWNLOADBOT_SERVICE="${DOWNLOADBOT_SERVICE:-downloadbot.service}"
BACKUP_BASE="${BACKUP_BASE:-/opt/yuyutian/app/MyTools/backups/resource-root-migration}"
APPLY=false
RESTART_SERVICES=true
BACKUP_DIR=''
SERVICES_STOPPED=false
DATABASE_COMMITTED=false

usage() {
    printf '%s\n' \
        '用法：sudo ./scripts/migrate-resource-root.sh [--apply] [--skip-restart]' \
        '' \
        '默认只执行预检并显示数据库中待更新的数量；传入 --apply 才会修改。' \
        '' \
        '可覆盖的环境变量：' \
        '  OLD_ROOT              默认 /opt/custom/OpenClaw' \
        '  NEW_ROOT              默认 /opt/extend/resource' \
        '  MYTOOLS_ENV_FILE      MyTools 生产环境文件' \
        '  DOWNLOADBOT_DIR       DownloadBot 部署目录' \
        '  BACKUP_BASE           配置和数据库备份根目录'
}

log() {
    printf '[resource-root] %s\n' "$*"
}

die() {
    printf '[resource-root] 错误：%s\n' "$*" >&2
    exit 1
}

while (($# > 0)); do
    case "$1" in
        --apply)
            APPLY=true
            ;;
        --skip-restart)
            RESTART_SERVICES=false
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "未知参数：$1"
            ;;
    esac
    shift
done

for command in mysql mysqldump python3 systemctl realpath; do
    command -v "$command" >/dev/null 2>&1 || die "缺少命令：$command"
done

[[ $EUID -eq 0 ]] || die '必须使用 root 执行，以便备份配置并安全重启服务。'
[[ "$OLD_ROOT" =~ ^/[A-Za-z0-9._/-]+$ ]] || die 'OLD_ROOT 不是安全的绝对路径。'
[[ "$NEW_ROOT" =~ ^/[A-Za-z0-9._/-]+$ ]] || die 'NEW_ROOT 不是安全的绝对路径。'
[[ "$OLD_ROOT" != "$NEW_ROOT" ]] || die '新旧根目录不能相同。'
[[ -d "$NEW_ROOT" ]] || die "新根目录不存在：$NEW_ROOT"
[[ "$(realpath -m "$NEW_ROOT")" == "$NEW_ROOT" ]] || die 'NEW_ROOT 必须是规范化绝对路径。'

for category in ebook big_media media; do
    [[ -d "$NEW_ROOT/$category" ]] || die "新根目录缺少分类目录：$NEW_ROOT/$category"
done
[[ -f "$MYTOOLS_ENV_FILE" ]] || die "MyTools 环境文件不存在：$MYTOOLS_ENV_FILE"
[[ -f "$DOWNLOADBOT_CONFIG" ]] || die "DownloadBot 配置不存在：$DOWNLOADBOT_CONFIG"
[[ -f "$DOWNLOADBOT_UNIT_FILE" ]] || die "DownloadBot systemd 单元不存在：$DOWNLOADBOT_UNIT_FILE"

# 只在当前进程加载凭据，禁止输出环境文件内容。
set -a
# shellcheck disable=SC1090
source "$MYTOOLS_ENV_FILE"
if [[ -f "$DOWNLOADBOT_DIR/.env" ]]; then
    # shellcheck disable=SC1091
    source "$DOWNLOADBOT_DIR/.env"
fi
set +a

MYSQL_HOST="${MYTOOLS_DB_HOST:-127.0.0.1}"
MYSQL_PORT="${MYTOOLS_DB_PORT:-3306}"
MYSQL_USER="${MYTOOLS_DB_USER:-root}"
MYSQL_PASSWORD="${MYTOOLS_DB_PASSWORD:-}"
[[ -n "$MYSQL_PASSWORD" ]] || die 'MYTOOLS_DB_PASSWORD 未配置。'
MYSQL_CONNECTION_ARGS=(--protocol=TCP --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER")

mysql_query() {
    MYSQL_PWD="$MYSQL_PASSWORD" mysql "${MYSQL_CONNECTION_ARGS[@]}" --batch --skip-column-names --execute "$1"
}

table_exists() {
    local schema="$1"
    local table="$2"
    [[ "$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${schema}' AND table_name='${table}'")" == '1' ]]
}

path_count() {
    local schema="$1"
    local table="$2"
    local column="$3"
    mysql_query "SELECT COUNT(*) FROM ${schema}.${table} WHERE ${column}='${OLD_ROOT}' OR ${column} LIKE CONCAT('${OLD_ROOT}','/%')"
}

update_file_state_batches() {
    local deleted_value="$1"
    shift
    local ids=("$@")
    local offset joined
    for ((offset = 0; offset < ${#ids[@]}; offset += 500)); do
        local batch=("${ids[@]:offset:500}")
        joined="$(IFS=,; printf '%s' "${batch[*]}")"
        mysql_query "UPDATE my_tools.local_file SET deleted=${deleted_value},update_time=CURRENT_TIMESTAMP WHERE id IN (${joined}) AND deleted<>${deleted_value}"
    done
}

reconcile_local_file_state() {
    local restored_ids=()
    local missing_ids=()
    local id path
    while IFS=$'\t' read -r id path; do
        [[ "$id" =~ ^[0-9]+$ ]] || die 'local_file 返回了无效主键。'
        [[ "$path" == "$NEW_ROOT/"* ]] || die "local_file 路径越界：$path"
        if [[ -f "$path" ]]; then restored_ids+=("$id"); else missing_ids+=("$id"); fi
    done < <(mysql_query "SELECT id,file_path FROM my_tools.local_file WHERE file_path LIKE CONCAT('${NEW_ROOT}','/%') ORDER BY id")
    update_file_state_batches 0 "${restored_ids[@]}"
    update_file_state_batches 1 "${missing_ids[@]}"
    log "MyTools 文件状态核对：存在 ${#restored_ids[@]} 条，缺失 ${#missing_ids[@]} 条"
}

mysql_query 'SELECT 1' >/dev/null || die '无法连接 MySQL。'
table_exists my_tools local_directory || die '缺少 my_tools.local_directory。'
table_exists my_tools local_file || die '缺少 my_tools.local_file。'
table_exists downloadbot assets || die '缺少 downloadbot.assets。'

log "迁移路径：$OLD_ROOT -> $NEW_ROOT"
log "MyTools 目录记录：$(path_count my_tools local_directory directory_path)"
log "MyTools 文件记录：$(path_count my_tools local_file file_path)"
log "DownloadBot 资源记录：$(path_count downloadbot assets path)"
if table_exists downloadbot pikpak_transfers; then
    log "DownloadBot PikPak 本地记录：$(path_count downloadbot pikpak_transfers local_path)"
fi
if table_exists my_tools media_package; then
    log "MyTools 媒体包记录：$(path_count my_tools media_package directory_path)"
fi

if [[ "$APPLY" != true ]]; then
    log '预检完成；未修改任何配置或数据。确认后使用 --apply 执行。'
    exit 0
fi

MIGRATION_ID="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$BACKUP_BASE/$MIGRATION_ID"
install -d -m 0700 "$BACKUP_DIR"

backup_file() {
    local source="$1"
    local label="$2"
    cp -a "$source" "$BACKUP_DIR/$label"
}

replace_root_in_file() {
    local target="$1"
    python3 - "$target" "$OLD_ROOT" "$NEW_ROOT" <<'PY'
import os
import pathlib
import sys
import tempfile

path = pathlib.Path(sys.argv[1])
old = sys.argv[2]
new = sys.argv[3]
text = path.read_text(encoding="utf-8")
if old not in text:
    if new in text:
        raise SystemExit(0)
    raise SystemExit(f"配置中未找到新旧存储根目录：{path}")
updated = text.replace(old, new)
stat = path.stat()
fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(updated)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, stat.st_mode)
    os.chown(temporary, stat.st_uid, stat.st_gid)
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
}

upsert_env() {
    local target="$1"
    local key="$2"
    local value="$3"
    python3 - "$target" "$key" "$value" <<'PY'
import os
import pathlib
import re
import sys
import tempfile

path = pathlib.Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]
text = path.read_text(encoding="utf-8")
line = f"{key}={value}"
pattern = re.compile(rf"^{re.escape(key)}=.*$", re.MULTILINE)
updated = pattern.sub(line, text) if pattern.search(text) else text.rstrip("\n") + "\n" + line + "\n"
stat = path.stat()
fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(updated)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, stat.st_mode)
    os.chown(temporary, stat.st_uid, stat.st_gid)
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
}

log "备份配置和数据库到：$BACKUP_DIR"
backup_file "$MYTOOLS_ENV_FILE" mytools-prod.env
backup_file "$DOWNLOADBOT_CONFIG" downloadbot-config.yaml
backup_file "$DOWNLOADBOT_UNIT_FILE" downloadbot.service

MYTOOLS_TABLES=(local_directory local_file)
if table_exists my_tools media_package; then MYTOOLS_TABLES+=(media_package); fi
DOWNLOADBOT_TABLES=(assets)
if table_exists downloadbot pikpak_transfers; then DOWNLOADBOT_TABLES+=(pikpak_transfers); fi
MYSQL_PWD="$MYSQL_PASSWORD" mysqldump "${MYSQL_CONNECTION_ARGS[@]}" --single-transaction --skip-lock-tables \
    --no-tablespaces my_tools "${MYTOOLS_TABLES[@]}" >"$BACKUP_DIR/my_tools.sql"
MYSQL_PWD="$MYSQL_PASSWORD" mysqldump "${MYSQL_CONNECTION_ARGS[@]}" --single-transaction --skip-lock-tables \
    --no-tablespaces downloadbot "${DOWNLOADBOT_TABLES[@]}" >"$BACKUP_DIR/downloadbot.sql"
chmod 0600 "$BACKUP_DIR"/*.sql

restore_after_error() {
    local exit_code=$?
    if [[ $exit_code -eq 0 ]]; then return; fi
    trap - ERR EXIT
    if [[ "$SERVICES_STOPPED" == true && "$DATABASE_COMMITTED" != true && -n "$BACKUP_DIR" ]]; then
        cp -a "$BACKUP_DIR/mytools-prod.env" "$MYTOOLS_ENV_FILE" 2>/dev/null || true
        cp -a "$BACKUP_DIR/downloadbot-config.yaml" "$DOWNLOADBOT_CONFIG" 2>/dev/null || true
        cp -a "$BACKUP_DIR/downloadbot.service" "$DOWNLOADBOT_UNIT_FILE" 2>/dev/null || true
        systemctl daemon-reload 2>/dev/null || true
    fi
    if [[ "$SERVICES_STOPPED" == true ]]; then
        systemctl restart "$MYTOOLS_SERVICE" 2>/dev/null || true
        systemctl restart "$DOWNLOADBOT_SERVICE" 2>/dev/null || true
    fi
    printf '[resource-root] 执行失败；配置和数据库备份位于：%s\n' "$BACKUP_DIR" >&2
    exit "$exit_code"
}
trap restore_after_error ERR EXIT

if systemctl is-active --quiet "$DOWNLOADBOT_SERVICE"; then systemctl stop "$DOWNLOADBOT_SERVICE"; fi
if systemctl is-active --quiet "$MYTOOLS_SERVICE"; then systemctl stop "$MYTOOLS_SERVICE"; fi
SERVICES_STOPPED=true

replace_root_in_file "$DOWNLOADBOT_CONFIG"
replace_root_in_file "$DOWNLOADBOT_UNIT_FILE"
upsert_env "$MYTOOLS_ENV_FILE" FILE_SCAN_PATH "$NEW_ROOT"
upsert_env "$MYTOOLS_ENV_FILE" FILE_SCAN_THUMBNAIL_PATH "$NEW_ROOT/.thumbnails"
upsert_env "$MYTOOLS_ENV_FILE" FILE_MAINTENANCE_TRASH_PATH "$NEW_ROOT/.trash/file-maintenance"

SERVICE_USER="$(systemctl show "$DOWNLOADBOT_SERVICE" --property=User --value 2>/dev/null || true)"
SERVICE_GROUP="$(systemctl show "$DOWNLOADBOT_SERVICE" --property=Group --value 2>/dev/null || true)"
SERVICE_USER="${SERVICE_USER:-pankang}"
SERVICE_GROUP="${SERVICE_GROUP:-$SERVICE_USER}"
for directory in .staging .link-staging .pikpak-staging .thumbnails .trash/file-maintenance; do
    install -d -o "$SERVICE_USER" -g "$SERVICE_GROUP" -m 0750 "$NEW_ROOT/$directory"
done

MEDIA_PACKAGE_SQL=''
if table_exists my_tools media_package; then
    MEDIA_PACKAGE_SQL="UPDATE my_tools.media_package SET directory_path=CONCAT('${NEW_ROOT}',SUBSTRING(directory_path,CHAR_LENGTH('${OLD_ROOT}')+1)),updated_at=CURRENT_TIMESTAMP WHERE directory_path='${OLD_ROOT}' OR directory_path LIKE CONCAT('${OLD_ROOT}','/%');"
fi
PIKPAK_SQL=''
if table_exists downloadbot pikpak_transfers; then
    PIKPAK_SQL="UPDATE downloadbot.pikpak_transfers SET local_path=CONCAT('${NEW_ROOT}',SUBSTRING(local_path,CHAR_LENGTH('${OLD_ROOT}')+1)),updated_at=CURRENT_TIMESTAMP WHERE local_path='${OLD_ROOT}' OR local_path LIKE CONCAT('${OLD_ROOT}','/%');"
fi

mysql_query "START TRANSACTION;
UPDATE my_tools.local_directory SET directory_path=CONCAT('${NEW_ROOT}',SUBSTRING(directory_path,CHAR_LENGTH('${OLD_ROOT}')+1)),last_scan_time=NULL,update_time=CURRENT_TIMESTAMP WHERE directory_path='${OLD_ROOT}' OR directory_path LIKE CONCAT('${OLD_ROOT}','/%');
UPDATE my_tools.local_file SET file_path=CONCAT('${NEW_ROOT}',SUBSTRING(file_path,CHAR_LENGTH('${OLD_ROOT}')+1)),update_time=CURRENT_TIMESTAMP WHERE file_path='${OLD_ROOT}' OR file_path LIKE CONCAT('${OLD_ROOT}','/%');
UPDATE my_tools.local_file SET thumbnail_path=CONCAT('${NEW_ROOT}',SUBSTRING(thumbnail_path,CHAR_LENGTH('${OLD_ROOT}')+1)),update_time=CURRENT_TIMESTAMP WHERE thumbnail_path='${OLD_ROOT}' OR thumbnail_path LIKE CONCAT('${OLD_ROOT}','/%');
${MEDIA_PACKAGE_SQL}
UPDATE downloadbot.assets SET path=CONCAT('${NEW_ROOT}',SUBSTRING(path,CHAR_LENGTH('${OLD_ROOT}')+1)),updated_at=CURRENT_TIMESTAMP WHERE path='${OLD_ROOT}' OR path LIKE CONCAT('${OLD_ROOT}','/%');
${PIKPAK_SQL}
COMMIT;"
DATABASE_COMMITTED=true
reconcile_local_file_state

OLD_REFERENCE_COUNT="$(mysql_query "SELECT
 (SELECT COUNT(*) FROM my_tools.local_directory WHERE directory_path='${OLD_ROOT}' OR directory_path LIKE CONCAT('${OLD_ROOT}','/%'))+
 (SELECT COUNT(*) FROM my_tools.local_file WHERE file_path='${OLD_ROOT}' OR file_path LIKE CONCAT('${OLD_ROOT}','/%'))+
 (SELECT COUNT(*) FROM downloadbot.assets WHERE path='${OLD_ROOT}' OR path LIKE CONCAT('${OLD_ROOT}','/%'))")"
[[ "$OLD_REFERENCE_COUNT" == '0' ]] || die "数据库仍有 $OLD_REFERENCE_COUNT 条旧路径记录，请使用备份检查。"

systemctl daemon-reload
if [[ "$RESTART_SERVICES" == true ]]; then
    systemctl restart "$MYTOOLS_SERVICE"
    systemctl restart "$DOWNLOADBOT_SERVICE"
    systemctl is-active --quiet "$MYTOOLS_SERVICE" || die 'MyTools 服务启动失败。'
    systemctl is-active --quiet "$DOWNLOADBOT_SERVICE" || die 'DownloadBot 服务启动失败。'
fi

grep -Fq "$OLD_ROOT" "$DOWNLOADBOT_CONFIG" && die 'DownloadBot 配置仍引用旧目录。'
grep -Fq "$OLD_ROOT" "$DOWNLOADBOT_UNIT_FILE" && die 'DownloadBot systemd 单元仍引用旧目录。'
log "迁移完成，备份目录：$BACKUP_DIR"
log 'MyTools 的 last_scan_time 已清空；服务启动后可通过现有人工全量扫描入口核对新增文件。'
