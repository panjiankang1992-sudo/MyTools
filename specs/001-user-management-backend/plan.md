# Implementation Plan: Spring Boot User Management Backend

**Branch**: `001-user-management-backend` | **Date**: 2026-04-21 | **Spec**: specs/001-user-management-backend/spec.md
**Input**: Feature specification from `/specs/001-user-management-backend/spec.md`

## Summary

构建一个 Spring Boot 3.x + MyBatis 3.x + MySQL + Maven 后端工程，实现用户管理模块（注册/登录/权限管理/Session刷新/CRUD/禁用启用），包含统一异常拦截（`@RestControllerAdvice`）、JWT鉴权（JJWT + refresh token rotation）、日志轮转（10MB/7天/10文件，Logback SizeAndTimeBasedRollingPolicy）、BCrypt密码加密（盐值从配置读取）、雪花算法ID生成器（支持时钟偏移处理）、文件读取工具、多数据源配置（baomidou dynamic-datasource）。每个功能模块必须有单元测试覆盖。

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- Spring Boot 3.x (web, security, validation)
- MyBatis 3.0.4 + dynamic-datasource-spring-boot3-starter 4.5.0 (多数据源路由)
- MySQL 8.x (Connector/J)
- JJWT 0.12.5 (JWT生成/校验)
- Lombok (减少样板代码)
- JUnit 5 + Mockito + H2 (测试)
**Storage**: MySQL (双数据源：my_tools 主库 + sales_order 辅助库)
**Testing**: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + `@WebMvcTest` + `@MybatisTest` + H2 in-memory
**Target Platform**: Linux Server (个人工具后端)
**Project Type**: REST API Web Service (Spring Boot Backend)
**Performance Goals**: 100并发下平均响应时间<2s，Token刷新<500ms，用户信息获取<200ms
**Constraints**: JWT无状态认证（AccessToken 15min，RefreshToken 7天），同一用户最多3个有效Token，日志文件10MB轮转/保留7天/最多10个(totalSizeCap=100MB)
**Scale**: 个人使用，预期<100用户

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 代码注释使用中文 | ✅ PASS | AGENTS.md明确规定，所有public方法有Javadoc注释 |
| 变量/方法/包名使用英文 | ✅ PASS | 代码中除注释外不允许出现中文 |
| 包结构按功能分子包 | ✅ PASS | controller/user/, service/user/ 等 |
| 错误码统一管理 | ✅ PASS | common/ErrorCode.java，格式 模块_序号 |
| 无Lint/IDE警告 | ✅ PASS | 代码规范要求 |
| 单元测试覆盖 | ✅ PASS | 每个功能模块必须包含单元测试 |
| 密码BCrypt加密 | ✅ PASS | 盐值从配置文件读取 |
| JWT认证 | ✅ PASS | JJWT实现，支持refresh token rotation |
| 日志轮转配置 | ✅ PASS | SizeAndTimeBasedRollingPolicy, 10MB/7天/10文件 |

## Project Structure

### Documentation (this feature)

```text
specs/001-user-management-backend/
├── plan.md              # This file
├── research.md          # Phase 0 output (6个研究主题已resolved)
├── data-model.md        # Phase 1 output (5个实体 + 建表SQL)
├── quickstart.md        # Phase 1 output (待创建)
├── contracts/           # Phase 1 output
│   └── user-api.md     # REST API协议定义
└── tasks.md             # Phase 2 output (NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/com/mytools/
├── MyToolsApplication.java              # Spring Boot主入口
├── common/
│   ├── ErrorCode.java                 # 错误码枚举（USER_001等）
│   ├── BusinessException.java         # 业务异常基类
│   ├── Result.java                    # 统一响应格式
│   └── GlobalExceptionHandler.java    # 统一异常拦截(@RestControllerAdvice)
├── config/
│   ├── SecurityConfig.java            # Spring Security配置(JWT Filter)
│   ├── DataSourceConfig.java          # 多数据源配置(dynamic-datasource)
│   └── SnowflakeConfig.java           # 雪花算法配置
├── user/
│   ├── controller/
│   │   └── UserController.java        # 用户CRUD接口
│   ├── service/
│   │   ├── UserService.java
│   │   └── impl/
│   │       └── UserServiceImpl.java
│   ├── mapper/
│   │   ├── UserMapper.java
│   │   └── RoleMapper.java
│   └── Model/
│       ├── User.java
│       ├── UserDTO.java
│       └── UserQuery.java
├── auth/
│   ├── controller/
│   │   └── AuthController.java        # 注册/登录/刷新Token
│   ├── service/
│   │   ├── AuthService.java
│   │   └── impl/
│   │       └── AuthServiceImpl.java
│   ├── mapper/
│   │   └── TokenMapper.java
│   ├── filter/
│   │   └── JwtAuthenticationFilter.java
│   └── utils/
│       ├── JwtUtils.java              # JWT生成/校验工具
│       └── PasswordUtils.java         # BCrypt加密工具
├── utils/
│   ├── SnowflakeIdGenerator.java      # 雪花算法ID生成器
│   └── FileUtils.java                # 文件读取工具
└── logging/
    └── LogConfig.java                 # 日志配置辅助

src/main/resources/
├── application.yml                   # 主配置（多数据源/JWT/雪花算法）
├── application-dev.yml               # 开发环境覆盖
├── logback-spring.xml                # 日志配置（10MB/7天/10文件）
└── mapper/                          # MyBatis XML映射文件
    ├── user/
    │   └── UserMapper.xml
    └── auth/
        └── TokenMapper.xml

src/test/java/com/mytools/
├── user/
│   ├── service/
│   │   └── UserServiceTest.java      # Mockito单元测试
│   └── controller/
│       └── UserControllerTest.java   # @WebMvcTest切片测试
├── auth/
│   ├── service/
│   │   └── AuthServiceTest.java
│   └── controller/
│       └── AuthControllerTest.java
└── integration/
    └── UserManagementIntegrationTest.java  # @SpringBootTest全量测试
```

**Structure Decision**: Spring Boot 单体应用，package-by-feature 结构。controller/、service/、mapper/、Model/、utils/ 均按功能分子包（user/、auth/、common/）。多数据源通过 baomidou dynamic-datasource + `@DS` 注解管理。

## Phase 0: Research (Resolved)

All 6 research topics completed and consolidated in `research.md`:

| # | Topic | Decision | Key Library/Approach |
|---|-------|----------|---------------------|
| 1 | Multi-datasource | baomidou dynamic-datasource | `@DS` annotation, `@DSTransactional` |
| 2 | JWT Authentication | JJWT + refresh token rotation | jjwt 0.12.5, Redis blacklist for jti |
| 3 | Global Exception | `@RestControllerAdvice` + ErrorCode enum | BusinessException hierarchy |
| 4 | Log Rotation | SizeAndTimeBasedRollingPolicy | 10MB/7days/100MB totalSizeCap |
| 5 | Testing | JUnit5 + Mockito + `@WebMvcTest` + `@MybatisTest` | H2 in-memory for integration |
| 6 | Snowflake ID | Custom with clock skew handling | WAIT (<100ms) + RANDOM_COMPENSATE fallback |

## Phase 1: Design Artifacts

### data-model.md ✅
- 5个实体：User, Role, UserRole, Token, ErrorCode
- 完整建表SQL (DDL + 初始化数据)
- 关系图：User (1)→(N) UserRole (N)←(1) Role, User (1)→(N) Token
- 验证规则：用户名3-20字符/密码8位大小写字母数字/邮箱RFC5322

### contracts/user-api.md ✅
- REST API协议（9个端点）
- 统一响应格式 `{code, message, data}`
- 错误码映射表（USER_001-009, AUTH_001-005, SYS_001-003）
- 请求/响应JSON示例

### quickstart.md ⏳
- 待创建：环境搭建步骤、数据库初始化、Maven构建命令

## Agent Context Update

Update AGENTS.md with plan reference between `<!-- SPECKIT START -->` and `<!-- SPECKIT END -->` markers.

## Complexity Tracking

> No violations requiring justification. All decisions follow established Spring Boot + MyBatis best practices. No additional projects or complex patterns introduced beyond what's specified in requirements.
