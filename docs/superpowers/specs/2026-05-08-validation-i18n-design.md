# 表单校验与错误国际化优化设计

## 1. 概述

本次优化旨在实现前后端统一的表单校验机制，以及错误消息的国际化支持。

### 1.1 目标

1. 前端所有表单输入框与后端保持一致的校验规则，不允许前端跳过校验直接提交
2. 后端返回的错误信息支持国际化，前端正确展示字段级错误
3. 后端代码（除注释外）不允许出现中文硬编码错误消息
4. 统一错误码机制，完善全局异常处理，未定义异常返回通用错误并记录日志

### 1.2 范围

- **后端模块**: user, auth, file, token, common
- **前端模块**: 所有涉及表单输入的页面（用户管理、角色管理等）

---

## 2. 错误码体系

### 2.1 错误码格式

采用 5 位纯数字格式，按模块分段：

| 模块 | 错误码范围 | 说明 |
|------|-----------|------|
| USER | 10001-10099 | 用户管理 |
| AUTH | 20001-20099 | 认证授权 |
| FILE | 30001-30099 | 本地文件/标签/目录 |
| TOKEN | 40001-40099 | Token管理 |
| SYS | 50001-50099 | 系统通用 |

### 2.2 错误码定义

```
USER (10001-10099)
├── 10001 - user_not_found             - 用户不存在
├── 10002 - username_exists            - 用户名已存在
├── 10003 - password_invalid           - 密码不符合规范
├── 10004 - email_format_invalid       - 邮箱格式错误
├── 10005 - username_or_password_wrong - 用户名或密码错误
├── 10006 - account_disabled           - 账户已禁用
├── 10007 - email_exists               - 邮箱已被使用
├── 10008 - old_password_wrong         - 旧密码错误
├── 10009 - invalid_status             - 无效的用户状态

AUTH (20001-20099)
├── 20001 - token_expired              - Token已过期
├── 20002 - token_invalid              - 无效Token
├── 20003 - permission_denied          - 权限不足
├── 20004 - token_format_error         - Token格式错误
├── 20005 - account_locked             - 账户已锁定

FILE (30001-30099)
├── 30001 - file_not_found             - 文件不存在
├── 30002 - file_preview_unsupported   - 文件类型不支持预览
├── 30003 - file_delete_failed        - 文件删除失败
├── 30004 - filename_exists           - 文件名已存在
├── 30005 - invalid_file_path         - 无效的文件路径
├── 30006 - tag_not_found             - 标签不存在
├── 30007 - tag_name_exists           - 标签名称已存在
├── 30008 - tagging_service_unavailable - 打标签服务不可用
├── 30009 - file_type_unsupported     - 文件类型不支持
├── 30010 - dir_not_found             - 目录不存在或无权限访问
├── 30011 - scan_in_progress          - 扫描任务执行中

TOKEN (40001-40099)
├── 40001 - token_not_found           - Token不存在
├── 40002 - token_operation_denied    - 无权限操作此Token
├── 40003 - token_name_invalid       - Token名称无效
├── 40004 - token_disabled           - Token已禁用
├── 40005 - token_verify_failed      - Token验证失败

SYS (50001-50099)
├── 50001 - server_error              - 服务器内部错误
├── 50002 - validation_failed         - 参数校验失败
├── 50003 - database_error            - 数据库操作失败
```

---

## 3. 后端国际化方案

### 3.1 配置文件结构

```
src/main/resources/
├── messages.properties           # 默认（英文）
├── messages_zh_CN.properties    # 中文
└── messages_en.properties       # 英文
```

### 3.2 消息模板格式

```properties
# messages_zh_CN.properties
user.not_found=用户不存在
user.username.exists=用户名已存在
user.password.invalid=密码必须包含大小写字母和数字
validation.failed=参数校验失败
server.error=服务器内部错误
```

### 3.3 ErrorCode 枚举改造

```java
@Getter
public enum ErrorCode {
    // 用户错误码 (10001-10099)
    USER_001("10001", "user.not_found", HttpStatus.NOT_FOUND),
    USER_002("10002", "user.username.exists", HttpStatus.CONFLICT),
    // ...

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

### 3.4 BusinessException 改造

```java
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
}
```

---

## 4. 全局异常处理

### 4.1 异常处理策略

| 异常类型 | 处理方式 |
|---------|---------|
| BusinessException | 通过 ErrorCode.messageKey 查找国际化消息，返回 fieldErrors |
| MethodArgumentNotValidException | 字段级错误通过国际化返回 |
| AuthenticationException | 返回 AUTH_002 错误 |
| AccessDeniedException | 返回 AUTH_003 错误 |
| 其他未捕获异常 | 返回 SYS_001，打印完整堆栈到日志 |

### 4.2 响应格式

**字段级错误响应：**
```json
{
  "code": 10003,
  "message": "validation.failed",
  "data": {
    "password": "user.password.invalid"
  },
  "traceId": "77db6410ed5d450d",
  "timestamp": "2026-05-08T23:18:32.880354506"
}
```

**通用错误响应：**
```json
{
  "code": 50001,
  "message": "server.error",
  "traceId": "77db6410ed5d450d",
  "timestamp": "2026-05-08T23:18:32.880354506"
}
```

---

## 5. 前端实现

### 5.1 错误码映射表

```typescript
// src/service/error-code.ts
export const ErrorCodeMap: Record<string, { key: string; isModal: boolean }> = {
  '10001': { key: 'user.not_found', isModal: false },
  '10002': { key: 'user.username_exists', isModal: false },
  '20001': { key: 'auth.token_expired', isModal: true },
  '20002': { key: 'auth.token_invalid', isModal: true },
  '50001': { key: 'sys.server_error', isModal: false },
  '50002': { key: 'sys.validation_failed', isModal: false },
};

export const ErrorMessageMap: Record<string, string> = {
  'zh-CN': {
    'user.not_found': '用户不存在',
    'user.username_exists': '用户名已存在',
    'user.password_invalid': '密码必须包含大小写字母和数字',
    'auth.token_expired': '登录已过期，请重新登录',
    'auth.token_invalid': '无效的登录凭证',
    'sys.server_error': '服务器内部错误',
    'sys.validation_failed': '参数校验失败',
  },
  'en': {
    'user.not_found': 'User not found',
    'user.username_exists': 'Username already exists',
    'user.password_invalid': 'Password must contain uppercase, lowercase letters and numbers',
    'auth.token_expired': 'Session expired, please login again',
    'auth.token_invalid': 'Invalid credentials',
    'sys.server_error': 'Internal server error',
    'sys.validation_failed': 'Validation failed',
  }
};
```

### 5.2 请求拦截器改造

```typescript
// src/service/request/index.ts
async onBackendFail(response, instance) {
  const { code, message, data, traceId } = response.data;
  const errorKey = String(code);

  // 1. 处理字段级错误
  if (data && typeof data === 'object') {
    const fieldErrors = data as Record<string, string>;
    // 将国际化 key 转换为显示消息
    for (const [field, key] of Object.entries(fieldErrors)) {
      const displayMsg = getI18nMessage(key);
      updateFieldError(field, displayMsg);
    }
    return null;
  }

  // 2. 处理通用错误
  const displayMsg = getI18nMessage(message) || message;
  showErrorMsg(request.state, displayMsg);

  return null;
}
```

### 5.3 表单校验规则

```typescript
// src/views/system/user/index.vue
const rules = {
  username: [
    { required: true, message: t('user.username_required'), trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: t('user.username_format'), trigger: 'blur' }
  ],
  password: [
    { required: true, message: t('user.password_required'), trigger: 'blur' },
    { pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/, 
      message: t('user.password_format'), trigger: 'blur' }
  ],
  email: [
    { required: true, message: t('user.email_required'), trigger: 'blur' },
    { type: 'email', message: t('user.email_format'), trigger: 'blur' }
  ],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: t('user.phone_format'), trigger: 'blur' }
  ]
};
```

### 5.4 字段错误展示

使用 NaiveUI 的 `validation` 状态和 `feedback` 属性：

```vue
<NFormItem label="密码" :validation-status="fieldErrors.password ? 'error' : undefined" :feedback="fieldErrors.password">
  <NInput v-model:value="form.password" />
</NFormItem>
```

---

## 6. 实施步骤

### 阶段一：后端基础设施
1. 创建 `messages.properties`、`messages_zh_CN.properties`、`messages_en.properties`
2. 重构 `ErrorCode` 枚举为新格式
3. 改造 `BusinessException` 支持 messageKey
4. 完善 `GlobalExceptionHandler`

### 阶段二：后端模块适配
1. 改造 User 模块错误码
2. 改造 Auth 模块错误码
3. 改造 File 模块错误码（本地文件/标签/目录）
4. 改造 Token 模块错误码
5. 移除微信相关代码和错误码

### 阶段三：前端基础设施
1. 创建错误码映射表 `error-code.ts`
2. 改造请求拦截器支持国际化消息
3. 添加字段级错误展示组件

### 阶段四：前端表单校验
1. 改造用户管理页面表单校验
2. 改造其他涉及输入的页面
3. 确保与后端校验规则一致

### 阶段五：文档与清理
1. 更新 CLAUDE.md
2. 更新 API 文档
3. 清理无用代码

---

## 7. 风险与注意事项

1. **错误码迁移影响线上**：需要前后端同时发布
2. **国际化 key 维护**：新增错误时需同步添加国际化消息
3. **前端缓存**：生产环境需确保静态资源更新
