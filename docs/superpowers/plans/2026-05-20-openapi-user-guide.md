# MyTools 开放接口文档

> 供外部 App 调用，实现登录和个人信息维护功能。

**Base URL:** `http://<host>:23110`
**认证方式:** `Authorization: Bearer <jwt_token>`（除登录接口外均需携带）

---

## 认证相关

### 1. 用户登录

登录成功后返回 `accessToken`，后续接口通过 `Authorization: Bearer <token>` 携带。

```
POST /api/auth/login
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| account | string | 是 | 用户名或邮箱 |
| password | string | 是 | 登录密码 |

**响应示例：**

```json
{
  "code": "0000",
  "message": "登录成功",
  "data": {
    "userId": 1,
    "username": "admin",
    "nickname": "管理员",
    "avatar": "https://...",
    "role": "ADMIN",
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 604800
  }
}
```

**错误码：**

| code | 说明 |
|------|------|
| 10005 | 用户名或密码错误 |
| 10006 | 账户已禁用 |
| 10005 | 账户已锁定 |

---

## 用户信息

### 2. 查询个人信息（包含 WebDAV 配置）

```
GET /api/public/profile
Authorization: Bearer <jwt_token>
```

**响应示例：**

```json
{
  "code": "0000",
  "message": "操作成功",
  "data": {
    "id": 1,
    "username": "admin",
    "nickname": "管理员",
    "avatar": "https://...",
    "email": "admin@example.com",
    "phone": "138****8888",
    "gender": 1,
    "birthday": "1990-01-01",
    "address": "北京市",
    "hobbies": "阅读",
    "signature": "你好",
    "role": "ADMIN",
    "status": "ACTIVE",
    "registerTime": "2026-05-11T16:59:21",
    "lastLoginTime": "2026-05-20T09:30:00",
    "webdavType": "jianguoyun",
    "webdavUrl": "https://dav.jianguoyun.com/dav/",
    "webdavUsername": "test@example.com",
    "webdavEncryptedPassword": "sAhm8iHeBF2ZU9jHq6IHaqUp2wBO1XU2L/QZ/SWJIwDky90f",
    "webdavPasswordSet": true
  }
}
```

**说明：**

- WebDAV 密码为 **AES-GCM-256 加密后的 Base64 密文**，客户端需解密后才能使用
- `webdavPasswordSet: false` 时，`webdavEncryptedPassword` 为空，请勿解密

### 3. 更新个人信息

```
PUT /api/public/profile
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| nickname | string | 否 | 昵称（最长50位） |
| avatar | string | 否 | 头像（Base64 图片数据） |
| email | string | 否 | 邮箱（有效格式） |
| phone | string | 否 | 手机号（11位，以1开头） |
| gender | integer | 否 | 性别：0-未知，1-男，2-女 |
| birthday | string | 否 | 生日（格式：YYYY-MM-DD） |
| address | string | 否 | 地址（最长255位） |
| hobbies | string | 否 | 爱好（最长500位） |
| signature | string | 否 | 个人签名（最长255位） |

**响应示例：**

```json
{
  "code": "0000",
  "message": "更新成功",
  "data": {
    "username": "admin",
    "nickname": "新昵称",
    "email": "new@example.com",
    ...
  }
}
```

### 4. 修改登录密码

```
PUT /api/public/password
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| oldPassword | string | 是 | 旧密码 |
| newPassword | string | 是 | 新密码（6-20位，不能包含异常字符） |

**响应示例：**

```json
{
  "code": "0000",
  "message": "密码修改成功"
}
```

**错误码：**

| code | 说明 |
|------|------|
| 10008 | 旧密码错误 |

---

## WebDAV 配置

### 5. 更新 WebDAV 配置

```
PUT /api/public/webdav
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 服务类型：`jianguoyun`、`nextcloud`、`owncloud`、`synology`、`alist`、`s3`、`custom` |
| url | string | 是 | WebDAV 服务器地址（最长512位） |
| username | string | 是 | WebDAV 用户名（最长128位） |
| password | string | 否 | WebDAV 密码（留空则保持不变，最长128位） |

**响应示例：**

```json
{
  "code": "0000",
  "message": "更新成功",
  "data": {
    "id": 2,
    "userId": 1,
    "type": "jianguoyun",
    "url": "https://dav.jianguoyun.com/dav/",
    "username": "test@example.com",
    "passwordSet": true
  }
}
```

**错误码：**

| code | 说明 |
|------|------|
| 10004 | 邮箱格式不正确（用于 email 字段） |

---

## 通用说明

### 通用响应结构

```json
{
  "code": "0000",
  "message": "操作成功",
  "data": { ... },
  "traceId": "abc123...",
  "timestamp": "2026-05-20T10:00:00.000000"
}
```

| code | 说明 |
|------|------|
| 0000 | 成功 |
| 10001 | 用户不存在 |
| 10005 | 用户名或密码错误 |
| 10008 | 旧密码错误 |
| 20001 | Token 已过期 |
| 20002 | 无效 Token |
| 50001 | 服务器内部错误 |

### 密码解密方法

WebDAV 密码使用 **AES-GCM-256** 加密，客户端解密步骤：

**加密参数：**
- 算法：AES-256-GCM
- IV 长度：12 字节
- 认证标签：128 位
- 格式：`Base64(IV || CipherText || Tag)`
- AES 密钥（Base64）：`CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=`

**Python 示例：**

```python
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import base64

AES_KEY_B64 = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8="
GCM_IV_LENGTH = 12

def decrypt_password(ciphertext_b64: str) -> str:
    key = base64.b64decode(AES_KEY_B64)
    decoded = base64.b64decode(ciphertext_b64)
    iv = decoded[:GCM_IV_LENGTH]
    cipher_text = decoded[GCM_IV_LENGTH:]
    aesgcm = AESGCM(key)
    return aesgcm.decrypt(iv, cipher_text, None).decode()

password = decrypt_password("sAhm8iHeBF2ZU9jHq6IHaqUp2wBO1XU2L/QZ/SWJIwDky90f")
print(password)  # testpass
```

**JavaScript 示例：**

```javascript
const crypto = require('crypto');

const AES_KEY_B64 = 'CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=';
const GCM_IV_LENGTH = 12;

function decryptPassword(ciphertextB64) {
  const key = Buffer.from(AES_KEY_B64, 'base64');
  const decoded = Buffer.from(ciphertextB64, 'base64');
  const iv = decoded.subarray(0, GCM_IV_LENGTH);
  const cipherText = decoded.subarray(GCM_IV_LENGTH);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  return decipher.update(cipherText) + decipher.final('utf8');
}

const password = decryptPassword('sAhm8iHeBF2ZU9jHq6IHaqUp2wBO1XU2L/QZ/SWJIwDky90f');
console.log(password);
```

---

## 完整调用示例（Python）

```python
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import base64

BASE_URL = "http://localhost:23110"
AES_KEY_B64 = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8="

# ========== 1. 登录 ==========
login_resp = requests.post(
    f"{BASE_URL}/api/auth/login",
    json={"account": "admin", "password": "admin123"}
).json()
token = login_resp["data"]["accessToken"]
headers = {"Authorization": f"Bearer {token}"}
print(f"登录成功，用户: {login_resp['data']['username']}")

# ========== 2. 查询个人信息 ==========
profile_resp = requests.get(
    f"{BASE_URL}/api/public/profile",
    headers=headers
).json()["data"]

print(f"昵称: {profile_resp['nickname']}")
print(f"邮箱: {profile_resp['email']}")
if profile_resp.get("webdavPasswordSet"):
    ciphertext = profile_resp["webdavEncryptedPassword"]
    key = base64.b64decode(AES_KEY_B64)
    decoded = base64.b64decode(ciphertext)
    iv = decoded[:12]
    plain = AESGCM(key).decrypt(iv, decoded[12:], None).decode()
    print(f"WebDAV 密码: {plain}")

# ========== 3. 更新个人信息 ==========
update_resp = requests.put(
    f"{BASE_URL}/api/public/profile",
    headers=headers,
    json={"nickname": "新昵称", "signature": "你好世界"}
).json()
print(f"更新结果: {update_resp['message']}")

# ========== 4. 修改登录密码 ==========
pwd_resp = requests.put(
    f"{BASE_URL}/api/public/password",
    headers=headers,
    json={"oldPassword": "admin123", "newPassword": "NewPass_123"}
).json()
print(f"密码修改: {pwd_resp['message']}")

# ========== 5. 更新 WebDAV 配置 ==========
webdav_resp = requests.put(
    f"{BASE_URL}/api/public/webdav",
    headers=headers,
    json={
        "type": "jianguoyun",
        "url": "https://dav.jianguoyun.com/dav/",
        "username": "my@email.com",
        "password": "webdav_secret"
    }
).json()
print(f"WebDAV 更新: {webdav_resp['message']}")
```

---

## 接口总览

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | /api/auth/login | 否 | 用户登录 |
| GET | /api/public/profile | 是 | 查询个人信息 + WebDAV |
| PUT | /api/public/profile | 是 | 更新个人信息 |
| PUT | /api/public/password | 是 | 修改登录密码 |
| PUT | /api/public/webdav | 是 | 更新 WebDAV 配置 |
