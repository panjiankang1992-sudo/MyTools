# Research: Spring Boot User Management Backend

**Date**: 2026-04-21 | **Feature**: 001-user-management-backend

## Decision Summary

| Topic | Decision | Key Library/Approach |
|-------|----------|---------------------|
| Multi-datasource | baomidou dynamic-datasource | `@DS` annotation routing |
| JWT Authentication | JJWT + refresh token rotation | `jjwt` library |
| Global Exception | `@RestControllerAdvice` + `ErrorCode` enum | Spring built-in |
| Log Rotation | SizeAndTimeBasedRollingPolicy | logback-spring.xml |
| Testing | JUnit 5 + Mockito + `@WebMvcTest` + `@MybatisTest` | Spring Boot Test |
| Snowflake ID | Custom implementation with clock skew handling | No external dependency |

---

## 1. Multi-Datasource Configuration

**Decision**: Use `baomidou/dynamic-datasource-spring-boot3-starter`

**Rationale**: Clean `@DS` annotation API for routing, built-in `@DSTransactional` for cross-datasource transactions, simpler than manual configuration classes.

**Key Code**:
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>dynamic-datasource-spring-boot3-starter</artifactId>
    <version>4.5.0</version>
</dependency>
```

```yaml
spring:
  datasource:
    dynamic:
      primary: my_tools
      datasource:
        my_tools:
          url: jdbc:mysql://192.168.1.8:3306/my_tools?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC
          username: root
          password: YUyutian/1015
        sales_order:
          url: jdbc:mysql://192.168.1.8:3306/sales_order?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC
          username: root
          password: YUyutian/1015
```

**Routing**: `@DS("my_tools")` or `@DS("sales_order")` on mapper interface or service class.

**Alternative rejected**: Manual `SqlSessionFactory` per datasource — more boilerplate, requires separate packages.

---

## 2. JWT Authentication

**Decision**: JJWT library + short-lived access token (15min) + refresh token (7 days) with rotation

**Rationale**: Access token 短生命周期减少被盗后的窗口期；refresh token rotation 检测 token 复用攻击。

**Key Components**:
- `JwtService`: token generation, validation, claim extraction
- `JwtAuthenticationFilter`: intercepts requests, validates token, sets SecurityContext
- `TokenBlocklistService`: Redis-based jti blacklist for immediate revocation
- `RefreshTokenService`: rotation with reuse detection

**Secret Key**: Base64-encoded 256-bit key from environment variable. Minimum 44 char Base64 string.

**Token Payload**:
```json
{
  "sub": "1234567890123456789",
  "username": "testuser",
  "roles": ["USER"],
  "jti": "uuid-for-revocation",
  "iat": 1713683200,
  "exp": 1713684100
}
```

**What NOT in JWT**: passwords, credit card numbers, full profile data.

---

## 3. Global Exception Handling

**Decision**: `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`

**Key Components**:
- `GlobalExceptionHandler`: `@RestControllerAdvice` with override for validation errors
- `ErrorResponse`: record with errorCode, message, path, traceId, timestamp
- `BusinessException`: base custom exception with ErrorCode integration
- `CustomAuthenticationEntryPoint` + `CustomAccessDeniedHandler`: for Spring Security 401/403

**ErrorCode Enum**:
```java
public enum ErrorCode {
    USER_NOT_FOUND("USER_001", "用户不存在", HttpStatus.NOT_FOUND),
    USER_002("USER_002", "用户名已存在", HttpStatus.CONFLICT),
    // ...
}
```

**Never expose in production**: stack traces, SQL errors, internal exception messages.

---

## 4. Logback Log Rotation

**Decision**: `SizeAndTimeBasedRollingPolicy` with `maxHistory=7`, `maxFileSize=10MB`, `totalSizeCap=100MB`

**Key Configuration** (`logback-spring.xml`):
```xml
<rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>${LOG_DIR}/${APP_NAME}.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
    <maxFileSize>10MB</maxFileSize>
    <maxHistory>7</maxHistory>
    <totalSizeCap>100MB</totalSizeCap>
    <cleanHistoryOnStart>true</cleanHistoryOnStart>
</rollingPolicy>
```

**Output Files**:
- `logs/application.log` — main application log
- `logs/sql.log` — MyBatis SQL log (separate)
- `logs/access.log` — HTTP access log

**Async Appender**: `queueSize=512`, `discardingThreshold=0`, `neverBlock=true`.

---

## 5. Testing Patterns

**Service Layer**: `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`
- Fast, no Spring context
- Mock mapper dependencies

**Controller Layer**: `@WebMvcTest` + `SecurityMockMvcRequestPostProcessors.jwt()`
- MVC slice test
- Mock service with `@MockBean`

**Mapper Integration**: `@MybatisTest` + H2 in-memory database
- Lightweight MyBatis test slice
- `@Transactional` with auto-rollback

**Full Integration**: `@SpringBootTest` + `@AutoConfigureTestDatabase`
- Full context
- H2 replaces real datasource

**Test Dependencies**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
</dependency>
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter-test</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 6. Snowflake ID Generator

**Decision**: Custom implementation with clock skew handling (WAIT + RANDOM_COMPENSATE fallback)

**ID Structure** (64-bit):
```
| 1bit sign | 41bits timestamp | 5bits datacenter | 5bits worker | 12bits sequence |
```

**Key Features**:
- Thread-safe via `ReentrantLock`
- Clock backward handling: wait < 100ms, random compensate >= 100ms
- Epoch: 1288834974657L (Twitter 2010-11-04)
- DC ID + Worker ID from config (0-31 each)

**Configuration**:
```yaml
snowflake:
  datacenter-id: 1
  worker-id: 1
  enabled: true
```

---

## 7. Key Dependencies (pom.xml)

```xml
<!-- Spring Boot 3.x -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- MyBatis + Dynamic Datasource -->
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.4</version>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>dynamic-datasource-spring-boot3-starter</artifactId>
    <version>4.5.0</version>
</dependency>

<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>

<!-- Database -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Utilities -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Testing -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```
