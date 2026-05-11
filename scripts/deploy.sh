#!/bin/bash
# ============================================
# MyTools 部署脚本
# ============================================

set -e

# 配置区域
APP_NAME="mytools"
APP_PORT=29210
JAR_FILE="target/mytools-1.0.0.jar"
CURRENT_DIR=$(cd "$(dirname "$0")/.." && pwd)

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 显示帮助
show_help() {
    echo "MyTools 部署脚本"
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -b, --build      构建项目（需要Maven）"
    echo "  -d, --deploy     部署应用到服务器"
    echo "  -r, --restart    重启应用"
    echo "  -l, --logs       查看应用日志"
    echo "  -s, --status     查看应用状态"
    echo "  -m, --migrate    执行数据库迁移"
    echo "  -a, --all        完整部署（构建+迁移+重启）"
    echo "  -h, --help       显示帮助"
    echo ""
    echo "示例:"
    echo "  $0 --all              # 完整部署"
    echo "  $0 --build --deploy  # 构建并部署"
    echo "  $0 --restart         # 重启应用"
}

# 检查是否在正确目录
check_dir() {
    if [ ! -f "pom.xml" ]; then
        log_error "请在项目根目录执行此脚本"
        exit 1
    fi
}

# 构建项目
build_project() {
    log_info "开始构建项目..."
    cd "$CURRENT_DIR"
    mvn clean package -DskipTests -q
    if [ $? -eq 0 ]; then
        log_info "构建成功: $JAR_FILE"
    else
        log_error "构建失败"
        exit 1
    fi
}

# 停止应用
stop_app() {
    log_info "停止应用..."
    PID=$(pgrep -f "$JAR_FILE" || true)
    if [ -n "$PID" ]; then
        kill $PID
        sleep 2
        log_info "应用已停止 (PID: $PID)"
    else
        log_warn "应用未运行"
    fi
}

# 启动应用
start_app() {
    log_info "启动应用..."
    cd "$CURRENT_DIR"

    # 使用 nohup 后台启动
    nohup java -jar "$JAR_FILE" --server.port=$APP_PORT > logs/app.log 2>&1 &
    sleep 3

    # 检查是否启动成功
    if curl -s "http://localhost:$APP_PORT/api/health" > /dev/null 2>&1 || curl -s "http://localhost:$APP_PORT" > /dev/null 2>&1; then
        log_info "应用启动成功 (端口: $APP_PORT)"
    else
        log_warn "应用可能启动中，请检查日志: logs/app.log"
    fi
}

# 查看状态
check_status() {
    PID=$(pgrep -f "$JAR_FILE" || true)
    if [ -n "$PID" ]; then
        log_info "应用运行中 (PID: $PID)"
        # 尝试获取端口信息
        if command -v lsof > /dev/null 2>&1; then
            lsof -i :$APP_PORT 2>/dev/null | grep LISTEN || true
        fi
    else
        log_warn "应用未运行"
    fi
}

# 查看日志
show_logs() {
    if [ -f "logs/app.log" ]; then
        tail -100 logs/app.log
    else
        log_warn "日志文件不存在: logs/app.log"
    fi
}

# 执行数据库迁移（仅首次部署需要）
run_migrate() {
    log_info "检查数据库迁移..."

    # 检查迁移文件
    MIGRATION_FILE="sql/migration/V2026_05_11__add_user_profile_columns.sql"
    if [ ! -f "$MIGRATION_FILE" ]; then
        log_warn "迁移文件不存在: $MIGRATION_FILE"
        return
    fi

    # 检查数据库配置
    if [ ! -f "src/main/resources/application.yml" ]; then
        log_warn "配置文件不存在，跳过迁移"
        return
    fi

    read -p "是否执行数据库迁移? (需要数据库连接信息) [y/N]: " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        log_info "跳过迁移"
        return
    fi

    log_info "请手动执行以下SQL:"
    echo ""
    cat "$MIGRATION_FILE"
    echo ""
    log_info "复制上述SQL到MySQL客户端执行"
}

# 完整部署
full_deploy() {
    log_info "开始完整部署流程..."
    build_project
    run_migrate
    stop_app
    start_app
    log_info "部署完成!"
}

# 主逻辑
main() {
    check_dir

    if [ $# -eq 0 ]; then
        show_help
        exit 0
    fi

    while [ $# -gt 0 ]; do
        case "$1" in
            -b|--build)
                BUILD=true
                ;;
            -d|--deploy)
                DEPLOY=true
                ;;
            -r|--restart)
                RESTART=true
                ;;
            -l|--logs)
                LOGS=true
                ;;
            -s|--status)
                STATUS=true
                ;;
            -m|--migrate)
                MIGRATE=true
                ;;
            -a|--all)
                ALL=true
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                log_error "未知选项: $1"
                show_help
                exit 1
                ;;
        esac
        shift
    done

    # 创建日志目录
    mkdir -p "$CURRENT_DIR/logs"

    # 执行对应的操作
    if [ "$ALL" = true ]; then
        full_deploy
    fi

    [ "$BUILD" = true ] && build_project
    [ "$MIGRATE" = true ] && run_migrate
    [ "$DEPLOY" = true ] && { stop_app; start_app; }
    [ "$RESTART" = true ] && { stop_app; start_app; }
    [ "$LOGS" = true ] && show_logs
    [ "$STATUS" = true ] && check_status
}

main "$@"
