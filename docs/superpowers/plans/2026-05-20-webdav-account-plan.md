# WebDAV 信息维护 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在个人信息维护页面新增 WebDAV 信息配置区块，每用户独立存储，支持 AES 加密密码。

**Architecture:** 后端新增 `webdav` 模块（实体 + Mapper + Service + Controller），前端在 `profile/index.vue` 中新增 WebDAV 配置区块。密码使用现有 `AesEncryptUtils`（AES-GCM）加密存储，API 走 `/api/user/webdav`。

**Tech Stack:** Spring Boot 3 + MyBatis XML + Vue3 + NaiveUI + Pinia + TypeScript

---

## 文件结构

```
src/main/java/com/yuyutian/mytools/
├── webdav/
│   ├── model/
│   │   ├── WebdavAccount.java
│   │   ├── WebdavAccountResponse.java
│   │   └── UpdateWebdavAccountRequest.java
│   ├── mapper/
│   │   └── WebdavAccountMapper.java
│   ├── service/
│   │   ├── WebdavAccountService.java
│   │   └── impl/WebdavAccountServiceImpl.java
│   └── controller/
│       └── WebdavAccountController.java

src/main/resources/mapper/webdav/
    └── WebdavAccountMapper.xml

webapp/src/
├── service/api/user.ts
├── typings/api/user-role.d.ts
├── locales/langs/zh-cn.ts
└── views/profile/index.vue

sql/
└── (新增 DDL 脚本)
```

---

## Task 1: 数据库 DDL

**Files:**
- Create: `sql/upgrade/webdav_account.sql`

- [ ] **Step 1: 创建 DDL 脚本**

```sql
CREATE TABLE webdav_account (
    id           BIGINT        NOT NULL  AUTO_INCREMENT  PRIMARY KEY COMMENT '主键',
    user_id      BIGINT        NOT NULL  UNIQUE COMMENT '关联用户ID',
    type         VARCHAR(32)   NOT NULL  DEFAULT 'jianguoyun' COMMENT '服务类型',
    url          VARCHAR(512)  NOT NULL  COMMENT 'WebDAV地址',
    username     VARCHAR(128)  NOT NULL  COMMENT '用户名',
    password     VARCHAR(256)  NOT NULL  COMMENT '密码(AES加密)',
    is_active    TINYINT       NOT NULL  DEFAULT 1 COMMENT '是否启用',
    create_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebDAV账号表';
```

- [ ] **Step 2: 提交**

```bash
git add sql/upgrade/webdav_account.sql
git commit -m "feat: add webdav_account table DDL"
```

---

## Task 2: 后端实体 & 请求/响应 DTO

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/webdav/model/WebdavAccount.java`
- Create: `src/main/java/com/yuyutian/mytools/webdav/model/WebdavAccountResponse.java`
- Create: `src/main/java/com/yuyutian/mytools/webdav/model/UpdateWebdavAccountRequest.java`

- [ ] **Step 1: 创建 WebdavAccount.java**

```java
package com.yuyutian.mytools.webdav.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WebdavAccount {
    private Long id;
    private Long userId;
    private String type;
    private String url;
    private String username;

    @JsonIgnore
    private String password;

    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean passwordSet;
}
```

- [ ] **Step 2: 创建 WebdavAccountResponse.java**

```java
package com.yuyutian.mytools.webdav.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WebdavAccountResponse {
    private Long id;
    private Long userId;
    private String type;
    private String url;
    private String username;
    private Boolean passwordSet;
}
```

- [ ] **Step 3: 创建 UpdateWebdavAccountRequest.java**

```java
package com.yuyutian.mytools.webdav.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateWebdavAccountRequest {

    @NotBlank(message = "webdav.type.notBlank")
    @Pattern(regexp = "^(jianguoyun|nextcloud|owncloud|synology|alist|s3|custom)$",
             message = "webdav.type.invalid")
    private String type;

    @NotBlank(message = "webdav.url.notBlank")
    @Size(max = 512, message = "webdav.url.size")
    private String url;

    @NotBlank(message = "webdav.username.notBlank")
    @Size(max = 128, message = "webdav.username.size")
    private String username;

    @Size(max = 128, message = "webdav.password.size")
    private String password;
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/webdav/model/
git commit -m "feat: add WebdavAccount entity and DTOs"
```

---

## Task 3: 后端 Mapper

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/webdav/mapper/WebdavAccountMapper.java`
- Create: `src/main/resources/mapper/webdav/WebdavAccountMapper.xml`

- [ ] **Step 1: 创建 WebdavAccountMapper.java**

```java
package com.yuyutian.mytools.webdav.mapper;

import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WebdavAccountMapper {

    @Select("SELECT * FROM webdav_account WHERE user_id = #{userId}")
    WebdavAccount selectByUserId(@Param("userId") Long userId);

    @Insert("""
        INSERT INTO webdav_account (user_id, type, url, username, password, is_active)
        VALUES (#{userId}, #{type}, #{url}, #{username}, #{password}, 1)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WebdavAccount account);

    @Update("""
        UPDATE webdav_account
        SET type = #{type}, url = #{url}, username = #{username}, password = #{password}
        WHERE user_id = #{userId}
        """)
    int updateByUserId(WebdavAccount account);
}
```

- [ ] **Step 2: 创建 WebdavAccountMapper.xml（备用，若 XML 方式更符合项目习惯则使用此文件）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
  "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper">

  <resultMap id="WebdavAccountResultMap" type="com.yuyutian.mytools.webdav.model.WebdavAccount">
    <id column="id" property="id"/>
    <result column="user_id" property="userId"/>
    <result column="type" property="type"/>
    <result column="url" property="url"/>
    <result column="username" property="username"/>
    <result column="password" property="password"/>
    <result column="is_active" property="isActive"/>
    <result column="create_time" property="createTime"/>
    <result name="update_time" property="updateTime"/>
  </resultMap>

  <select id="selectByUserId" resultMap="WebdavAccountResultMap">
    SELECT * FROM webdav_account WHERE user_id = #{userId}
  </select>

  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO webdav_account (user_id, type, url, username, password, is_active)
    VALUES (#{userId}, #{type}, #{url}, #{username}, #{password}, 1)
  </insert>

  <update id="updateByUserId">
    UPDATE webdav_account
    SET type = #{type}, url = #{url}, username = #{username}, password = #{password}
    WHERE user_id = #{userId}
  </update>

</mapper>
```

> **注意**：项目已有 `@Select`/`@Insert`/`@Update` 注解写法的 Mapper，可直接用注解替代 XML。若 TokenMapper 等模块使用 XML，则按 XML 方式。实际以 `src/main/resources/mapper/auth/TokenMapper.xml` 为准，若该模块用注解则跟注解。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/webdav/mapper/
git add src/main/resources/mapper/webdav/WebdavAccountMapper.xml
git commit -m "feat: add WebdavAccountMapper"
```

---

## Task 4: 后端 Service

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/webdav/service/WebdavAccountService.java`
- Create: `src/main/java/com/yuyutian/mytools/webdav/service/impl/WebdavAccountServiceImpl.java`

- [ ] **Step 1: 创建 WebdavAccountService.java**

```java
package com.yuyutian.mytools.webdav.service;

import com.yuyutian.mytools.webdav.model.*;

public interface WebdavAccountService {

    WebdavAccountResponse getByUserId(Long userId);

    WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request);
}
```

- [ ] **Step 2: 创建 WebdavAccountServiceImpl.java**

```java
package com.yuyutian.mytools.webdav.service.impl;

import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.*;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebdavAccountServiceImpl implements WebdavAccountService {

    private final WebdavAccountMapper webdavAccountMapper;

    @Override
    public WebdavAccountResponse getByUserId(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectByUserId(userId);
        if (account == null) {
            return null;
        }
        return toResponse(account);
    }

    @Override
    @Transactional
    public WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request) {
        WebdavAccount existing = webdavAccountMapper.selectByUserId(userId);

        String encryptedPassword;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            encryptedPassword = AesEncryptUtils.encrypt(request.getPassword());
        } else if (existing != null) {
            encryptedPassword = existing.getPassword();
        } else {
            encryptedPassword = "";
        }

        WebdavAccount account = new WebdavAccount();
        account.setUserId(userId);
        account.setType(request.getType());
        account.setUrl(request.getUrl());
        account.setUsername(request.getUsername());
        account.setPassword(encryptedPassword);
        account.setIsActive(1);

        if (existing == null) {
            webdavAccountMapper.insert(account);
        } else {
            account.setId(existing.getId());
            webdavAccountMapper.updateByUserId(account);
        }

        return toResponse(account);
    }

    private WebdavAccountResponse toResponse(WebdavAccount account) {
        return new WebdavAccountResponse(
            account.getId(),
            account.getUserId(),
            account.getType(),
            account.getUrl(),
            account.getUsername(),
            account.getPassword() != null && !account.getPassword().isBlank()
        );
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/webdav/service/
git commit -m "feat: add WebdavAccountService with AES password encryption"
```

---

## Task 5: 后端 Controller

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/webdav/controller/WebdavAccountController.java`

- [ ] **Step 1: 创建 WebdavAccountController.java**

参考 `TokenController.java`，路由为 `/api/user/webdav`，用户 ID 从 JWT 获取：

```java
package com.yuyutian.mytools.webdav.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.*;
import com.yuyutian.mytools.webdav.model.*;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WebdavAccountController {

    private final WebdavAccountService webdavAccountService;

    @GetMapping("/api/user/webdav")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> getWebdavAccount(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = JwtUtils.getUserIdFromToken(authHeader);
        WebdavAccountResponse data = webdavAccountService.getByUserId(userId);
        return ResponseEntity.ok(Result.success(data));
    }

    @PutMapping("/api/user/webdav")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> updateWebdavAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateWebdavAccountRequest request) {
        Long userId = JwtUtils.getUserIdFromToken(authHeader);
        WebdavAccountResponse data = webdavAccountService.saveOrUpdate(userId, request);
        return ResponseEntity.ok(Result.success(data));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/webdav/controller/WebdavAccountController.java
git commit -m "feat: add WebdavAccountController with GET/PUT /api/user/webdav"
```

---

## Task 6: 前端类型 & API 函数

**Files:**
- Modify: `webapp/src/typings/api/user-role.d.ts`
- Modify: `webapp/src/service/api/user.ts`

- [ ] **Step 1: 在 `webapp/src/typings/api/user-role.d.ts` 的 `Api.User` 命名空间追加**

```typescript
  interface WebdavAccountResponse {
    id: string;
    userId: string;
    type: string;
    url: string;
    username: string;
    passwordSet: boolean;
  }

  interface UpdateWebdavAccountRequest {
    type: string;
    url: string;
    username: string;
    password?: string;
  }
```

- [ ] **Step 2: 在 `webapp/src/service/api/user.ts` 末尾追加**

```typescript
// WebDAV 账号
export const fetchWebdavAccount = () =>
  request<Api.User.WebdavAccountResponse>('get', '/api/user/webdav');

export const updateWebdavAccount = (data: Api.User.UpdateWebdavAccountRequest) =>
  request<Api.User.WebdavAccountResponse>('put', '/api/user/webdav', data);
```

- [ ] **Step 3: 提交**

```bash
git add webapp/src/typings/api/user-role.d.ts webapp/src/service/api/user.ts
git commit -m "feat: add WebDAV account API types and functions"
```

---

## Task 7: 前端页面 — WebDAV 信息区块

**Files:**
- Modify: `webapp/src/views/profile/index.vue`

在 `<script setup>` 中新增 WebDAV 相关状态和逻辑，在模板中新增 WebDAV Card 区块。

- [ ] **Step 1: 在 `<script setup>` 中新增**

参考 `profile/index.vue` 现有 `reactive(form)` 模式，在 `const form = reactive({...})` 后新增：

```typescript
// WebDAV 信息
const webdavForm = reactive({
  type: 'jianguoyun',
  url: '',
  username: '',
  password: ''
});
const originalWebdavForm = { ...webdavForm };
const webdavEditing = ref(false);
const webdavLoading = ref(false);
const showPassword = ref(false);

const hasWebdavChanges = computed(() =>
  JSON.stringify(webdavForm) !== JSON.stringify(originalWebdavForm)
);

// 加载 WebDAV 配置
async function loadWebdav() {
  try {
    const data = await fetchWebdavAccount();
    if (data) {
      webdavForm.type = data.type;
      webdavForm.url = data.url;
      webdavForm.username = data.username;
      webdavForm.password = '';
      Object.assign(originalWebdavForm, {
        type: data.type,
        url: data.url,
        username: data.username,
        password: ''
      });
    }
  } catch {
    // 未配置时不报错
  }
}

// 保存 WebDAV 配置
async function saveWebdav() {
  webdavLoading.value = true;
  try {
    await updateWebdavAccount({
      type: webdavForm.type,
      url: webdavForm.url,
      username: webdavForm.username,
      password: webdavForm.password || undefined
    });
    Object.assign(originalWebdavForm, { ...webdavForm, password: '' });
    webdavForm.password = '';
    webdavEditing.value = false;
    window.$message?.success('保存成功');
  } finally {
    webdavLoading.value = false;
  }
}

function cancelWebdavEdit() {
  Object.assign(webdavForm, originalWebdavForm);
  webdavForm.password = '';
  webdavEditing.value = false;
}

// watch 初始加载后获取 WebDAV 数据
watch(() => authStore.userInfo?.id, (id) => {
  if (id) loadWebdav();
}, { immediate: true });
```

- [ ] **Step 2: 在模板中新增 WebDAV Card**

在个人信息表单 `</n-card>` 之后新增：

```vue
<n-card :title="locale.profile.webdavTitle" class="mt-4">
  <template #header-extra>
    <n-button
      v-if="!webdavEditing"
      size="small"
      @click="webdavEditing = true"
    >
      {{ locale.common.edit }}
    </n-button>
  </template>

  <n-space vertical :size="16">
    <n-grid :x-gap="16" :cols="2">
      <n-gi>
        <n-form-item :label="locale.profile.webdavType" path="type">
          <n-select
            v-model:value="webdavForm.type"
            :options="webdavTypeOptions"
            :disabled="!webdavEditing"
          />
        </n-form-item>
      </n-gi>
      <n-gi>
        <n-form-item :label="locale.profile.webdavUrl" path="url">
          <n-input
            v-model:value="webdavForm.url"
            :disabled="!webdavEditing"
            placeholder="https://dav.example.com/dav/"
          />
        </n-form-item>
      </n-gi>
      <n-gi>
        <n-form-item :label="locale.profile.webdavUsername" path="username">
          <n-input
            v-model:value="webdavForm.username"
            :disabled="!webdavEditing"
          />
        </n-form-item>
      </n-gi>
      <n-gi>
        <n-form-item :label="locale.profile.webdavPassword" path="password">
          <n-input
            v-model:value="webdavForm.password"
            type="password"
            show-password-on="click"
            :disabled="!webdavEditing"
            :placeholder="originalWebdavForm.password || '已设置' "
          />
        </n-form-item>
      </n-gi>
    </n-grid>

    <n-space v-if="webdavEditing">
      <n-button type="primary" :loading="webdavLoading" @click="saveWebdav">
        {{ locale.common.save }}
      </n-button>
      <n-button @click="cancelWebdavEdit">
        {{ locale.common.cancel }}
      </n-button>
    </n-space>
  </n-space>
</n-card>
```

WebDAV 类型选项（script setup 顶部）：

```typescript
const webdavTypeOptions = [
  { label: '坚果云', value: 'jianguoyun' },
  { label: 'Nextcloud', value: 'nextcloud' },
  { label: 'ownCloud', value: 'owncloud' },
  { label: '群晖/NAS', value: 'synology' },
  { label: 'Alist', value: 'alist' },
  { label: 'S3/WebDAV网关', value: 's3' },
  { label: '自定义', value: 'custom' }
];
```

- [ ] **Step 3: 提交**

```bash
git add webapp/src/views/profile/index.vue
git commit -m "feat: add WebDAV account section in profile page"
```

---

## Task 8: 国际化文案

**Files:**
- Modify: `webapp/src/locales/langs/zh-cn.ts`

- [ ] **Step 1: 在 `profile` 节点下新增**

```typescript
webdavTitle: 'WebDAV 信息维护',
webdavType: '类型',
webdavUrl: '地址',
webdavUsername: '用户名',
webdavPassword: '密码',
```

- [ ] **Step 2: 提交**

```bash
git add webapp/src/locales/langs/zh-cn.ts
git commit -m "feat: add WebDAV account i18n strings"
```

---

## Task 9: 编译验证

- [ ] **Step 1: 后端编译**

```bash
cd /Users/pankang/mycode/MyTools && mvn compile -q
```

预期：无编译错误

- [ ] **Step 2: 前端类型检查**

```bash
cd /Users/pankang/mycode/MyTools/webapp && pnpm typecheck
```

预期：无类型错误

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat: complete WebDAV account maintenance feature"
```

---

## 规格自检

- [x] DDL 建表脚本 — Task 1
- [x] 实体类（密码 `@JsonIgnore`） — Task 2
- [x] MyBatis Mapper（注解或 XML） — Task 3
- [x] Service（加密/解密密码，复用 `AesEncryptUtils`） — Task 4
- [x] Controller（GET + PUT `/api/user/webdav`，JWT鉴权） — Task 5
- [x] 前端类型定义 — Task 6
- [x] 前端 API 函数 — Task 6
- [x] 前端页面（WebDAV Card，下拉选项，密码显示切换） — Task 7
- [x] 国际化文案 — Task 8
- [x] 编译验证 — Task 9
