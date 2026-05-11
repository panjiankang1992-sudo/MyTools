# MyTools 部署指南

## 快速部署

### 方式一：使用部署脚本（推荐）

```bash
# 进入脚本目录
cd scripts

# 完整部署（构建 + 重启）
./deploy.sh --all

# 或者分步执行
./deploy.sh --build    # 仅构建
./deploy.sh --deploy   # 仅部署（重启应用）
./deploy.sh --restart  # 仅重启
./deploy.sh --logs      # 查看日志
./deploy.sh --status    # 查看状态
```

Windows 用户使用:
```batch
scripts\deploy.bat --all
```

### 方式二：手动部署

```bash
# 1. 拉取最新代码
git pull

# 2. 构建项目
mvn clean package -DskipTests

# 3. 重启应用
#    方法A: systemctl (如果配置了服务)
systemctl restart mytools

#    方法B: 手动重启
pkill -f mytools-1.0.0.jar
nohup java -jar target/mytools-1.0.0.jar --server.port=29210 > logs/app.log 2>&1 &

# 4. 检查状态
curl http://localhost:29210
```

## 数据库迁移

**重要**: 仅在以下情况需要执行迁移：
- 首次部署项目
- 添加了新功能需要新表/字段

### 检查是否需要迁移

查看代码中是否有新的 SQL 文件：
```bash
ls sql/migration/
```

### 执行迁移

```bash
# 1. 查看迁移SQL
cat sql/migration/V2026_05_11__add_user_profile_columns.sql

# 2. 登录MySQL执行
mysql -u root -p your_database < sql/migration/V2026_05_11__add_user_profile_columns.sql

# 或分步执行
mysql -u root -p your_database
```

## 服务管理

### 查看应用状态
```bash
# 检查端口
netstat -tlnp | grep 29210

# 或使用脚本
./scripts/deploy.sh --status
```

### 查看日志
```bash
# 实时查看
tail -f logs/app.log

# 或使用脚本
./scripts/deploy.sh --logs
```

### 停止应用
```bash
# 方法1: pkill
pkill -f mytools-1.0.0.jar

# 方法2: kill + PID
ps aux | grep mytools
kill <PID>
```

## 常见问题

### Q: 部署后登录报错 500
A: 检查数据库迁移是否执行，特别是新增的表和字段

### Q: 端口被占用
A: 检查是否有旧进程: `lsof -i :29210` 或 `netstat -tlnp | grep 29210`

### Q: 前端静态文件不更新
A: 需要重新构建前端并部署到 Nginx:
```bash
cd webapp
npm run build
# 将 dist 目录内容复制到 nginx/html
```

### Q: 忘记数据库密码
A: 检查 `src/main/resources/application.yml` 中的数据库配置

## 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+
- Node.js 16+ (前端构建需要)

## 目录结构

```
MyTools/
├── scripts/          # 部署脚本
│   ├── deploy.sh    # Linux/Mac 部署脚本
│   └── deploy.bat   # Windows 部署脚本
├── sql/
│   └── migration/   # 数据库迁移脚本
├── docs/
│   └── DEPLOY.md    # 本文档
├── target/          # 编译输出
│   └── mytools-1.0.0.jar
└── webapp/
    └── dist/        # 前端构建输出
```

## 自动化部署 (CI/CD)

如需自动化部署，可参考以下配置:

### GitHub Actions
```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [master]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Build
        run: mvn clean package -DskipTests
      - name: Deploy
        run: |
          # 使用 scp 复制到服务器
          # 或使用 ssh action 执行部署脚本
```
