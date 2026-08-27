# MyTools 部署指南

## 快速部署

### 必需安全环境变量

生产启动前必须通过受限环境文件、Secret Manager或systemd `EnvironmentFile`提供以下变量，仓库不再包含可用默认密钥：

```text
MYTOOLS_DB_PASSWORD
SALES_ORDER_DB_PASSWORD
JWT_SECRET
PASSWORD_SALT
MYTOOLS_ENCRYPTION_KEY
```

`MYTOOLS_ENCRYPTION_KEY`必须是Base64编码的16、24或32字节随机密钥，可在受控终端运行
`java ... AesEncryptUtils generate-key`生成。不要把生成结果写入仓库、命令历史或部署日志。

轮换WebDAV凭据加密密钥时，把旧密钥暂存到`MYTOOLS_ENCRYPTION_PREVIOUS_KEY`，把新密钥设置为
`MYTOOLS_ENCRYPTION_KEY`。账号下一次保存或更新时会使用新密钥重新加密；确认所有账号完成迁移后删除旧密钥变量。

### 方式一：通过SSH通道远程部署（推荐）

远程入口统一使用 `ssh.yuyutian.top`。脚本只调用系统 `ssh`/`scp`，认证必须来自 SSH
配置、指定的私钥文件或 `ssh-agent`；不要把密码放入参数、环境变量或仓库文件。

```bash
export MYTOOLS_SSH_USER='<remote-user>'
# 可选：export MYTOOLS_SSH_IDENTITY='/absolute/path/to/private-key'

# 验证新服务部署根；具体版本以发布清单和 releases/current 为准
scripts/remote-ssh.sh run -- test -d /opt/yuyutian/mytools
scripts/remote-ssh.sh run -- readlink /opt/yuyutian/mytools/releases/current

# 上传已经在本机验证过的发布包
scripts/remote-ssh.sh upload service/build/mytools-services.tar.gz /tmp/mytools-services.tar.gz

# 需要交互排查时进入远端终端
scripts/remote-ssh.sh connect
```

新服务统一部署到 `/opt/yuyutian/mytools`，以不可变版本目录和 `releases/current` 原子切换；远端不要求
存在 Git 工作区。旧应用目录 `/opt/yuyutian/app/MyTools` 不由新服务发布器覆盖。任何安装、迁移或
重启动作都应先通过只读命令确认远端发布根和当前版本，再执行 `service/deploy/README.md` 中的流程。

### 方式二：在目标服务器使用部署脚本

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

### 方式三：在目标服务器手动部署

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
nohup java -jar target/mytools-1.0.0.jar --server.port=23110 > logs/app.log 2>&1 &

# 4. 检查状态
curl http://localhost:23110
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
netstat -tlnp | grep 23110

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
A: 检查是否有旧进程: `lsof -i :23110` 或 `netstat -tlnp | grep 23110`

### Q: 前端静态文件不更新
A: 需要重新构建前端并部署到 Nginx:
```bash
cd webapp
npm run build
# 将 dist 目录内容复制到 nginx/html
```

### Q: 启动时提示缺少密钥
A: 检查服务进程实际读取的受限环境文件，确认上述必需变量已设置；不要把真实值补回`application.yml`。

## 环境要求

- Java 21+
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
          java-version: '21'
      - name: Build
        run: mvn clean package -DskipTests
      - name: Deploy
        run: |
          # 使用 scp 复制到服务器
          # 或使用 ssh action 执行部署脚本
```
