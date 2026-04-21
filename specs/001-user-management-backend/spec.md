# Feature Specification: Spring Boot User Management Backend

**Feature Branch**: `001-user-management-backend`
**Created**: 2026-04-21
**Status**: Draft
**Input**: "构建一个springboot+mybatis+mysql+maven的后端工程，要求：1.需要有完善的测试能力，即每个功能都需要有对应的测试用例 2.该后端是个人工具使用，功能需要按照包名分开，例如 controller下面需要按照包名区分，service和Model层也是如此 3.当前需要完成的是用户管理模块，包括用户注册，登录，权限管理，session刷新，获取用户信息，用户删除，修改密码，更新用户信息，用户禁用等能力；4.参照上面的用户管理需要的能力，请自行建库建表，数据库名 my_tools；5.该工程需要能连接多个数据库，请构建的时候设计好 6.需要有异常统一拦截机制 7.需要有鉴权能力 8.需要有日志能力，日志模块10M绕接一次，保存7天，最多保存10个 9.密码需要加密处理，盐值暂时写入到配置文件中 10.必要的公共方法，例如雪花算法，文件读取等能力请一并添加上"

## User Scenarios & Testing

### User Story 1 - 用户注册 (Priority: P1)

新用户可以通过用户名、密码、邮箱等信息创建账户。

**Why this priority**: 是所有后续功能的前提，没有账户无法使用任何系统能力。

**Independent Test**: 可通过 POST /api/user/register 接口独立测试，注册成功后返回用户ID和 Token。

**Acceptance Scenarios**:

1. **Given** 数据库无此用户，**When** 提交有效注册信息，**Then** 返回成功状态、用户ID和认证Token，数据库新增一条用户记录
2. **Given** 用户名已存在，**When** 提交相同用户名注册，**Then** 返回错误码 USER_002（用户名已存在）
3. **Given** 密码格式不符合要求，**When** 提交弱密码，**Then** 返回错误码 USER_003（密码不符合规范）
4. **Given** 邮箱格式错误，**When** 提交无效邮箱，**Then** 返回错误码 USER_004（邮箱格式错误）

---

### User Story 2 - 用户登录 (Priority: P1)

已注册用户可以通过用户名/邮箱和密码登录系统，获取认证凭证。

**Why this priority**: 登录是用户使用系统的主要入口，必须稳定可靠。

**Independent Test**: 可通过 POST /api/user/login 接口独立测试，登录成功后返回 Token 和用户基本信息。

**Acceptance Scenarios**:

1. **Given** 用户已注册且状态正常，**When** 输入正确用户名和密码，**Then** 返回有效Token和用户信息
2. **Given** 密码错误，**When** 输入错误密码，**Then** 返回错误码 USER_005（用户名或密码错误），不暴露具体是哪个字段错误
3. **Given** 用户已被禁用，**When** 尝试登录，**Then** 返回错误码 USER_006（账户已禁用）
4. **Given** 用户不存在，**When** 输入不存在的用户名，**Then** 返回错误码 USER_005（用户名或密码错误）

---

### User Story 3 - Session 刷新 (Priority: P2)

已登录用户的认证 Token 即将过期时，可以刷新 Token 延长会话。

**Why this priority**: 保证长时间操作的用户不会被强制登出，提升用户体验。

**Independent Test**: 可通过 POST /api/user/refresh 接口独立测试，需携带有效 Token。

**Acceptance Scenarios**:

1. **Given** Token 有效但接近过期，**When** 请求刷新，**Then** 返回新的有效Token
2. **Given** Token 已过期，**When** 请求刷新，**Then** 返回错误码 AUTH_001（Token已过期）
3. **Given** Token 格式错误，**When** 请求刷新，**Then** 返回错误码 AUTH_002（无效Token）

---

### User Story 4 - 获取当前用户信息 (Priority: P2)

已登录用户可以查看自己的账户信息。

**Why this priority**: 用户需要查看和确认自己的账户状态和信息。

**Independent Test**: 可通过 GET /api/user/info 接口独立测试，需携带有效 Token。

**Acceptance Scenarios**:

1. **Given** 用户已登录且Token有效，**When** 请求用户信息，**Then** 返回用户名、邮箱、角色、注册时间、最后登录时间
2. **Given** Token无效，**When** 请求用户信息，**Then** 返回错误码 AUTH_002（无效Token）

---

### User Story 5 - 更新用户信息 (Priority: P2)

已登录用户可以修改自己的个人信息和联系方式。

**Why this priority**: 用户需求变化时需要能够更新个人信息。

**Independent Test**: 可通过 PUT /api/user/info 接口独立测试，需携带有效 Token。

**Acceptance Scenarios**:

1. **Given** 用户已登录，**When** 提交有效的更新信息（邮箱、手机号等），**Then** 信息更新成功并返回最新用户信息
2. **Given** 提交的新邮箱已被其他用户使用，**When** 更新邮箱，**Then** 返回错误码 USER_007（邮箱已被使用）
3. **Given** 提交空值或格式错误，**When** 更新信息，**Then** 返回对应的字段校验错误

---

### User Story 6 - 修改密码 (Priority: P1)

已登录用户可以修改自己的登录密码。

**Why this priority**: 账户安全的基本能力，用户需要能够主动更换密码。

**Independent Test**: 可通过 PUT /api/user/password 接口独立测试，需携带有效 Token。

**Acceptance Scenarios**:

1. **Given** 用户已登录，**When** 提交正确旧密码和新密码，**Then** 密码修改成功，下一次登录需使用新密码
2. **Given** 旧密码错误，**When** 提交错误旧密码，**Then** 返回错误码 USER_008（旧密码错误）
3. **Given** 新密码不符合规范，**When** 提交弱新密码，**Then** 返回错误码 USER_003（密码不符合规范）

---

### User Story 7 - 用户禁用/启用 (Priority: P2)

管理员可以禁用或重新启用某个用户账户。

**Why this priority**: 管理员需要能够对问题账户进行管控。

**Independent Test**: 可通过 PUT /api/user/{id}/status 接口独立测试，需携带管理员 Token。

**Acceptance Scenarios**:

1. **Given** 用户存在且状态正常，**When** 管理员禁用该用户，**Then** 用户状态变为禁用，该用户无法登录
2. **Given** 用户已被禁用，**When** 管理员启用该用户，**Then** 用户状态变为正常，该用户可重新登录
3. **Given** 操作者非管理员，**When** 尝试禁用/启用用户，**Then** 返回错误码 AUTH_003（权限不足）

---

### User Story 8 - 删除用户 (Priority: P3)

管理员可以删除某个用户账户。

**Why this priority**: 数据清理和账户注销能力，通常不会频繁使用。

**Independent Test**: 可通过 DELETE /api/user/{id} 接口独立测试，需携带管理员 Token。

**Acceptance Scenarios**:

1. **Given** 用户存在，**When** 管理员删除该用户，**Then** 数据库中该用户记录被删除
2. **Given** 用户不存在，**When** 管理员删除该用户，**Then** 返回错误码 USER_001（用户不存在）
3. **Given** 操作者非管理员，**When** 尝试删除用户，**Then** 返回错误码 AUTH_003（权限不足）

---

### User Story 9 - 鉴权与访问控制 (Priority: P1)

系统需要对不同角色的用户进行权限管理，确保用户只能访问被授权的资源。

**Why this priority**: 安全基础能力，防止未授权访问。

**Independent Test**: 可通过不同角色 Token 请求受保护接口独立测试。

**Acceptance Scenarios**:

1. **Given** 普通用户Token，**When** 请求管理员专属接口，**Then** 返回错误码 AUTH_003（权限不足）
2. **Given** 有效Token，**When** 请求需要认证的接口，**Then** 返回正常响应
3. **Given** 无Token或Token缺失，**When** 请求需要认证的接口，**Then** 返回错误码 AUTH_002（无效Token）

---

### Edge Cases

- 注册时用户名包含非法字符（空格、特殊符号）的处理
- 登录时密码错误次数过多是否需要防暴力破解（锁定账户或延迟响应）
- 删除用户时该用户有活跃Session的处理（是否立即失效）
- 修改密码时是否强制该用户所有现有Token失效
- 多数据库场景下，事务边界如何处理
- Session刷新是否支持并发的多个有效Token
- 日志文件中包含敏感信息（密码、Token）的脱敏处理

## Requirements

### Functional Requirements

- **FR-001**: 系统必须提供用户注册功能，支持用户名（唯一）、密码（加密存储）、邮箱、手机号等字段
- **FR-002**: 系统必须提供用户登录功能，验证凭证后返回认证Token，支持用户名或邮箱登录
- **FR-003**: 系统必须提供Token刷新功能，允许在Token过期前获取新Token
- **FR-004**: 系统必须提供获取当前用户信息功能，返回用户完整资料（不包含密码）
- **FR-005**: 系统必须提供更新用户信息功能，支持修改邮箱、手机号等可更新字段
- **FR-006**: 系统必须提供修改密码功能，需验证旧密码，新密码必须符合强度要求
- **FR-007**: 系统必须提供用户禁用功能，被禁用用户无法登录，但数据保留
- **FR-008**: 系统必须提供用户启用功能，将已禁用用户恢复正常状态
- **FR-009**: 系统必须提供删除用户功能，物理删除用户数据及相关联会话
- **FR-010**: 系统必须实现基于角色的访问控制（RBAC），至少包含 ADMIN 和 USER 两种角色
- **FR-011**: 系统必须实现全局异常拦截，对所有未处理异常返回统一格式的错误响应
- **FR-012**: 系统必须记录操作日志，日志文件超过10MB时自动切换，保留最近7天的日志，最多保留10个日志文件
- **FR-013**: 用户密码必须使用BCrypt算法加密存储，盐值从配置文件读取
- **FR-014**: 系统必须提供雪花算法生成全局唯一ID的工具方法
- **FR-015**: 系统必须提供文件读取的工具方法，支持读取配置文件和资源文件
- **FR-016**: 系统必须支持多数据源配置，能够同时连接多个数据库
- **FR-017**: 每个功能模块必须包含对应的单元测试，测试覆盖率达到核心业务逻辑
- **FR-018**: 数据库名必须为 my_tools，系统启动时自动创建（如不存在）
- **FR-019**: 错误码必须统一管理，格式为 `模块_序号`，所有业务异常通过错误码抛出
- **FR-020**: 代码中除注释外不允许出现中文，变量名、方法名、包名均使用英文

### Key Entities

- **User（用户）**: 核心实体，包含用户ID（雪花算法生成）、用户名（唯一）、密码（加密）、邮箱、手机号、角色、状态（正常/禁用）、注册时间、最后登录时间、创建时间、更新时间
- **Role（角色）**: 用户角色，包含角色ID、角色名称（ADMIN/USER）、角色描述、创建时间
- **UserRole（用户角色关联）**: 用户与角色的多对多关联，包含用户ID和角色ID
- **ErrorCode（错误码）**: 错误码定义实体，包含错误码编号、错误码Key、错误描述

## Success Criteria

### Measurable Outcomes

- **SC-001**: 用户可以在30秒内完成注册流程并收到认证Token
- **SC-002**: 用户登录成功率在正常情况下达到99%以上
- **SC-003**: Token刷新操作响应时间在500ms以内
- **SC-004**: 获取用户信息接口响应时间在200ms以内
- **SC-005**: 所有接口在高负载（100并发）下平均响应时间不超过2秒
- **SC-006**: 密码加密存储，数据库中不存储明文密码也无法通过彩虹表还原
- **SC-007**: 日志系统能够正确记录所有关键操作事件，日志文件自动轮转不丢失数据
- **SC-008**: 单元测试覆盖所有public业务方法，关键逻辑路径测试覆盖率达到80%以上
- **SC-009**: 统一的异常处理确保任何未捕获异常都返回格式一致的错误响应，不泄露内部实现细节
- **SC-010**: 多数据源配置下，系统能够正确路由查询到目标数据库

## Assumptions

- **数据库连接**: 目标MySQL服务器地址 192.168.1.8:3306 可达，sales_order 数据库凭证有效
- **Session机制**: 使用 JWT Token 作为无状态认证机制，不使用服务端Session
- **密码策略**: 密码长度至少8位，必须包含大小写字母和数字
- **并发控制**: 同一用户同一时间最多持有3个有效Token
- **管理员账户**: 系统第一个启动时自动创建一个默认管理员账户（用户名：admin，密码由环境变量或配置文件指定）
- **日志存储**: 日志文件存储在项目 logs/ 目录下
- **API风格**: RESTful API，JSON格式请求和响应
- **字符编码**: 统一使用 UTF-8 编码
- **测试策略**: 使用 JUnit 5 + Mockito 进行单元测试，使用 H2 内存数据库进行集成测试
