# Alist 集成实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Alist 子页面，支持浏览 Alist 存储文件并预览图片/文本/Markdown。

**Architecture:** 新增 `AlistClient`（认证 + 文件列表 + 直链获取），复用现有 `CloudFileController` 的 `/api/cloud/files` 接口，新增 `/api/cloud/alist/raw` 获取预览直链。前端新建 `views/alist/` 页面，账号过滤 `type === 'alist'`。

**Tech Stack:** Java 21 + Spring Boot 3.2.5 + MyBatis / Vue 3 + TypeScript + NaiveUI + Pinia + Elegant Router

---

## Task 1: 后端 — AlistClient

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/service/impl/AlistClient.java`

- [ ] **Step 1: 创建 AlistClient 类框架**

```java
package com.yuyutian.mytools.cloudfile.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.cloudfile.model.CloudFileItem;
import com.yuyutian.mytools.cloudfile.model.CloudFileListResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
public class AlistClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private String token; // stored encrypted externally, decrypted by caller
    private final HttpClient httpClient;

    public AlistClient(String baseUrl, String username, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
```

- [ ] **Step 2: 实现 login 方法（SHA-256 哈希密码）**

在类中添加：

```java
/**
 * 使用 SHA-256 哈希后的密码登录，返回新 token。
 * 用于首次登录或 token 刷新。
 */
public String login(String plainPassword) throws Exception {
    String sha256Hex = sha256(plainPassword);
    String body = MAPPER.writeValueAsString(Map.of(
            "username", username,
            "password", sha256Hex
    ));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/login/hash"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode root = MAPPER.readTree(response.body());

    if (root.has("code") && root.get("code").asInt() == 200) {
        this.token = root.path("data").path("token").asText();
        return this.token;
    }
    throw new IOException("Alist login failed: " + root.path("message").asText("unknown error"));
}

private static String sha256(String input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : hash) hex.append(String.format("%02x", b));
    return hex.toString();
}
```

- [ ] **Step 3: 实现 list 方法**

```java
/**
 * 列出指定路径下的文件和目录。
 */
public CloudFileListResponse list(String path) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of(
            "path", path.isEmpty() ? "/" : path,
            "password", "",
            "page", 1,
            "per_page", 500
    ));

    HttpResponse<String> response = post("/api/fs/list", body);
    JsonNode root = MAPPER.readTree(response.body());

    if (root.has("code") && root.get("code").asInt() == 200) {
        JsonNode content = root.path("data").path("content");
        List<CloudFileItem> items = new ArrayList<>();
        for (JsonNode item : content) {
            boolean isDir = item.path("is_dir").asBoolean();
            items.add(new CloudFileItem(
                    null, // path set below
                    item.path("name").asText(),
                    isDir,
                    item.path("size").asLong(0),
                    null, // contentType - not available from Alist
                    parseInstant(item.path("modified").asText(null)),
                    null  // etag
            ));
        }
        // set full path for each item
        String normalizedPath = path.isEmpty() ? "/" : path;
        for (CloudFileItem item : items) {
            String itemPath = normalizedPath.equals("/")
                    ? "/" + item.getName()
                    : normalizedPath + "/" + item.getName();
            // use reflection or add setter - use CloudFileItem builder or set via field
            // Since CloudFileItem may not have setPath, check and use appropriate approach
            try {
                java.lang.reflect.Method setPath = CloudFileItem.class.getMethod("setPath", String.class);
                setPath.invoke(item, itemPath);
            } catch (NoSuchMethodException e) {
                // CloudFileItem doesn't have setPath, use constructor
            }
        }
        return new CloudFileListResponse(normalizedPath, items);
    }

    if (response.statusCode() == 401) {
        throw new IOException("TOKEN_EXPIRED");
    }
    throw new IOException("Alist list failed: " + root.path("message").asText("unknown"));
}

private Instant parseInstant(String text) {
    if (text == null || text.isEmpty()) return null;
    try { return Instant.parse(text); } catch (Exception e) { return null; }
}
```

- [ ] **Step 4: 实现 getRawUrl 方法（获取预览直链）**

```java
/**
 * 获取文件的直链 URL，用于预览（图片/文本）。
 */
public String getRawUrl(String path) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of(
            "path", path,
            "password", ""
    ));

    HttpResponse<String> response = post("/api/fs/get", body);
    JsonNode root = MAPPER.readTree(response.body());

    if (root.has("code") && root.get("code").asInt() == 200) {
        String rawUrl = root.path("data").path("raw_url").asText(null);
        if (rawUrl == null || rawUrl.isEmpty()) {
            throw new IOException("No raw_url available for: " + path);
        }
        return rawUrl;
    }

    if (response.statusCode() == 401) {
        throw new IOException("TOKEN_EXPIRED");
    }
    throw new IOException("Alist get failed: " + root.path("message").asText("unknown"));
}
```

- [ ] **Step 5: 实现 post 辅助方法（含 401 自动刷新）**

```java
private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    // auto-refresh token on 401 and retry once
    if (response.statusCode() == 401 && !token.isEmpty()) {
        log.info("Alist token expired, need refresh");
        throw new IOException("TOKEN_EXPIRED");
    }

    return response;
}
```

- [ ] **Step 6: 验证编译**

Run: `mvn compile -q`
Expected: 编译成功（无输出）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/service/impl/AlistClient.java
git commit -m "feat: add AlistClient with auth, list, and getRawUrl"
```

---

## Task 2: 后端 — CloudFileServiceImpl 支持 Alist 类型

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/cloudfile/service/impl/CloudFileServiceImpl.java`

- [ ] **Step 1: 添加 import**

```java
import com.yuyutian.mytools.cloudfile.service.impl.AlistClient;
```

- [ ] **Step 2: 添加 Alist 常量**

在类中添加：

```java
private static final String AES_KEY = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=";
private static final String ALIST_TYPE = "alist";
```

- [ ] **Step 3: 添加 AlistClient 相关字段**

在 `buildClient` 方法之前添加：

```java
private String alistRawUrl(Long userId, Long accountId, String path) {
    AlistClient client = buildAlistClient(userId, accountId);
    try {
        return client.getRawUrl(path);
    } catch (IOException e) {
        if ("TOKEN_EXPIRED".equals(e.getMessage())) {
            // re-login and retry once
            client = rebuildAlistClient(userId, accountId);
            try {
                return client.getRawUrl(path);
            } catch (Exception ex) {
                throw new BusinessException("53001", "获取预览链接失败: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        throw new BusinessException("53001", "获取预览链接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    } catch (Exception e) {
        throw new BusinessException("53001", "获取预览链接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

private AlistClient buildAlistClient(Long userId, Long accountId) {
    WebdavAccount account = resolveAccount(userId, accountId);
    if (!ALIST_TYPE.equals(account.getType())) {
        throw new BusinessException("40002", "账号类型不是 Alist", HttpStatus.BAD_REQUEST);
    }
    String token = decrypt(account.getPassword());
    return new AlistClient(account.getUrl(), account.getUsername(), token);
}

private AlistClient rebuildAlistClient(Long userId, Long accountId) {
    WebdavAccount account = resolveAccount(userId, accountId);
    String newToken = "";
    try {
        String plainPassword = decrypt(account.getPassword());
        AlistClient tempClient = new AlistClient(account.getUrl(), account.getUsername(), "");
        newToken = tempClient.login(plainPassword);
        // re-encrypt and save the new token
        reSaveAlistToken(accountId, encrypt(newToken));
    } catch (Exception e) {
        log.error("Failed to refresh Alist token", e);
        throw new BusinessException("53002", "Alist 登录失败，请检查账号配置", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return new AlistClient(account.getUrl(), account.getUsername(), newToken);
}

private WebdavAccount resolveAccount(Long userId, Long accountId) {
    WebdavAccount account;
    if (accountId != null) {
        account = webdavAccountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException("40002", "账号不存在或无权访问", HttpStatus.BAD_REQUEST);
        }
    } else {
        account = webdavAccountMapper.selectDefaultByUserId(userId);
        if (account == null) {
            throw new BusinessException("40001", "请先配置云盘账号", HttpStatus.BAD_REQUEST);
        }
    }
    return account;
}

private String decrypt(String encrypted) {
    if (encrypted == null || encrypted.isBlank()) return "";
    try {
        return AesEncryptUtils.decrypt(encrypted, AES_KEY);
    } catch (Exception e) {
        return ""; // fall back to empty
    }
}

private String encrypt(String plain) {
    try {
        return AesEncryptUtils.encrypt(plain, AES_KEY);
    } catch (Exception e) {
        throw new BusinessException("50001", "加密失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

注意：`reSaveAlistToken` 需要在 `WebdavAccountMapper` 中添加 `updatePasswordById` 方法（见 Task 2 Step 4）。

- [ ] **Step 4: Mapper 添加 updatePasswordById**

修改 `src/main/java/com/yuyutian/mytools/webdav/mapper/WebdavAccountMapper.java`：

```java
int updatePasswordById(@Param("id") Long id, @Param("password") String password);
```

在 `src/main/resources/mapper/webdav/WebdavAccountMapper.xml` 中添加：

```xml
<update id="updatePasswordById">
    UPDATE webdav_account SET password = #{password} WHERE id = #{id}
</update>
```

- [ ] **Step 5: 修改 buildClient 方法**

将 `buildClient` 方法中 WebdavClient 创建部分改为：

```java
private WebdavClient buildClient(Long userId, Long accountId) {
    WebdavAccount account = resolveAccount(userId, accountId);
    if (ALIST_TYPE.equals(account.getType())) {
        throw new BusinessException("40002", "请使用 Alist 接口访问 Alist 账号", HttpStatus.BAD_REQUEST);
    }
    String plainPassword = decrypt(account.getPassword());
    return new WebdavClient(account.getUrl(), account.getUsername(), plainPassword);
}
```

同时将 `resolveAccount` 和 `decrypt` 提取出来供 `buildAlistClient` 使用（已在 Step 3 中添加）。

- [ ] **Step 6: 添加 AlistRawUrl 端点**

在 `CloudFileController` 中添加：

```java
@GetMapping("/api/cloud/alist/raw")
public ResponseEntity<Result<Map<String, String>>> getAlistRawUrl(
        @RequestHeader("Authorization") String auth,
        @RequestParam("path") String path,
        @RequestParam(value = "accountId", required = false) Long accountId) {
    Long userId = resolveUserId(auth);
    String rawUrl = cloudFileService.alistRawUrl(userId, accountId, path);
    return ResponseEntity.ok(Result.success(Map.of("rawUrl", rawUrl)));
}
```

注意：这需要 `CloudFileService` 接口添加 `alistRawUrl` 方法：

在 `CloudFileService.java` 中添加：

```java
String alistRawUrl(Long userId, Long accountId, String path);
```

在 `CloudFileServiceImpl.java` 中已有实现（见 Step 3）。

- [ ] **Step 7: 验证编译**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/service/CloudFileService.java
git add src/main/java/com/yuyutian/mytools/cloudfile/service/impl/CloudFileServiceImpl.java
git add src/main/java/com/yuyutian/mytools/cloudfile/controller/CloudFileController.java
git add src/main/java/com/yuyutian/mytools/webdav/mapper/WebdavAccountMapper.java
git add src/main/resources/mapper/webdav/WebdavAccountMapper.xml
git commit -m "feat: support Alist account type in CloudFileService"
```

---

## Task 3: 前端 — API 类型定义

**Files:**
- Create: `webapp/src/typings/api/alist.d.ts`
- Modify: `webapp/src/service/api/alist.ts`

- [ ] **Step 1: 创建 Alist API 类型定义**

`webapp/src/typings/api/alist.d.ts`：

```ts
declare namespace Api {
  namespace Alist {
    interface AlistFileItem {
      name: string;
      size: number;
      is_dir: boolean;
      modified: string;
      created: string;
      type: number;
      thumb?: string;
      sign?: string;
      raw_url?: string;
    }

    interface AlistListResponse {
      content: AlistFileItem[];
      total: number;
      provider: string;
      readme: string;
      header: string;
      write: boolean;
    }

    interface AlistLoginRequest {
      username: string;
      password: string; // SHA-256 hex
    }

    interface AlistLoginResponse {
      token: string;
      device_key: string;
    }

    interface AlistRawUrlResponse {
      name: string;
      size: number;
      is_dir: boolean;
      raw_url: string;
      sign: string;
    }
  }
}
```

- [ ] **Step 2: 创建 Alist API 函数**

`webapp/src/service/api/alist.ts`：

```ts
import { request } from '@/service/request';

/** 获取 Alist 账号列表（过滤 type === 'alist'） */
export function fetchAlistAccounts() {
  return request<Api.Webdav.WebdavAccount[]>({
    url: '/api/webdav/accounts',
    method: 'get'
  });
}

/** 列出 Alist 目录下的文件（复用 cloud file API） */
export function fetchAlistFiles(path = '/', accountId?: string) {
  const params: Record<string, string | number> = { path, depth: 1 };
  if (accountId) params.accountId = accountId;
  return request<Api.CloudFile.CloudFileListResponse>({
    url: '/api/cloud/files',
    method: 'get',
    params
  });
}

/** 获取文件预览直链 */
export function fetchAlistRawUrl(path: string, accountId?: string) {
  const params: Record<string, string> = { path };
  if (accountId) params.accountId = accountId;
  return request<{ rawUrl: string }>({
    url: '/api/cloud/alist/raw',
    method: 'get',
    params
  });
}
```

- [ ] **Step 3: 提交**

```bash
git add webapp/src/typings/api/alist.d.ts webapp/src/service/api/alist.ts
git commit -m "feat: add Alist API types and service functions"
```

---

## Task 4: 前端 — Alist Store

**Files:**
- Create: `webapp/src/store/modules/alist/index.ts`

- [ ] **Step 1: 创建 Alist Store**

```ts
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchAlistAccounts, fetchAlistFiles } from '@/service/api/alist';
import { SetupStoreId } from '@/enum';

export interface AlistTreeNode {
  key: string;
  label: string;
  isLeaf: boolean;
  path: string;
  isDirectory: boolean;
  size?: number;
  lastModified?: string | null;
  children?: AlistTreeNode[];
}

export const useAlistStore = defineStore(SetupStoreId.Alist, () => {
  const currentPath = ref('/');
  const currentAccountId = ref<string>('');
  const fileList = ref<Api.CloudFile.CloudFileItem[]>([]);
  const treeData = ref<AlistTreeNode[]>([]);
  const loading = ref(false);
  const accounts = ref<Api.Webdav.WebdavAccount[]>([]);

  const isEmpty = computed(() => fileList.value.length === 0);

  function itemToNode(item: Api.CloudFile.CloudFileItem): AlistTreeNode {
    return {
      key: item.path,
      label: item.name,
      isLeaf: !item.isDirectory,
      path: item.path,
      isDirectory: item.isDirectory,
      size: item.size,
      lastModified: item.lastModified
    };
  }

  function updateNodeInTree(nodes: AlistTreeNode[], path: string, updater: (node: AlistTreeNode) => void): boolean {
    for (const node of nodes) {
      if (node.path === path) { updater(node); return true; }
      if (node.children) { if (updateNodeInTree(node.children, path, updater)) return true; }
    }
    return false;
  }

  async function loadFiles(path: string, parentPath?: string) {
    loading.value = true;
    try {
      const { data, error } = await fetchAlistFiles(path, currentAccountId.value || undefined);
      if (error || !data) return;

      const items = data.items || [];
      fileList.value = items;
      currentPath.value = data.path || path;

      const effectiveParent = parentPath ?? (path === '/' ? '/' : path.substring(0, path.lastIndexOf('/')) || '/');

      if (effectiveParent === '/') {
        treeData.value = items.map(itemToNode);
      } else {
        const found = updateNodeInTree(treeData.value, effectiveParent, node => {
          node.children = items.map(itemToNode);
        });
        if (found) treeData.value = [...treeData.value];
      }
    } finally {
      loading.value = false;
    }
  }

  async function init(accountId?: string) {
    if (accountId) currentAccountId.value = accountId;
    currentPath.value = '/';
    await loadFiles('/');
  }

  async function refresh() {
    await loadFiles(currentPath.value);
  }

  async function navigateTo(path: string) {
    loading.value = true;
    try {
      await loadFiles(path);
    } finally {
      loading.value = false;
    }
  }

  async function loadTreeNodeChildren(node: AlistTreeNode) {
    if (!node.isDirectory) return;
    if (node.children?.length) return;
    const { data } = await fetchAlistFiles(node.path, currentAccountId.value || undefined);
    if (!data) return;
    updateNodeInTree(treeData.value, node.path, n => {
      n.children = (data.items || []).map(itemToNode);
    });
    treeData.value = [...treeData.value];
  }

  async function loadAccounts() {
    const { data } = await fetchAlistAccounts();
    if (data) accounts.value = data;
  }

  return {
    currentPath,
    currentAccountId,
    fileList,
    treeData,
    loading,
    isEmpty,
    accounts,
    loadFiles,
    init,
    refresh,
    navigateTo,
    loadTreeNodeChildren,
    loadAccounts
  };
});
```

在 `src/main/java/com/yuyutian/mytools/common/SetupStoreId.java` 中添加：

```java
Alist("alist"),
```

- [ ] **Step 2: 提交**

```bash
git add webapp/src/store/modules/alist/index.ts
git add src/main/java/com/yuyutian/mytools/common/SetupStoreId.java
git commit -m "feat: add Alist Pinia store"
```

---

## Task 5: 前端 — Alist 页面

**Files:**
- Create: `webapp/src/views/alist/index.vue`

- [ ] **Step 1: 创建 Alist 页面（简化版 browse）**

参考现有 `cloud-file/browse/index.vue` 的结构，创建精简版：

```vue
<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useAlistStore } from '@/store/modules/alist';
import { fetchAlistRawUrl } from '@/service/api/alist';
import {
  NLayout, NLayoutSider, NLayoutContent,
  NTree, NBreadcrumb, NBreadcrumbItem,
  NButton, NSpace, NDataTable,
  NModal, NInput, NImage, NEmpty,
  NSpin, useMessage, NDrawer, NDrawerContent,
  NInputGroup, NCard
} from 'naive-ui';
import {
  FolderOutline, DocumentTextOutline, ImageOutline,
  FilmOutline, MusicalNotesOutline, RefreshOutline,
  DownloadOutline, CreateOutline, TrashOutline,
  CloudUploadOutline, Folder, SettingsOutline
} from '@vicons/ionicons5';
import type { TreeOption } from 'naive-ui';

const message = useMessage();
const store = useAlistStore();

// 账号相关
const accountOptions = computed(() =>
  store.accounts.map(a => ({ label: a.name, value: a.id }))
);

async function handleAccountChange(accountId: string) {
  await store.init(accountId);
}

// 树形目录
const selectedTreeKey = ref<string[]>([]);

async function onMounted() {
  await store.loadAccounts();
  const defaultAccount = store.accounts.find(a => a.isDefault === 1) || store.accounts[0];
  if (defaultAccount) {
    store.currentAccountId = defaultAccount.id;
    await store.init(defaultAccount.id);
  }
}

// 面包屑
const breadcrumbs = computed(() => {
  const parts = store.currentPath.split('/').filter(Boolean);
  const crumbs = [{ label: '根目录', path: '/' }];
  let accumulated = '';
  for (const part of parts) {
    accumulated += '/' + part;
    crumbs.push({ label: part, path: accumulated });
  }
  return crumbs;
});

function handleBreadcrumbNavigate(path: string) {
  if (path !== store.currentPath) store.navigateTo(path);
}

// 表格列
const columns = [
  {
    title: '名称',
    key: 'name',
    render(row: Api.CloudFile.CloudFileItem) {
      const icon = row.isDirectory
        ? h(ImageOutline, { size: 16, style: 'margin-right: 6px; color: #f0ad4e;' })
        : h(DocumentTextOutline, { size: 16, style: 'margin-right: 6px; color: #909399;' });
      return h('span', { style: 'display: flex; align-items: center; cursor: pointer;' }, [
        icon,
        h('span', { onClick: () => row.isDirectory ? store.navigateTo(row.path) : handlePreview(row) }, row.name)
      ]);
    }
  },
  {
    title: '大小',
    key: 'size',
    width: 100,
    render(row: Api.CloudFile.CloudFileItem) {
      if (row.isDirectory) return '-';
      return row.size ? formatSize(row.size) : '-';
    }
  },
  {
    title: '修改时间',
    key: 'lastModified',
    width: 180,
    render(row: Api.CloudFile.CloudFileItem) {
      return row.lastModified ? row.lastModified.replace('T', ' ').substring(0, 19) : '-';
    }
  }
];

function formatSize(bytes: number): string {
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

// 预览抽屉
const previewDrawer = reactive({ show: false, title: '', rawUrl: '', loading: false });

async function handlePreview(file: Api.CloudFile.CloudFileItem) {
  previewDrawer.title = file.name;
  previewDrawer.show = true;
  previewDrawer.rawUrl = '';
  previewDrawer.loading = true;
  try {
    const { data, error } = await fetchAlistRawUrl(file.path, store.currentAccountId || undefined);
    if (error || !data?.rawUrl) {
      message.error('获取预览失败');
      previewDrawer.show = false;
      return;
    }
    previewDrawer.rawUrl = data.rawUrl;
  } catch {
    message.error('获取预览失败');
    previewDrawer.show = false;
  } finally {
    previewDrawer.loading = false;
  }
}

function isImageFile(name: string) {
  return /\.(jpg|jpeg|png|gif|webp|bmp|svg)$/i.test(name);
}

onMounted(onMounted);
</script>

<template>
  <div style="height: 100%">
    <n-layout has-sider style="height: 100%">
      <!-- 左侧目录树 -->
      <n-layout-sider :width="220" bordered content-style="padding: 8px;">
        <n-space vertical :size="8" style="height: 100%;">
          <div style="font-size: 12px; color: #888; padding: 4px;">Alist 文件</div>
          <n-spin :show="store.loading" style="flex: 1; overflow: auto;">
            <n-tree
              v-model:selected-keys="selectedTreeKey"
              :data="store.treeData"
              :expand-on-click="true"
              virtual-scroll
              block-node
              @update:selected-keys="(keys) => { if (keys[0]) store.navigateTo(keys[0] as string); }"
              @load="(node: TreeOption) => store.loadTreeNodeChildren(node as any)"
            />
            <n-empty v-if="!store.loading && store.treeData.length === 0" description="暂无文件" style="margin-top: 16px;" />
          </n-spin>
        </n-space>
      </n-layout-sider>

      <!-- 右侧内容区 -->
      <n-layout-content content-style="display: flex; flex-direction: column; height: 100%;">
        <!-- 账号选择器 -->
        <div style="display:flex;align-items:center;gap:8px;padding:8px 16px 0;">
          <n-select
            v-model:value="store.currentAccountId"
            :options="accountOptions"
            placeholder="选择 Alist 账号"
            style="width:200px;"
            @update:value="handleAccountChange"
          />
        </div>

        <!-- 工具栏：面包屑 + 操作按钮 -->
        <div style="display:flex;align-items:center;gap:12px;padding:8px 16px;border-bottom:1px solid #f0f0f0;flex-shrink:0;">
          <n-breadcrumb style="flex:1;min-width:0;">
            <n-breadcrumb-item
              v-for="crumb in breadcrumbs"
              :key="crumb.path"
              :clickable="crumb.path !== store.currentPath"
              @click="handleBreadcrumbNavigate(crumb.path)"
            >
              {{ crumb.label }}
            </n-breadcrumb-item>
          </n-breadcrumb>
          <n-space>
            <n-button size="small" @click="store.refresh()">
              <template #icon><refresh-outline /></template>
              刷新
            </n-button>
          </n-space>
        </div>

        <!-- 文件列表 -->
        <div style="flex:1;overflow:auto;padding:0 16px 16px;">
          <n-data-table
            :columns="columns"
            :data="store.fileList"
            :loading="store.loading"
            :row-key="(row: Api.CloudFile.CloudFileItem) => row.path"
            :pagination="false"
            :bordered="false"
            virtual-scroll
            style="margin-top: 8px;"
          />
          <n-empty v-if="!store.loading && store.isEmpty" description="该目录为空" style="margin-top: 48px;" />
        </div>
      </n-layout-content>
    </n-layout>

    <!-- 预览抽屉 -->
    <n-drawer v-model:show="previewDrawer.show" :width="800" placement="right">
      <n-drawer-content :title="previewDrawer.title" closable>
        <n-spin :show="previewDrawer.loading" description="加载中...">
          <div v-if="isImageFile(previewDrawer.title)" style="text-align:center;">
            <n-image :src="previewDrawer.rawUrl" width="100%" />
          </div>
          <div v-else-if="previewDrawer.title.endsWith('.md') || previewDrawer.title.endsWith('.txt')">
            <iframe :src="previewDrawer.rawUrl" style="width:100%;height:70vh;border:none;" />
          </div>
          <div v-else style="text-align:center;padding:48px;">
            <p>此文件类型不支持内嵌预览</p>
            <n-button tag="a" :href="previewDrawer.rawUrl" target="_blank" type="primary" style="margin-top:16px;">
              在新窗口打开
            </n-button>
          </div>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>
```

- [ ] **Step 2: 验证编译**

Run: `cd webapp && npx vue-tsc --noEmit --skipLibCheck`
Expected: 无类型错误

- [ ] **Step 3: 提交**

```bash
git add webapp/src/views/alist/index.vue
git commit -m "feat: add Alist file browser page"
```

---

## Task 6: 前端 — 路由和菜单

**Files:**
- Modify: `webapp/src/locales/langs/zh-cn.ts`
- Modify: `webapp/src/locales/langs/en-us.ts`
- Modify: `webapp/src/router/elegant/routes.ts`

- [ ] **Step 1: 添加 i18n 翻译**

`zh-cn.ts` 中 `route` 部分添加：

```ts
'alist': 'Alist',
```

`en-us.ts` 中 `route` 部分添加：

```ts
'alist': 'Alist',
```

- [ ] **Step 2: 运行路由生成**

```bash
cd webapp && npx pnpm gen-route
```

这会自动更新 `routes.ts`、`imports.ts`、`elegant-router.d.ts`。
如果 elegant-router 没有配置 `src/views/alist/` 目录作为视图路径，需要手动添加路由。

- [ ] **Step 3: 检查 routes.ts 中的 alist 路由**

确保生成结果包含：

```ts
{
  name: 'alist',
  path: '/alist',
  component: 'layout.base',
  meta: {
    title: 'alist',
    i18nKey: 'route.alist',
    icon: 'mdi:cloud-outline',
    order: 5
  },
  children: [
    {
      name: 'alist_index',
      path: '/alist/index',
      component: 'view.alist_index',
      meta: {
        title: 'alist_index',
        i18nKey: 'route.alist_index',
        icon: 'mdi:file-document-outline'
      }
    }
  ]
}
```

如果 `pnpm gen-route` 没有自动生成，需要手动添加。

- [ ] **Step 4: 调整菜单**

在侧边菜单中将 `alist` 作为独立菜单项放在"云端文件"之后（order: 5），或者作为"云端文件"的子菜单。参考"云端文件"的菜单结构添加：

```ts
{
  name: 'alist',
  path: '/alist',
  meta: {
    title: 'Alist',
    i18nKey: 'route.alist',
    icon: 'mdi:cloud-outline'
  }
}
```

- [ ] **Step 5: 提交**

```bash
git add webapp/src/locales/langs/zh-cn.ts webapp/src/locales/langs/en-us.ts
git add webapp/src/router/elegant/routes.ts
git add webapp/src/router/elegant/imports.ts  # 如果有变化
git add webapp/src/router/elegant/elegant-router.d.ts  # 如果有变化
git commit -m "feat: add Alist route and menu configuration"
```

---

## Task 7: 构建验证

- [ ] **Step 1: 后端编译**

```bash
mvn compile -q
```

- [ ] **Step 2: 前端类型检查**

```bash
cd webapp && npx vue-tsc --noEmit --skipLibCheck
```

- [ ] **Step 3: 前端构建**

```bash
cd webapp && npx pnpm build
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat: complete Alist integration"
```

---

## Task 8: 浏览器验证

- [ ] 登录后访问 `/alist`，页面正常加载
- [ ] 账号选择器显示所有 Alist 账号，选中后加载文件列表
- [ ] 点击文件夹可进入子目录
- [ ] 点击文件弹出预览抽屉（图片直接显示，其他类型显示"在新窗口打开"）
- [ ] 面包屑导航正常工作
- [ ] 从 Alist 页面切换到其他页面无白屏
