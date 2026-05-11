@echo off
chcp 65001 > nul
REM ============================================
REM MyTools 部署脚本 (Windows)
REM ============================================

setlocal enabledelayedexpansion

set APP_NAME=mytools
set APP_PORT=29210
set JAR_FILE=target\mytools-1.0.0.jar
set CURRENT_DIR=%~dp0..

cd /d "%CURRENT_DIR%"

echo.
echo ============================================
echo MyTools 部署脚本
echo ============================================
echo.

REM 解析参数
if "%~1"=="" goto help
if "%~1"=="-h" goto help
if "%~1"=="--help" goto help

:parse_args
if "%~1"=="-b" set BUILD=1
if "%~1"=="--build" set BUILD=1
if "%~1"=="-d" set DEPLOY=1
if "%~1"=="--deploy" set DEPLOY=1
if "%~1"=="-r" set RESTART=1
if "%~1"=="--restart" set RESTART=1
if "%~1"=="-l" set LOGS=1
if "%~1"=="--logs" set LOGS=1
if "%~1"=="-s" set STATUS=1
if "%~1"=="--status" set STATUS=1
if "%~1"=="-m" set MIGRATE=1
if "%~1"=="--migrate" set MIGRATE=1
if "%~1"=="-a" set ALL=1
if "%~1"=="--all" set ALL=1
shift
if not "%~1"=="" goto parse_args

REM 帮助
:help
echo 用法: %~nx0 [选项]
echo.
echo 选项:
echo   -b, --build      构建项目（需要Maven）
echo   -d, --deploy     部署应用（重启）
echo   -r, --restart    重启应用
echo   -l, --logs       查看应用日志
echo   -s, --status     查看应用状态
echo   -m, --migrate    显示迁移SQL
echo   -a, --all        完整部署（构建+重启）
echo   -h, --help       显示帮助
echo.
echo 示例:
echo   %~nx0 --all              完整部署
echo   %~nx0 --build --deploy   构建并部署
echo   %~nx0 --restart          重启应用
echo.
goto :end

REM 构建项目
:build
echo [INFO] 开始构建项目...
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo [ERROR] 构建失败
    exit /b 1
)
echo [INFO] 构建成功
goto :end

REM 停止应用
:stop
echo [INFO] 停止应用...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%APP_PORT% ^| findstr LISTENING') do (
    set PID=%%a
)
if defined PID (
    taskkill /F /PID !PID! > nul 2>&1
    timeout /t 2 /nobreak > nul
    echo [INFO] 应用已停止
) else (
    echo [WARN] 应用未运行
)
goto :end

REM 启动应用
:start
echo [INFO] 启动应用...
if not exist "logs" mkdir logs
start /B java -jar %JAR_FILE% --server.port=%APP_PORT% > logs\app.log 2>&1
timeout /t 3 /nobreak > nul
echo [INFO] 应用启动中 (端口: %APP_PORT%)
goto :end

REM 查看状态
:status
echo [INFO] 检查应用状态...
netstat -ano | findstr :%APP_PORT% | findstr LISTENING
if errorlevel 1 (
    echo [WARN] 应用未运行
) else (
    echo [INFO] 应用运行中
)
goto :end

REM 查看日志
:showlogs
if exist "logs\app.log" (
    type logs\app.log | more /p
) else (
    echo [WARN] 日志文件不存在
)
goto :end

REM 显示迁移SQL
:migrate
echo.
echo [INFO] 数据库迁移SQL:
echo.
type sql\migration\V2026_05_11__add_user_profile_columns.sql
echo.
echo [INFO] 请复制上述SQL到MySQL执行
echo.
goto :end

REM 完整部署
:fulldeploy
echo [INFO] 开始完整部署...
call :build
call :stop
call :start
echo [INFO] 部署完成
goto :end

:end
endlocal
