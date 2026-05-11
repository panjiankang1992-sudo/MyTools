# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## AI 编程指南 (Karpathy 原则)

**目标**: 减少常见的 LLM 编程错误。

**权衡**: 这些指南偏向谨慎而非速度。对于简单任务，请自行判断。

### 1. 编码前思考

**不要假设。不要隐藏困惑。暴露权衡。**

实现前：
- 明确陈述你的假设。如果不确定，请问。
- 如果存在多种解释，呈现它们 - 不要默默选择。
- 如果存在更简单的方法，请指出。有理由时据理力争。
- 如果某事不清楚，停下来。说出什么让你困惑。

### 2. 简单性优先

**最小化代码解决问题。不要 speculative。**

- 不要添加超出要求的功能。
- 不要为单一用途的代码创建抽象。
- 不要添加未经请求的"灵活性"或"可配置性"。
- 不要为不可能的场景添加错误处理。
- 如果你写了 200 行而它可以是 50 行，重写它。

问自己："高级工程师会说这太复杂了吗？" 如果是，简化。

### 3. 精准修改

**只触碰必须触碰的。只清理你自己的烂摊子。**

编辑现有代码时：
- 不要"改进"相邻的代码、注释或格式。
- 不要重构没有坏的东西。
- 匹配现有风格，即使你会有所不同。
- 如果你注意到不相关的死代码，指出它 - 不要删除它。

当你的更改创建了孤立文件时：
- 移除你的更改导致未使用的 imports/变量/函数。
- 不要移除预先存在的死代码，除非被要求。

测试：每一行更改都应该直接追溯到用户请求。

### 4. 目标驱动执行

**定义成功标准。循环直到验证。**

将任务转化为可验证的目标：
- "添加验证" → "为无效输入编写测试，然后让它们通过"
- "修复 bug" → "编写一个复现它的测试，然后让测试通过"
- "重构 X" → "确保测试前后都通过"

对于多步骤任务，陈述一个简要计划：
```
1. [步骤] → 验证: [检查]
2. [步骤] → 验证: [检查]
3. [步骤] → 验证: [检查]
```

强有力的成功标准让你能够独立循环。弱标准（"让它工作"）需要不断确认。

---

**这些指南起作用时:** diff 中不必要的更改更少，因过度复杂而重写更少，澄清问题在错误之前而非之后出现。

---

## 项目概述

这是一个全栈管理后台项目，包含：
- **后端**: Java 21 + Spring Boot 3.2.5 + MyBatis + MySQL (位于 `src/`)
- **前端**: Vue3 + TypeScript + NaiveUI (位于 `webapp/`，基于 SoybeanAdmin)
- **技术规格文档**: 位于 `specs/` 目录

## 分支管理规则

**重要**: `master` 是主分支，所有开发代码必须合入到此分支。

### 分支命名规范
- 功能开发: `XXX-feature/` 或 `XXX 功能描述`
- Bug修复: `fix-XXX/` 或 `hotfix-XXX/`
- 不要直接向 master 提交代码，所有改动通过 PR 合入

### 合入规则
1. 所有代码开发在功能分支进行
2. 开发完成后创建 PR 合入 master
3. 合入前需确保代码可编译通过
4. 合入后删除已合并的功能分支

## 框架参考文档

**SoybeanAdmin 框架指南**: `doc/soybean-admin-guide.md`

此文档总结了 [SoybeanAdmin 官方文档](https://docs.soybeanjs.cn/zh/) 的核心内容，包括：
- 环境变量配置说明
- 请求封装机制
- API 调用规范
- 常见问题解决方案
- 代码命名规范

**重要**: 前端问题排查和方案设计应优先参考此文档。

## 常用命令

### 后端 (Maven)
```bash
mvn compile                    # 编译项目
mvn package -DskipTests        # 打包(跳过测试)
mvn test                       # 运行测试
```

### 前端 (pnpm)
```bash
cd webapp && pnpm dev          # 开发模式启动
cd webapp && pnpm build        # 生产构建
cd webapp && pnpm typecheck    # TypeScript 类型检查
cd webapp && pnpm lint         # ESLint 检查并修复
```

## 架构设计

### 后端分层结构
```
src/main/java/com/yuyutian/mytools/
├── auth/           # JWT认证模块
│   ├── controller/ AuthController
│   ├── filter/     JwtAuthenticationFilter
│   ├── mapper/     TokenMapper
│   ├── service/   AuthService
│   └── utils/     JwtUtils
├── user/           # 用户管理模块
│   ├── controller/ UserController
│   ├── mapper/    UserMapper, UserRoleMapper
│   └── service/   UserService
├── role/           # 角色管理模块
├── wechat/         # 微信相关模块
│   ├── account/   # 微信账号管理
│   └── moments/   # 朋友圈任务模块
├── localfile/      # 本地文件模块
│   ├── controller/ LocalFileController
│   ├── service/
│   │   ├── tagging/  # 打标签子模块
│   │   │   ├── TaggerService.java
│   │   │   ├── TaggerClient.java
│   │   │   ├── TaggerException.java
│   │   │   └── impl/TaggerServiceImpl.java
│   │   └── LocalFileService.java
│   ├── mapper/    LocalFileMapper, FileTagMapper
│   ├── entity/     FileTag
│   └── job/        FileScanJob
├── token/          # Token管理模块
├── scheduler/      # 定时任务模块
├── config/         # Spring配置类
├── common/         # 公共类(Result, ErrorCode, BusinessException)
└── utils/         # 工具类
```

### 认证机制
- JWT Token认证，Spring Security实现
- 无状态会话 (`SessionCreationPolicy.STATELESS`)
- `/api/auth/**` 端点公开，其他需认证
- 密码使用 BCrypt 加密

### 前端架构
- Vue3 + TypeScript + Pinia 状态管理
- 基于文件的路由自动生成 (Elegant Router)
- UnoCSS 原子化CSS
- Tiptap 富文本编辑器(用于朋友圈内容编辑)

## 代码规范

### 注释要求
- **必须**: 所有 `public` 方法必须有 Javadoc 注释
- **必须**: 关键逻辑(if/循环/重要赋值)需行内注释说明
- **必须**: 枚举、配置类、常量类必须有类级注释
- 注释使用中文句子的标点符号()

### 命名规范
- 变量/方法/包名使用英文
- 错误码格式: `模块_序号` (如 `USER_001`, `AUTH_002`)
- 错误码统一在 `common/ErrorCode.java` 中管理

### 提交前检查
- 不允许出现任何 Lint 警告或 IDE 规范告警
- 所有编译警告必须修复后再提交

## 错误码规范

### 错误码格式
采用 5 位纯数字格式，按模块分段：
- 用户 USER: 10001-10099
- 认证 AUTH: 20001-20099
- 本地文件 FILE: 30001-30099
- Token: 40001-40099
- 角色 ROLE: 60001-60099
- 系统 SYS: 50001-50099

### 错误消息国际化
- 所有错误消息通过 `MessageSource` + `messages_xx_XX.properties` 实现国际化
- 后端 `ErrorCode` 枚举包含 `messageKey` 字段，对应 properties 文件中的 key
- 前端通过 `webapp/src/service/error-code.ts` 中的 `ErrorMessageMap` 维护国际化映射
- 新增错误码时需同时添加国际化消息

### 异常处理
- `BusinessException` 支持 `messageKey` 和 `fieldErrors`
- `GlobalExceptionHandler` 处理所有异常，返回国际化消息
- 未捕获异常返回 `SYS_001`，堆栈打印到日志

## 数据库

- MySQL + MyBatis-Plus(动态数据源)
- Schema初始化: `sql/init-schema.sql`
- 测试数据: `sql/init-data.sql`

## API文档

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## 规格文档

规格文档位于 `specs/` 目录，按任务编号组织：
- `specs/001-user-management-backend/` - 用户管理后端
- `specs/002-user-management-frontend-adaptation/` - 前端适配
- `specs/003-wechat-moments-task/` - 朋友圈任务模块
- `specs/004-wechat-moments-refresh-config/` - 朋友圈任务刷新配置
- `specs/005-moments-task-list-ui/` - 朋友圈任务列表UI优化
- `specs/006-local-file-browser/` - 本地文件浏览器
- `specs/007-token-management/` - 用户令牌管理
- `specs/008-file-auto-tagging/` - 文件自动打标签

## 待开发模块

- [x] localfile - 本地文件管理模块（已完成：文件扫描、自动打标签、手动打标签）
- [x] token - Token管理模块（已完成：令牌列表、在线状态、强制下线）
- [x] scheduler - 定时任务模块（已完成：文件扫描任务、打标签任务）

## 合入规则（重要）

**所有任务必须合入 master 分支才算完成。**

1. 任务开发完成后，将所有改动提交到当前分支
2. 通过 PR 合入 master 分支（或直接合入如果已在自己分支）
3. 合入前必须确保：
   - 代码可编译通过 (`mvn compile`)
   - 无编译警告
4. 合入 master 后任务才算正式完成

## 部署信息

**注意**: 敏感信息（密码等）存储在 `~/.MyToolsEnv` 文件中，请勿将密码直接写入代码或文档。

### 现网环境
| 项目 | 信息 |
|------|------|
| 主机 | 192.168.1.9 |
| 用户 | pankang |
| 密码 | 见 `~/.MyToolsEnv` |

### 服务部署地址
| 服务 | 部署路径 | 端口 |
|------|----------|------|
| 后端 | `/opt/mycode/MyTools/backend` | 29210 |
| 前端 | `/opt/mycode/MyTools/webapp` | 29211 |

### 相关服务地址
| 服务 | 地址 |
|------|------|
| 后端API | http://192.168.1.9:29210 |
| 前端 | http://192.168.1.9:29211 |
| Swagger UI | http://192.168.1.9:29210/swagger-ui.html |
| MySQL | 192.168.1.8:3306 (my_tools 库) |
| 打标签服务 | http://192.168.1.9:8024 |
