# MyTools Constitution

**Version**: 1.0.0 | **Ratified**: 2026-04-21 | **Last Amended**: 2026-04-21

## Core Principles

### I. Chinese Comments, English Code (NON-NEGOTIABLE)
所有代码注释使用中文标点符号（。）和中文句子。代码本身（变量名、方法名、包名、配置文件）必须使用英文，禁止出现中文。

### II. Package-by-Feature Architecture
按功能分子包组织代码，而非按技术层分组。controller/、service/、Model/、mapper/、utils/ 下均按功能划分子包，例如 `controller/user/`、`service/order/`。

### III. Test Coverage (NON-NEGOTIABLE)
每个功能模块必须包含对应的单元测试，覆盖所有 public 业务方法，关键逻辑路径测试覆盖率达到 80% 以上。

### IV. Centralized Error Management
所有业务异常通过错误码抛出，禁止硬编码错误信息。错误码格式为 `模块_序号`，统一在 `common/ErrorCode.java` 管理。

### V. No Warning Tolerance
代码不允许出现任何 Lint 警告或 IDE 规范告警，所有警告必须在提交前修复。

## Project Structure

### Layer Convention
```
src/main/java/com/xxx/{module}/
├── controller/     # 控制层，按功能分子包
├── Model/          # 数据模型（实体类）
├── mapper/         # 数据访问层
├── service/        # 业务逻辑层，按功能分子包
└── utils/          # 工具类，按功能分子包
```

### Code Documentation Standards
- **必须**：所有 `public` 方法必须有 Javadoc 注释
- **必须**：关键逻辑（if/循环/重要赋值）需行内注释说明
- **必须**：枚举、配置类、常量类必须有类级注释

## Security Constraints

### Password Storage
用户密码必须使用 BCrypt 算法加密存储，盐值从配置文件读取，不得硬编码。

### Authentication
使用 JWT Token 作为无状态认证机制，支持 Token 刷新，但同一用户同一时间最多持有 3 个有效 Token。

### RBAC
系统必须实现基于角色的访问控制（RBAC），至少包含 ADMIN 和 USER 两种角色。

## Build & Verification

- 编译命令：`mvn compile`
- 打包命令：`mvn package -DskipTests`
- 修复任何编译警告后再提交

## Governance

Constitution supersedes all other practices. Amendments require documentation, approval, and migration plan. All PRs/reviews must verify compliance.

**技术栈**：Java 21 + Spring Boot + Maven + MyBatis + MySQL
