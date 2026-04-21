# Tasks: Spring Boot User Management Backend

**Input**: Design documents from `specs/001-user-management-backend/`
**Prerequisites**: plan.md, spec.md, data-model.md, contracts/user-api.md, research.md

**Tests**: YES — each user story requires unit tests (per spec requirement: "完善的测试能力")

**Organization**: Tasks are grouped by user story to enable independent implementation and testing

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2...)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Project Initialization)

**Purpose**: Maven project structure, pom.xml, logging, application configs

- [x] T001 Create Maven project directory structure: `src/main/java/com/mytools/`, `src/main/resources/`, `src/test/java/com/mytools/` ✅
- [x] T002 [P] Create pom.xml with Spring Boot 3.x, MyBatis, dynamic-datasource, JJWT, Lombok, test dependencies ✅
- [x] T003 [P] Create `src/main/resources/application.yml` with all config: datasource, JWT, snowflake, password salt, logging path ✅
- [x] T004 [P] Create `src/main/resources/application-dev.yml` for dev profile overrides ✅
- [x] T005 [P] Create `src/main/resources/logback-spring.xml` with SizeAndTimeBasedRollingPolicy: 10MB/7days/totalSizeCap=100MB, async appender, separate SQL logger ✅
- [x] T006 Create `MyToolsApplication.java` Spring Boot main entry class at `com/mytools/MyToolsApplication.java` ✅

---

## Phase 2: Foundational (Core Infrastructure) ⚠️ BLOCKS ALL USER STORIES

**Purpose**: Multi-datasource, security filter, common utilities — required before any user story

- [ ] T007 [P] Create `common/ErrorCode.java` enum: all error codes (USER_001-009, AUTH_001-005, SYS_001-003) with code, message, HttpStatus
- [ ] T008 [P] Create `common/BusinessException.java` base exception class with ErrorCode integration
- [ ] T009 [P] Create `common/Result.java` unified response record: `{code, message, data, traceId, timestamp}`
- [ ] T010 [P] Create `common/GlobalExceptionHandler.java` @RestControllerAdvice: handle BusinessException, MethodArgumentNotValid, AuthenticationException, generic Exception (no internal leak)
- [ ] T011 [P] Create `config/DataSourceConfig.java` using baomidou dynamic-datasource: primary=my_tools, secondary=sales_order, @DS routing
- [ ] T012 [P] Create `utils/PasswordUtils.java` BCrypt encoder/decoder, salt from config property
- [ ] T013 [P] Create `utils/SnowflakeIdGenerator.java`: thread-safe, 41+5+5+12 bits, epoch=TWITTER_EPOCH, clock skew WAIT+random compensate
- [ ] T014 [P] Create `config/SnowflakeConfig.java` Spring @Bean: datacenterId/workerId from properties, enabled toggle
- [ ] T015 [P] Create `utils/FileUtils.java`: read file from classpath/resources, read config file, utility methods
- [ ] T016 Create `auth/utils/JwtUtils.java`: JJWT generate/validate access token (15min) and refresh token (7days), extract claims, isTokenValid, getExpiration
- [ ] T017 Create `auth/filter/JwtAuthenticationFilter.java`: OncePerRequestFilter, parse Bearer token, validate, set SecurityContext
- [ ] T018 Create `config/SecurityConfig.java`: Spring Security filter chain, permit /api/auth/**, all other requests authenticated, JWT filter added

---

## Phase 3: User Story 1 - 用户注册 (Priority: P1) 🎯 MVP

**Goal**: 新用户可以通过用户名、密码、邮箱注册账户，系统返回用户ID和认证Token

**Independent Test**: POST /api/auth/register → 201 + {userId, username, token, expiresIn}

- [ ] T019 [P] [US1] Create `auth/Model/RegisterRequest.java`: username, password, email, phone (with @Valid + Hibernate Validator: username 3-20 alphanumeric, password 8+ mixed case+digits, email format, phone optional)
- [ ] T020 [P] [US1] Create `auth/Model/RegisterResponse.java`: userId, username, token, expiresIn
- [ ] T021 [P] [US1] Create `user/Model/User.java` entity: id (BIGINT PK), username, password, email, phone, role, status, registerTime, lastLoginTime, createTime, updateTime — use @TableName, @Data, Lombok
- [ ] T022 [P] [US1] Create `user/mapper/UserMapper.java` MyBatis @Mapper: insert(User), findByUsername(String), existsByUsername(String), existsByEmail(String)
- [ ] T023 [P] [US1] Create `user/mapper/RoleMapper.java` MyBatis @Mapper: findByRoleName(String), findDefaultUserRole()
- [ ] T024 [P] [US1] Create `user/mapper/UserRoleMapper.java` MyBatis @Mapper: insert(UserRole), findByUserId(Long)
- [ ] T025 [US1] Create `auth/service/AuthService.java` interface: register(RegisterRequest) → RegisterResponse
- [ ] T026 [US1] Create `auth/service/impl/AuthServiceImpl.java`: validate input, check duplicate username/email, BCrypt encode password, save user with default USER role, generate JWT token, return response
- [ ] T027 [US1] Create `auth/controller/AuthController.java`: POST /api/auth/register, @PostMapping, @RequestBody @Valid RegisterRequest, returns ResponseEntity<Result>
- [ ] T028 [US1] Create `src/test/java/com/mytools/auth/service/AuthServiceTest.java`: JUnit5 + Mockito, test register success, duplicate username (USER_002), weak password (USER_003), invalid email (USER_004)
- [ ] T029 [US1] Create `src/test/java/com/mytools/auth/controller/AuthControllerTest.java`: @WebMvcTest + MockMvc, test register endpoint returns 201

---

## Phase 4: User Story 2 - 用户登录 (Priority: P1)

**Goal**: 已注册用户可通过用户名/邮箱+密码登录，返回Token和用户信息

**Independent Test**: POST /api/auth/login → 200 + {userId, username, role, token, expiresIn}

- [ ] T030 [P] [US2] Create `auth/Model/LoginRequest.java`: account (username or email), password
- [ ] T031 [P] [US2] Create `auth/Model/LoginResponse.java`: userId, username, role, token, expiresIn
- [ ] T032 [P] [US2] Create `auth/mapper/TokenMapper.java`: insert(Token), findByToken(String), deleteByUserId(Long), countByUserId(Long)
- [ ] T033 [P] [US2] Create `auth/Model/Token.java` entity: id, userId, token, deviceInfo, expiresAt, createTime — with @TableName
- [ ] T034 [US2] Create `auth/service/AuthService.java` add method: login(LoginRequest) → LoginResponse
- [ ] T035 [US2] Update `AuthServiceImpl.java`: implement login — findByUsernameOrEmail, BCrypt matches, update lastLoginTime, save Token record, generate JWT, enforce max 3 tokens per user
- [ ] T036 [US2] Update `AuthController.java`: POST /api/auth/login, @Valid LoginRequest
- [ ] T037 [US2] Create `src/test/java/com/mytools/auth/service/AuthServiceTest.java` add: test login success, wrong password (USER_005), disabled account (USER_006), non-existent user (USER_005)
- [ ] T038 [US2] Create `src/test/java/com/mytools/auth/controller/AuthControllerTest.java` add: test login endpoint returns 200, wrong credentials returns 401

---

## Phase 5: User Story 3 - Session刷新 (Priority: P2)

**Goal**: 已登录用户可在Token过期前刷新，获得新Token

**Independent Test**: POST /api/auth/refresh with Bearer token → 200 + {token, expiresIn}

- [ ] T039 [P] [US3] Create `auth/Model/RefreshResponse.java`: token, expiresIn
- [ ] T040 [P] [US3] Update `JwtUtils.java`: add method generateAccessTokenFromClaims(Map), extractExpiration(token)
- [ ] T041 [US3] Create `auth/service/AuthService.java` add method: refreshToken(String oldToken) → RefreshResponse
- [ ] T042 [US3] Update `AuthServiceImpl.java`: implement refresh — validate old token, check not expired (AUTH_001), blacklist old token (optional), generate new access token, save new Token record
- [ ] T043 [US3] Update `AuthController.java`: POST /api/auth/refresh, @RequestHeader Authorization Bearer, returns new token
- [ ] T044 [US3] Create `src/test/java/com/mytools/auth/service/AuthServiceTest.java` add: test refresh success, expired token (AUTH_001), invalid token (AUTH_002)
- [ ] T045 [US3] Create `src/test/java/com/mytools/auth/controller/AuthControllerTest.java` add: test refresh endpoint returns 200, expired token returns 401

---

## Phase 6: User Story 4 - 获取用户信息 (Priority: P2)

**Goal**: 已登录用户可查看自己的账户信息

**Independent Test**: GET /api/user/info with Bearer token → 200 + {userId, username, email, phone, role, status, registerTime, lastLoginTime}

- [ ] T046 [P] [US4] Create `user/Model/UserInfoResponse.java`: userId, username, email, phone, role, status, registerTime, lastLoginTime
- [ ] T047 [P] [US4] Update `user/mapper/UserMapper.java`: add findById(Long), updateLastLoginTime(Long, LocalDateTime)
- [ ] T048 [US4] Create `user/service/UserService.java`: getUserInfo(Long userId) → UserInfoResponse
- [ ] T049 [US4] Create `user/service/impl/UserServiceImpl.java`: fetch user by id, map to UserInfoResponse (exclude password)
- [ ] T050 [US4] Create `user/controller/UserController.java`: GET /api/user/info, @GetMapping, extract userId from SecurityContext, returns user info
- [ ] T051 [US4] Create `src/test/java/com/mytools/user/service/UserServiceTest.java`: JUnit5 + Mockito, test getUserInfo success, user not found (USER_001)
- [ ] T052 [US4] Create `src/test/java/com/mytools/user/controller/UserControllerTest.java`: @WebMvcTest, test info endpoint returns 200 with user data, no auth returns 401

---

## Phase 7: User Story 5 - 更新用户信息 (Priority: P2)

**Goal**: 已登录用户可修改邮箱、手机号

**Independent Test**: PUT /api/user/info with Bearer token + JSON body → 200 + updated UserInfoResponse

- [ ] T053 [P] [US5] Create `user/Model/UpdateUserInfoRequest.java`: email (optional), phone (optional), with @Valid
- [ ] T054 [P] [US5] Update `user/mapper/UserMapper.java`: add updateEmail(Long, String), updatePhone(Long, String), findByEmail(String)
- [ ] T055 [US5] Create `user/service/UserService.java` add method: updateUserInfo(Long userId, UpdateUserInfoRequest) → UserInfoResponse
- [ ] T056 [US5] Update `UserServiceImpl.java`: implement update — validate email uniqueness (USER_007), apply updates, return updated info
- [ ] T057 [US5] Update `UserController.java`: PUT /api/user/info, @PutMapping, @RequestBody @Valid UpdateUserInfoRequest
- [ ] T058 [US5] Create `src/test/java/com/mytools/user/service/UserServiceTest.java` add: test update success, email already taken (USER_007)
- [ ] T059 [US5] Create `src/test/java/com/mytools/user/controller/UserControllerTest.java` add: test update endpoint returns 200, email conflict returns 409

---

## Phase 8: User Story 6 - 修改密码 (Priority: P1)

**Goal**: 已登录用户可修改自己的密码，需验证旧密码

**Independent Test**: PUT /api/user/password with Bearer token + JSON → 200

- [ ] T060 [P] [US8] Create `user/Model/ChangePasswordRequest.java`: oldPassword, newPassword (with validation same as registration)
- [ ] T061 [P] [US8] Update `user/mapper/UserMapper.java`: add updatePassword(Long, String BCrypt encoded)
- [ ] T062 [US8] Create `user/service/UserService.java` add method: changePassword(Long userId, ChangePasswordRequest)
- [ ] T063 [US8] Update `UserServiceImpl.java`: implement changePassword — verify oldPassword matches (USER_008), BCrypt encode new password, update, optionally invalidate all existing tokens
- [ ] T064 [US8] Update `UserController.java`: PUT /api/user/password, @PutMapping, @RequestBody @Valid ChangePasswordRequest
- [ ] T065 [US8] Create `src/test/java/com/mytools/user/service/UserServiceTest.java` add: test changePassword success, wrong old password (USER_008), weak new password (USER_003)
- [ ] T066 [US8] Create `src/test/java/com/mytools/user/controller/UserControllerTest.java` add: test password change returns 200, wrong old password returns 400

---

## Phase 9: User Story 7 - 用户禁用/启用 (Priority: P2)

**Goal**: 管理员可禁用或启用某用户账户

**Independent Test**: PUT /api/user/{id}/status with ADMIN Bearer token + JSON → 200 + {userId, status}

- [ ] T067 [P] [US9] Create `user/Model/UpdateStatusRequest.java`: status (ACTIVE or DISABLED)
- [ ] T068 [P] [US9] Update `user/mapper/UserMapper.java`: add updateStatus(Long, String), findById(Long)
- [ ] T069 [US9] Create `user/service/UserService.java` add method: updateUserStatus(Long targetUserId, String status, Long adminUserId)
- [ ] T070 [US9] Update `UserServiceImpl.java`: implement updateUserStatus — verify admin role, target user exists (USER_001), valid status transition (USER_009), update status
- [ ] T071 [US9] Update `UserController.java`: PUT /api/user/{id}/status, @PutMapping, @PathVariable id, @RequestBody UpdateStatusRequest, @PreAuthorize("hasRole('ADMIN')")
- [ ] T072 [US9] Create `src/test/java/com/mytools/user/service/UserServiceTest.java` add: test disable success, enable success, non-admin access (AUTH_003), user not found (USER_001)
- [ ] T073 [US9] Create `src/test/java/com/mytools/user/controller/UserControllerTest.java` add: test admin disable returns 200, non-admin disable returns 403

---

## Phase 10: User Story 8 - 删除用户 (Priority: P3)

**Goal**: 管理员可删除某用户账户及其关联数据

**Independent Test**: DELETE /api/user/{id} with ADMIN Bearer token → 200

- [ ] T074 [P] [US10] Update `user/mapper/UserMapper.java`: add deleteById(Long)
- [ ] T075 [P] [US10] Update `auth/mapper/TokenMapper.java`: add deleteByUserId(Long)
- [ ] T076 [P] [US10] Update `user/mapper/UserRoleMapper.java`: add deleteByUserId(Long)
- [ ] T077 [US10] Create `user/service/UserService.java` add method: deleteUser(Long targetUserId, Long adminUserId)
- [ ] T078 [US10] Update `UserServiceImpl.java`: implement deleteUser — verify admin role, user exists (USER_001), delete tokens, delete user roles, delete user
- [ ] T079 [US10] Update `UserController.java`: DELETE /api/user/{id}, @DeleteMapping, @PathVariable id, @PreAuthorize("hasRole('ADMIN')")
- [ ] T080 [US10] Create `src/test/java/com/mytools/user/service/UserServiceTest.java` add: test delete success, non-admin access (AUTH_003), user not found (USER_001)
- [ ] T081 [US10] Create `src/test/java/com/mytools/user/controller/UserControllerTest.java` add: test admin delete returns 200, non-admin delete returns 403

---

## Phase 11: User Story 9 - 鉴权与访问控制 (Priority: P1)

**Goal**: 系统实现RBAC，确保用户只能访问授权资源，管理员可访问所有资源

**Independent Test**: Non-admin accessing admin endpoint → 403; Access without token → 401; Valid token → 200

- [ ] T082 [P] [US11] Create `config/SecurityConfig.java` update: configure role-based access, @PreAuthorize annotations on admin endpoints, method security enabled
- [ ] T083 [P] [US11] Create `auth/filter/JwtAuthenticationFilter.java` update: load user authorities from DB roles, set to Authentication
- [ ] T084 [P] [US11] Create `auth/utils/JwtUtils.java` update: add getAuthoritiesFromToken(String token) method
- [ ] T085 [US11] Update `GlobalExceptionHandler.java`: handle AccessDeniedException → return AUTH_003
- [ ] T086 [US11] Update `GlobalExceptionHandler.java`: handle AuthenticationException → return AUTH_002
- [ ] T087 [US11] Create `src/test/java/com/mytools/auth/AuthIntegrationTest.java`: @SpringBootTest, test full auth flow: register → login → access protected endpoint → refresh → logout
- [ ] T088 [US11] Create `src/test/java/com/mytools/auth/RbacTest.java`: @WebMvcTest, test non-admin cannot access admin endpoints (403), no token returns (401), valid token passes (200)

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Integration validation, configuration cleanup, docs

- [ ] T089 [P] Create MyBatis XML mapper files in `src/main/resources/mapper/user/` and `mapper/auth/`: UserMapper.xml, TokenMapper.xml, RoleMapper.xml, UserRoleMapper.xml
- [ ] T090 [P] Create `src/test/resources/application.yml` for test profile: H2 in-memory datasource, JWT test secret, snowflake disabled
- [ ] T091 [P] Create `src/test/resources/schema.sql`: H2 schema for integration tests
- [ ] T092 Run `mvn compile` — fix any compilation errors
- [ ] T093 Run `mvn test` — fix any test failures
- [ ] T094 Verify logback-spring.xml: confirm 10MB/7days/10files rolling works via unit test or manual inspection
- [ ] T095 [P] Update AGENTS.md with any new conventions discovered during implementation
- [ ] T096 Run quickstart.md validation: test all 9 endpoints with curl/http client

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — starts immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all user stories
- **Phases 3-11 (User Stories)**: All depend on Phase 2
  - US1, US2, US3, US4, US5, US6, US7, US8, US9 can proceed in parallel after Phase 2
  - Within each US: Models → Services → Controllers → Tests
- **Phase 12 (Polish)**: Depends on all desired user stories complete

### User Story Dependencies

| Story | Depends On | Reason |
|-------|-----------|--------|
| US1 (注册) | Phase 2 | Needs UserMapper, PasswordUtils, JwtUtils |
| US2 (登录) | Phase 2 + US1 (UserMapper) | Needs UserMapper (from US1), TokenMapper |
| US3 (刷新) | Phase 2 + US2 (TokenMapper) | Needs TokenMapper (from US2), JwtUtils |
| US4 (获取信息) | Phase 2 + US1 (User) | Needs User entity (from US1) |
| US5 (更新信息) | Phase 2 + US4 (UserService) | Needs UserMapper (from US4) |
| US6 (改密码) | Phase 2 + US1 (UserMapper) | Needs UserMapper (from US1) |
| US7 (禁用/启用) | Phase 2 + US1 (User) | Needs User entity (from US1) |
| US8 (删除) | Phase 2 + US1 (User) + US7 | Needs User entity, Token cleanup |
| US9 (RBAC) | Phase 2 + US1 | Needs role/permission infrastructure |

### Parallel Opportunities

- T007-T010 (common layer): all [P] — parallel
- T011-T015 (config + utils): all [P] — parallel
- T019-T023 (US1 models/mappers): all [P] — parallel
- T030-T033 (US2 models/mappers): all [P] — parallel
- Once Phase 2 complete, US1-US9 can ALL start in parallel (9 parallel tracks)

### Implementation Strategy

**MVP First (US1 + US2 only — minimum deliverable)**:

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL)
3. Complete Phase 3: US1 (注册)
4. Complete Phase 4: US2 (登录)
5. **STOP and VALIDATE**: register + login flow works end-to-end
6. Deploy/demo if ready

**Full Delivery (all 9 user stories)**:
1. Complete Phase 1 + Phase 2
2. Complete Phases 3-11 (all user stories, can parallelize)
3. Phase 12: Polish & cross-cutting
4. Full test suite run
5. Deploy

### Notes

- [P] tasks = different files, no dependencies — always prefer parallel execution
- [Story] label maps task to specific user story for traceability
- Each user story is independently testable via its /api endpoint
- Unit tests use Mockito + @ExtendWith(MockitoExtension.class)
- Integration tests use @SpringBootTest with H2 in-memory database
- All code comments in Chinese (Chinese punctuation: 。)
- All public methods require Javadoc comments
