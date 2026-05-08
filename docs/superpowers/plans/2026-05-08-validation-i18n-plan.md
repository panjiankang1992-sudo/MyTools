# 表单校验与错误国际化优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现前后端统一的表单校验机制与错误消息国际化

**Architecture:** 后端采用 MessageSource + properties 文件实现国际化，前端通过错误码映射表展示本地化错误。错误码统一为 5 位数字格式，按模块分段。

**Tech Stack:** Spring Boot 3.x (MessageSource), NaiveUI, TypeScript, async-validator

---

## 文件结构

```
src/main/resources/
├── messages.properties              # 默认消息
├── messages_zh_CN.properties         # 中文消息
└── messages_en.properties           # 英文消息

src/main/java/com/yuyutian/mytools/
├── common/
│   ├── ErrorCode.java               # 改造：新增 messageKey 字段
│   ├── BusinessException.java       # 改造：支持 messageKey 和 fieldErrors
│   ├── GlobalExceptionHandler.java  # 改造：返回国际化消息
│   └── Result.java                  # 改造：支持 messageKey

webapp/src/
├── service/
│   ├── error-code.ts                # 新建：错误码映射表
│   └── request/
│       ├── index.ts                 # 改造：支持字段级错误
│       └── shared.ts                # 改造：支持国际化消息
└── views/system/user/index.vue      # 改造：添加前端校验
```

---

## 阶段一：后端国际化基础设施

### Task 1: 创建国际化消息配置文件

**Files:**
- Create: `src/main/resources/messages.properties`
- Create: `src/main/resources/messages_zh_CN.properties`
- Create: `src/main/resources/messages_en.properties`

- [ ] **Step 1: 创建 messages.properties (默认英文)**

```properties
# Default messages (English)
# User errors
user.not_found=User not found
user.username.exists=Username already exists
user.password.invalid=Password must contain uppercase, lowercase letters and numbers
user.email.format.invalid=Email format is invalid
user.username.or.password.wrong=Username or password is incorrect
user.account.disabled=Account has been disabled
user.email.exists=Email has already been used
user.old.password.wrong=Old password is incorrect
user.status.invalid=Invalid user status

# Auth errors
auth.token.expired=Token has expired
auth.token.invalid=Invalid token
auth.permission.denied=Permission denied
auth.token.format.error=Token format error
auth.account.locked=Account has been locked

# File errors
file.not_found=File not found
file.preview.unsupported=File type does not support preview
file.delete.failed=File deletion failed
file.filename.exists=Filename already exists
file.path.invalid=Invalid file path
file.tag.not_found=Tag not found
file.tag.name.exists=Tag name already exists
file.tagging.service.unavailable=Tagging service is unavailable
file.type.unsupported=File type not supported
file.dir.not_found=Directory not found or access denied
file.scan.in_progress=Scan task is in progress

# Token errors
token.not_found=Token not found
token.operation.denied=No permission to operate this token
token.name.invalid=Token name is invalid
token.disabled=Token has been disabled
token.verify.failed=Token verification failed

# System errors
sys.server.error=Internal server error
sys.validation.failed=Parameter validation failed
sys.database.error=Database operation failed
```

- [ ] **Step 2: 创建 messages_zh_CN.properties (中文)**

```properties
# 用户错误
user.not_found=用户不存在
user.username.exists=用户名已存在
user.password.invalid=密码必须包含大小写字母和数字
user.email.format.invalid=邮箱格式不正确
user.username.or.password.wrong=用户名或密码错误
user.account.disabled=账户已禁用
user.email.exists=邮箱已被使用
user.old.password.wrong=旧密码错误
user.status.invalid=无效的用户状态

# 认证错误
auth.token.expired=Token已过期
auth.token.invalid=无效Token
auth.permission.denied=权限不足
auth.token.format.error=Token格式错误
auth.account.locked=账户已锁定

# 文件错误
file.not_found=文件不存在
file.preview.unsupported=文件类型不支持预览
file.delete.failed=文件删除失败
file.filename.exists=文件名已存在
file.path.invalid=无效的文件路径
file.tag.not_found=标签不存在
file.tag.name.exists=标签名称已存在
file.tagging.service.unavailable=打标签服务不可用
file.type.unsupported=文件类型不支持
file.dir.not_found=目录不存在或无权限访问
file.scan.in_progress=扫描任务执行中

# Token错误
token.not_found=Token不存在
token.operation.denied=无权限操作此Token
token.name.invalid=Token名称无效
token.disabled=Token已禁用
token.verify.failed=Token验证失败

# 系统错误
sys.server.error=服务器内部错误
sys.validation.failed=参数校验失败
sys.database.error=数据库操作失败
```

- [ ] **Step 3: 创建 messages_en.properties (英文)**

```properties
# User errors
user.not_found=User not found
user.username.exists=Username already exists
user.password.invalid=Password must contain uppercase, lowercase letters and numbers
user.email.format.invalid=Email format is invalid
user.username.or.password.wrong=Username or password is incorrect
user.account.disabled=Account has been disabled
user.email.exists=Email has already been used
user.old.password.wrong=Old password is incorrect
user.status.invalid=Invalid user status

# Auth errors
auth.token.expired=Token has expired
auth.token.invalid=Invalid token
auth.permission.denied=Permission denied
auth.token.format.error=Token format error
auth.account.locked=Account has been locked

# File errors
file.not_found=File not found
file.preview.unsupported=File type does not support preview
file.delete.failed=File deletion failed
file.filename.exists=Filename already exists
file.path.invalid=Invalid file path
file.tag.not_found=Tag not found
file.tag.name.exists=Tag name already exists
file.tagging.service.unavailable=Tagging service is unavailable
file.type.unsupported=File type not supported
file.dir.not_found=Directory not found or access denied
file.scan.in_progress=Scan task is in progress

# Token errors
token.not_found=Token not found
token.operation.denied=No permission to operate this token
token.name.invalid=Token name is invalid
token.disabled=Token has been disabled
token.verify.failed=Token verification failed

# System errors
sys.server.error=Internal server error
sys.validation.failed=Parameter validation failed
sys.database.error=Database operation failed
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/messages*.properties
git commit -m "feat: add i18n message properties files"
```

---

### Task 2: 重构 ErrorCode 枚举

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/common/ErrorCode.java`

- [ ] **Step 1: 读取现有 ErrorCode.java 确认当前结构**

- [ ] **Step 2: 重构 ErrorCode 枚举为新格式**

```java
package com.yuyutian.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Error code enum.
 * Unified management of all business error codes, format: 5-digit numeric code.
 * Each error code contains code number, message key, and corresponding HTTP status.
 */
@Getter
public enum ErrorCode {
    // User error codes (10001-10099)
    USER_001("10001", "user.not_found", HttpStatus.NOT_FOUND),
    USER_002("10002", "user.username.exists", HttpStatus.CONFLICT),
    USER_003("10003", "user.password.invalid", HttpStatus.BAD_REQUEST),
    USER_004("10004", "user.email.format.invalid", HttpStatus.BAD_REQUEST),
    USER_005("10005", "user.username.or.password.wrong", HttpStatus.UNAUTHORIZED),
    USER_006("10006", "user.account.disabled", HttpStatus.FORBIDDEN),
    USER_007("10007", "user.email.exists", HttpStatus.CONFLICT),
    USER_008("10008", "user.old.password.wrong", HttpStatus.BAD_REQUEST),
    USER_009("10009", "user.status.invalid", HttpStatus.BAD_REQUEST),

    // Auth error codes (20001-20099)
    AUTH_001("20001", "auth.token.expired", HttpStatus.UNAUTHORIZED),
    AUTH_002("20002", "auth.token.invalid", HttpStatus.UNAUTHORIZED),
    AUTH_003("20003", "auth.permission.denied", HttpStatus.FORBIDDEN),
    AUTH_004("20004", "auth.token.format.error", HttpStatus.UNAUTHORIZED),
    AUTH_005("20005", "auth.account.locked", HttpStatus.FORBIDDEN),

    // File error codes (30001-30099)
    FILE_001("30001", "file.not_found", HttpStatus.NOT_FOUND),
    FILE_002("30002", "file.preview.unsupported", HttpStatus.BAD_REQUEST),
    FILE_003("30003", "file.delete.failed", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_004("30004", "file.filename.exists", HttpStatus.CONFLICT),
    FILE_005("30005", "file.path.invalid", HttpStatus.BAD_REQUEST),
    FILE_006("30006", "file.tag.not_found", HttpStatus.NOT_FOUND),
    FILE_007("30007", "file.tag.name.exists", HttpStatus.CONFLICT),
    FILE_008("30008", "file.tagging.service.unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    FILE_009("30009", "file.type.unsupported", HttpStatus.BAD_REQUEST),
    FILE_010("30010", "file.dir.not_found", HttpStatus.NOT_FOUND),
    FILE_011("30011", "file.scan.in_progress", HttpStatus.CONFLICT),

    // Token error codes (40001-40099)
    TOKEN_001("40001", "token.not_found", HttpStatus.NOT_FOUND),
    TOKEN_002("40002", "token.operation.denied", HttpStatus.FORBIDDEN),
    TOKEN_003("40003", "token.name.invalid", HttpStatus.BAD_REQUEST),
    TOKEN_004("40004", "token.disabled", HttpStatus.BAD_REQUEST),
    TOKEN_005("40005", "token.verify.failed", HttpStatus.BAD_REQUEST),

    // System error codes (50001-50099)
    SYS_001("50001", "sys.server.error", HttpStatus.INTERNAL_SERVER_ERROR),
    SYS_002("50002", "sys.validation.failed", HttpStatus.BAD_REQUEST),
    SYS_003("50003", "sys.database.error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/common/ErrorCode.java
git commit -m "refactor: migrate ErrorCode to numeric format with message keys"
```

---

### Task 3: 改造 BusinessException

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/common/BusinessException.java`

- [ ] **Step 1: 读取现有 BusinessException.java**

- [ ] **Step 2: 改造 BusinessException 支持 messageKey 和 fieldErrors**

```java
package com.yuyutian.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business exception.
 * Contains error code, message key for i18n, HTTP status, and field-level errors.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;
    private final Map<String, String> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessageKey());
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.httpStatus = errorCode.getHttpStatus();
        this.fieldErrors = null;
    }

    public BusinessException(ErrorCode errorCode, Map<String, String> fieldErrors) {
        super(errorCode.getMessageKey());
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.httpStatus = errorCode.getHttpStatus();
        this.fieldErrors = fieldErrors;
    }

    public BusinessException(String code, String messageKey, HttpStatus httpStatus) {
        super(messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
        this.fieldErrors = null;
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/common/BusinessException.java
git commit -m "refactor: BusinessException supports messageKey and fieldErrors"
```

---

### Task 4: 完善 GlobalExceptionHandler

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/common/GlobalExceptionHandler.java`

- [ ] **Step 1: 读取现有 GlobalExceptionHandler.java**

- [ ] **Step 2: 注入 MessageSource 并改造异常处理逻辑**

```java
package com.yuyutian.mytools.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler.
 * Unified handling of all exceptions thrown by Controller.
 * Returns internationally formatted error responses.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * Get internationalized message by message key.
     */
    private String getMessage(String messageKey) {
        try {
            return messageSource.getMessage(messageKey, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            log.warn("Failed to get i18n message for key: {}", messageKey);
            return messageKey;
        }
    }

    /**
     * Handle business exception.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business exception: code={}, messageKey={}, path={}", ex.getCode(), ex.getMessageKey(), request.getRequestURI());
        Result<Void> result = Result.error(ex.getCode(), getMessage(ex.getMessageKey()), ex.getFieldErrors());
        return ResponseEntity.status(ex.getHttpStatus()).body(result);
    }

    /**
     * Handle validation exception.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String messageKey = error.getDefaultMessage();
            // Default message from @NotBlank etc. may be message key or display text
            // If it contains Chinese, treat as display text directly
            // Otherwise treat as message key for i18n
            String displayMessage = isChinese(messageKey) ? messageKey : getMessage(messageKey);
            fieldErrors.put(fieldName, displayMessage);
        });
        log.warn("Validation exception: errors={}, path={}", fieldErrors, request.getRequestURI());
        Result<Map<String, String>> result = Result.error(
                "50002",
                getMessage("sys.validation.failed"),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * Handle authentication exception.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication exception: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_002.getCode(), getMessage(ErrorCode.AUTH_002.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    /**
     * Handle access denied exception.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_003.getCode(), getMessage(ErrorCode.AUTH_003.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: type={}, message={}, path={}", ex.getClass().getName(), ex.getMessage(), request.getRequestURI(), ex);
        Result<Void> result = Result.error(ErrorCode.SYS_001.getCode(), getMessage(ErrorCode.SYS_001.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    /**
     * Check if string contains Chinese characters.
     */
    private boolean isChinese(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches(".*[\\u4e00-\\u9fa5].*");
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/common/GlobalExceptionHandler.java
git commit -m "refactor: GlobalExceptionHandler supports i18n and field errors"
```

---

### Task 5: 改造 Result 类

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/common/Result.java`

- [ ] **Step 1: 读取现有 Result.java**

- [ ] **Step 2: 改造 Result 支持 fieldErrors**

```java
package com.yuyutian.mytools.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Unified response format.
 * All API responses use this format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** Error code, 200 means success */
    private int code;

    /** Response message (i18n key or display text) */
    private String message;

    /** Response data */
    private T data;

    /** Field-level errors (used in validation errors) */
    private Map<String, String> fieldErrors;

    /** Trace ID for log correlation */
    private String traceId;

    /** Response timestamp */
    private LocalDateTime timestamp;

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(String code, String message, Map<String, String> fieldErrors) {
        return new Result<>(parseCode(code), message, null, fieldErrors, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<>(parseCode(code), message, null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getHttpStatus().value(), errorCode.getMessageKey(), null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(BusinessException e) {
        return new Result<>(e.getHttpStatus().value(), e.getMessageKey(), null, e.getFieldErrors(), generateTraceId(), LocalDateTime.now());
    }

    private static int parseCode(String code) {
        try {
            return Integer.parseInt(code);
        } catch (Exception e) {
            return 50000;
        }
    }

    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/common/Result.java
git commit -m "refactor: Result supports fieldErrors"
```

---

## 阶段二：后端模块适配

### Task 6: 适配 User 模块

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/yuyutian/mytools/user/controller/UserController.java`

- [ ] **Step 1: 读取 UserServiceImpl.java，确认 throw new BusinessException 的位置**

- [ ] **Step 2: 适配 UserServiceImpl，将错误消息替换为 ErrorCode**

例如：
```java
// Before
throw new BusinessException("用户不存在");

// After
throw new BusinessException(ErrorCode.USER_001);
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/user/service/impl/UserServiceImpl.java
git commit -m "refactor: UserServiceImpl uses ErrorCode enum"
```

### Task 7: 适配 Auth 模块

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/auth/service/impl/AuthServiceImpl.java`

- [ ] **Step 1: 读取 AuthServiceImpl.java**

- [ ] **Step 2: 适配 AuthServiceImpl，将错误消息替换为 ErrorCode**

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/auth/service/impl/AuthServiceImpl.java
git commit -m "refactor: AuthServiceImpl uses ErrorCode enum"
```

### Task 8: 适配 File 模块（本地文件/标签/目录）

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/localfile/service/LocalFileService.java`
- Modify: `src/main/java/com/yuyutian/mytools/localfile/service/tagging/TaggerService.java`
- Modify: `src/main/java/com/yuyutian/mytools/localfile/controller/LocalFileController.java`

- [ ] **Step 1: 读取上述文件，确认错误抛出位置**

- [ ] **Step 2: 适配 File 模块相关服务**

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/localfile/
git commit -m "refactor: LocalFile module uses ErrorCode enum"
```

### Task 9: 适配 Token 模块

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/token/service/impl/TokenManagementServiceImpl.java`
- Modify: `src/main/java/com/yuyutian/mytools/token/controller/TokenController.java`

- [ ] **Step 1: 读取上述文件**

- [ ] **Step 2: 适配 Token 模块**

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/token/
git commit -m "refactor: Token module uses ErrorCode enum"
```

### Task 10: 移除微信相关代码和错误码

**Files:**
- Delete: Any remaining Wechat-related files (根据当前代码库确认)

- [ ] **Step 1: 搜索微信相关代码**

```bash
grep -r "wechat\|Wechat\|微信" --include="*.java" src/
```

- [ ] **Step 2: 移除微信相关代码和错误码**

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "refactor: remove wechat module"
```

---

## 阶段三：前端基础设施

### Task 11: 创建错误码映射表

**Files:**
- Create: `webapp/src/service/error-code.ts`

- [ ] **Step 1: 创建错误码映射表**

```typescript
import type { Locale } from 'naive-ui';

export interface ErrorCodeConfig {
  /** Whether to show as modal */
  isModal: boolean;
  /** Whether to trigger logout */
  isLogout: boolean;
}

export const ErrorCodeConfigMap: Record<string, ErrorCodeConfig> = {
  // Auth errors - require modal/logout
  '20001': { isModal: true, isLogout: true },  // token expired
  '20002': { isModal: true, isLogout: true },  // token invalid
  '20003': { isModal: false, isLogout: false }, // permission denied
  '20005': { isModal: true, isLogout: true },  // account locked
  // Token errors
  '40001': { isModal: false, isLogout: false },
  '40002': { isModal: false, isLogout: false },
};

export const ErrorMessageMap: Record<Locale['zh-CN'] | 'en', Record<string, string>> = {
  'zh-CN': {
    // User errors
    'user.not_found': '用户不存在',
    'user.username.exists': '用户名已存在',
    'user.password.invalid': '密码必须包含大小写字母和数字',
    'user.email.format.invalid': '邮箱格式不正确',
    'user.username.or.password.wrong': '用户名或密码错误',
    'user.account.disabled': '账户已禁用',
    'user.email.exists': '邮箱已被使用',
    'user.old.password.wrong': '旧密码错误',
    'user.status.invalid': '无效的用户状态',
    // Auth errors
    'auth.token.expired': '登录已过期，请重新登录',
    'auth.token.invalid': '无效的登录凭证',
    'auth.permission.denied': '权限不足',
    'auth.token.format.error': 'Token格式错误',
    'auth.account.locked': '账户已锁定',
    // File errors
    'file.not_found': '文件不存在',
    'file.preview.unsupported': '文件类型不支持预览',
    'file.delete.failed': '文件删除失败',
    'file.filename.exists': '文件名已存在',
    'file.path.invalid': '无效的文件路径',
    'file.tag.not_found': '标签不存在',
    'file.tag.name.exists': '标签名称已存在',
    'file.tagging.service.unavailable': '打标签服务不可用',
    'file.type.unsupported': '文件类型不支持',
    'file.dir.not_found': '目录不存在或无权限访问',
    'file.scan.in_progress': '扫描任务执行中',
    // Token errors
    'token.not_found': 'Token不存在',
    'token.operation.denied': '无权限操作此Token',
    'token.name.invalid': 'Token名称无效',
    'token.disabled': 'Token已禁用',
    'token.verify.failed': 'Token验证失败',
    // System errors
    'sys.server.error': '服务器内部错误',
    'sys.validation.failed': '参数校验失败',
    'sys.database.error': '数据库操作失败',
  },
  'en': {
    // User errors
    'user.not_found': 'User not found',
    'user.username.exists': 'Username already exists',
    'user.password.invalid': 'Password must contain uppercase, lowercase letters and numbers',
    'user.email.format.invalid': 'Email format is invalid',
    'user.username.or.password.wrong': 'Username or password is incorrect',
    'user.account.disabled': 'Account has been disabled',
    'user.email.exists': 'Email has already been used',
    'user.old.password.wrong': 'Old password is incorrect',
    'user.status.invalid': 'Invalid user status',
    // Auth errors
    'auth.token.expired': 'Session expired, please login again',
    'auth.token.invalid': 'Invalid credentials',
    'auth.permission.denied': 'Permission denied',
    'auth.token.format.error': 'Token format error',
    'auth.account.locked': 'Account has been locked',
    // File errors
    'file.not_found': 'File not found',
    'file.preview.unsupported': 'File type does not support preview',
    'file.delete.failed': 'File deletion failed',
    'file.filename.exists': 'Filename already exists',
    'file.path.invalid': 'Invalid file path',
    'file.tag.not_found': 'Tag not found',
    'file.tag.name.exists': 'Tag name already exists',
    'file.tagging.service.unavailable': 'Tagging service is unavailable',
    'file.type.unsupported': 'File type not supported',
    'file.dir.not_found': 'Directory not found or access denied',
    'file.scan.in_progress': 'Scan task is in progress',
    // Token errors
    'token.not_found': 'Token not found',
    'token.operation.denied': 'No permission to operate this token',
    'token.name.invalid': 'Token name is invalid',
    'token.disabled': 'Token has been disabled',
    'token.verify.failed': 'Token verification failed',
    // System errors
    'sys.server.error': 'Internal server error',
    'sys.validation.failed': 'Parameter validation failed',
    'sys.database.error': 'Database operation failed',
  }
};

/**
 * Get i18n message by message key
 */
export function getI18nMessage(messageKey: string, locale: Locale['zh-CN'] | 'en' = 'zh-CN'): string {
  return ErrorMessageMap[locale][messageKey] || messageKey;
}
```

- [ ] **Step 2: 提交**

```bash
git add webapp/src/service/error-code.ts
git commit -m "feat: add error code mapping and i18n messages"
```

---

### Task 12: 改造请求拦截器

**Files:**
- Modify: `webapp/src/service/request/index.ts`
- Modify: `webapp/src/service/request/shared.ts`

- [ ] **Step 1: 读取并改造 shared.ts 添加字段错误处理**

```typescript
// webapp/src/service/request/shared.ts
import type { Locale } from 'naive-ui';
import { getI18nMessage, ErrorCodeConfigMap } from '../error-code';

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);
        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}

export function showFieldErrors(fieldErrors: Record<string, string>, locale: Locale['zh-CN'] | 'en' = 'zh-CN') {
  // Store field errors for form components to display
  // This can be implemented via a reactive store or event bus
  window.__FIELD_ERRORS__ = fieldErrors;
}

export function getI18nMessageFn(messageKey: string, locale: Locale['zh-CN'] | 'en' = 'zh-CN'): string {
  return getI18nMessage(messageKey, locale);
}

export function getErrorCodeConfig(code: string) {
  return ErrorCodeConfigMap[code] || { isModal: false, isLogout: false };
}
```

- [ ] **Step 2: 改造 index.ts 的 onBackendFail**

```typescript
// webapp/src/service/request/index.ts (relevant parts)
async onBackendFail(response, instance) {
  const authStore = useAuthStore();
  const { code, message, data, fieldErrors } = response.data;
  const errorCode = String(code);

  // Get error code config
  const config = getErrorCodeConfig(errorCode);

  // Handle logout codes
  if (config.isLogout) {
    authStore.resetStore();
    return null;
  }

  // Handle modal logout codes
  if (config.isModal) {
    const displayMsg = getI18nMessageFn(message) || message;
    window.$dialog?.error({
      title: $t('common.error'),
      content: displayMsg,
      positiveText: $t('common.confirm'),
      maskClosable: false,
      closeOnEsc: false,
      onPositiveClick() {
        authStore.resetStore();
      }
    });
    return null;
  }

  // Handle field-level errors
  if (fieldErrors && typeof fieldErrors === 'object') {
    showFieldErrors(fieldErrors);
    // Also show a summary message
    const errorCount = Object.keys(fieldErrors).length;
    showErrorMsg(request.state, `${$t('common.validation_failed')}: ${errorCount} ${$t('common.fields')}`);
    return null;
  }

  // Handle general errors
  const displayMsg = getI18nMessageFn(message) || message;
  showErrorMsg(request.state, displayMsg);

  return null;
}
```

- [ ] **Step 3: 提交**

```bash
git add webapp/src/service/request/
git commit -m "feat: request interceptor supports i18n and field errors"
```

---

## 阶段四：前端表单校验

### Task 13: 改造用户管理页面表单校验

**Files:**
- Modify: `webapp/src/views/system/user/index.vue`

- [ ] **Step 1: 读取现有 user/index.vue**

- [ ] **Step 2: 添加前端表单校验规则**

```typescript
// 在 <script setup> 中添加
import { computed } from 'vue';
import { $t } from '@/locales';

// 添加校验规则
const rules = {
  username: [
    { required: true, message: () => $t('user.username_required') || '用户名不能为空', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: () => $t('user.username_format') || '用户名4-20位字母数字下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: () => $t('user.password_required') || '密码不能为空', trigger: 'blur' },
    { pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/, 
      message: () => $t('user.password_format') || '密码必须包含大小写字母和数字', trigger: 'blur' }
  ],
  email: [
    { required: true, message: () => $t('user.email_required') || '邮箱不能为空', trigger: 'blur' },
    { type: 'email', message: () => $t('user.email_format') || '邮箱格式不正确', trigger: 'blur' }
  ],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: () => $t('user.phone_format') || '手机号格式不正确', trigger: 'blur' }
  ]
};

// 添加表单引用和字段错误状态
const formRef = ref<any>(null);
const fieldErrors = reactive<Record<string, string>>({});

// 处理创建用户提交
async function handleCreateUser() {
  try {
    await formRef.value?.validate();
    await fetchCreateUser(createForm as any);
    showCreateModal.value = false;
    message.success($t('user.create_success') || '用户创建成功');
    // 重置表单
    createForm.username = '';
    createForm.password = '';
    createForm.email = '';
    createForm.phone = '';
    createForm.role = 'USER';
    createForm.status = 'ACTIVE';
    loadData();
  } catch (errors) {
    // 表单校验失败，不做处理（NaiveUI自动显示错误）
  }
}
```

- [ ] **Step 3: 修改模板中的 NFormItem 添加 validation-status 和 feedback**

```vue
<!-- 新增用户弹窗 -->
<NModal v-model:show="showCreateModal" preset="card" title="新增用户" style="width: 450px">
  <NForm ref="formRef" :model="createForm" :rules="rules" labelPlacement="left" labelWidth="80">
    <NFormItem label="用户名" path="username" :validation-status="fieldErrors.username ? 'error' : undefined" :feedback="fieldErrors.username">
      <NInput v-model:value="createForm.username" placeholder="请输入用户名" />
    </NFormItem>
    <NFormItem label="密码" path="password" :validation-status="fieldErrors.password ? 'error' : undefined" :feedback="fieldErrors.password">
      <NInput v-model:value="createForm.password" type="password" placeholder="请输入密码" />
    </NFormItem>
    <NFormItem label="邮箱" path="email" :validation-status="fieldErrors.email ? 'error' : undefined" :feedback="fieldErrors.email">
      <NInput v-model:value="createForm.email" placeholder="请输入邮箱" />
    </NFormItem>
    <NFormItem label="手机号" path="phone" :validation-status="fieldErrors.phone ? 'error' : undefined" :feedback="fieldErrors.phone">
      <NInput v-model:value="createForm.phone" placeholder="请输入手机号" />
    </NFormItem>
    <NFormItem label="角色">
      <NSelect v-model:value="createForm.role" :options="[
        { label: '普通用户', value: 'USER' },
        { label: '管理员', value: 'ADMIN' }
      ]" style="width: 200px" />
    </NFormItem>
  </NForm>
  <template #footer>
    <NSpace justify="end">
      <NButton @click="showCreateModal = false">取消</NButton>
      <NButton type="primary" @click="handleCreateUser">确定</NButton>
    </NSpace>
  </template>
</NModal>
```

- [ ] **Step 4: 添加国际化 key 到 locales 文件**

```typescript
// webapp/src/locales/lang/zh-CN.ts
export default {
  user: {
    username_required: '用户名不能为空',
    username_format: '用户名4-20位字母数字下划线',
    password_required: '密码不能为空',
    password_format: '密码必须包含大小写字母和数字',
    email_required: '邮箱不能为空',
    email_format: '邮箱格式不正确',
    phone_format: '手机号格式不正确',
    create_success: '用户创建成功',
  },
  common: {
    validation_failed: '参数校验失败',
    fields: '个字段',
  }
};
```

- [ ] **Step 5: 提交**

```bash
git add webapp/src/views/system/user/index.vue
git add webapp/src/locales/
git commit -m "feat: add frontend validation rules to user management page"
```

---

## 阶段五：文档与清理

### Task 14: 更新 CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 读取现有 CLAUDE.md**

- [ ] **Step 2: 更新错误码说明，添加国际化相关说明**

```markdown
## 错误码规范

### 错误码格式
采用 5 位纯数字格式，按模块分段：
- 用户 USER: 10001-10099
- 认证 AUTH: 20001-20099
- 本地文件 FILE: 30001-30099
- Token: 40001-40099
- 系统 SYS: 50001-50099

### 错误消息国际化
- 所有错误消息通过 `MessageSource` + `messages_xx_XX.properties` 实现国际化
- 后端 `ErrorCode` 枚举包含 `messageKey` 字段，对应 properties 文件中的 key
- 前端通过 `error-code.ts` 中的 `ErrorMessageMap` 维护国际化映射
- 新增错误码时需同时添加国际化消息

### 异常处理
- `BusinessException` 支持 `messageKey` 和 `fieldErrors`
- `GlobalExceptionHandler` 处理所有异常，返回国际化消息
- 未捕获异常返回 `SYS_001`，堆栈打印到日志
```

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with error code and i18n specs"
```

---

## 自我检查清单

- [ ] 设计文档中的所有需求都有对应的 Task
- [ ] 所有 Task 都有具体的文件路径和代码
- [ ] 没有 "TBD"、"TODO" 等占位符
- [ ] 类型、方法名在各个 Task 间保持一致
- [ ] 每个 Task 完成后都有提交命令

---

## 实施顺序

1. **阶段一（Task 1-5）**：后端基础设施 - 可并行
2. **阶段二（Task 6-10）**：后端模块适配 - 依赖阶段一
3. **阶段三（Task 11-12）**：前端基础设施 - 可并行
4. **阶段四（Task 13）**：前端表单校验 - 依赖阶段三
5. **阶段五（Task 14）**：文档更新 - 最后执行
