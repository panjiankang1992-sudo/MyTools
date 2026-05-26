# 云端文件页面重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将云端文件从三个子页面简化为两个子页面，WebDAV 和 Alist 页面各自内嵌账号管理抽屉；后端修复 Alist 账号路由。

**Architecture:** 删除 `cloud-file_accounts` 独立页面；提取共享的 `AccountDrawer.vue` 组件给两个页面复用；后端 `listFiles()` 根据账号类型路由到 `AlistClient.list()` 或 `WebdavClient.list()`。

**Tech Stack:** Vue 3 + TypeScript + NaiveUI + Pinia / Java 21 + Spring Boot + MyBatis

---

### File Structure Map

**不变（职责不同，不合并 store）：**
- `webapp/src/store/modules/cloudfile/index.ts` — WebDAV 文件浏览状态
- `webapp/src/store/modules/alist/index.ts` — Alist 文件浏览状态
- `webapp/src/service/api/webdav.ts` — 账号 CRUD API（不变）
- `webapp/src/service/api/cloudfile.ts` — 文件操作 API（不变）
- `webapp/src/typings/api/webdav.d.ts` — 类型（不变）

**删除：**
- `webapp/src/views/cloud-file/accounts/index.vue`

**创建：**
- `webapp/src/views/cloud-file/components/AccountDrawer.vue` — 共享账号管理抽屉组件

**修改（9 个）：**
- `src/main/java/.../cloudfile/service/impl/CloudFileServiceImpl.java` — 路由 Alist 账号
- `webapp/src/router/elegant/routes.ts` — 删除 `cloud-file_accounts` 子路由
- `webapp/src/router/elegant/imports.ts` — 删除 accounts 的 import
- `webapp/src/router/elegant/transform.ts` — 删除 routeMap 中的 `cloud-file_accounts`
- `webapp/src/typings/elegant-router.d.ts` — 删除 `cloud-file_accounts` 相关类型
- `webapp/src/locales/langs/zh-cn.ts` — `'cloud-file_browse': '坚果云'` → `'WebDAV'`，删除 `cloud-file_accounts`
- `webapp/src/locales/langs/en-us.ts` — 同上
- `webapp/src/views/cloud-file/browse/index.vue` — 移除跳转按钮，改为内嵌抽屉
- `webapp/src/views/cloud-file/alist/index.vue` — 添加内嵌抽屉

---

### Task 1: 后端 — 修复 CloudFileServiceImpl Alist 路由

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/cloudfile/service/impl/CloudFileServiceImpl.java:31-63`

当前 `listFiles()` 直接调用 `buildClient()`，对 Alist 账号抛出异常。`AlistClient.list(path)` 已实现，需接入。

- [ ] **Step 1: 重构 listFiles() 路由逻辑**

将 `listFiles()` 方法改为：

```java
@Override
public CloudFileListResponse listFiles(Long userId, Long accountId, String path, int depth) {
    WebdavAccount account = resolveAccount(userId, accountId);
    if (ALIST_TYPE.equals(account.getType())) {
        // Alist 账号走 AlistClient.list()（Alist 不支持 depth 参数，忽略）
        AlistClient client = buildAlistClient(userId, accountId);
        try {
            return client.list(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "无法连接到 Alist 服务: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    // 非 Alist 账号走 WebdavClient.list()
    WebdavClient client = buildClient(userId, accountId);
    try {
        return client.list(path, depth);
    } catch (Exception e) {
        throw new BusinessException("50001", "无法连接到云盘服务: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

**注意：** 不需要再调用 `resolveAccount` 后再调 `buildClient`（那样会重复解析账号）。将 `buildClient` 改为 private，接收已解析的 account 参数。

- [ ] **Step 2: 重构 buildClient 接收 account 参数**

将 `buildClient` 方法签名从 `(Long userId, Long accountId)` 改为 `(WebdavAccount account)`：

```java
private WebdavClient buildClient(WebdavAccount account) {
    if (ALIST_TYPE.equals(account.getType())) {
        throw new BusinessException("40002", "请使用 Alist 接口访问 Alist 账号", HttpStatus.BAD_REQUEST);
    }
    String plainPassword = decrypt(account.getPassword());
    if (plainPassword.isEmpty() && account.getPassword() != null && !account.getPassword().isBlank()) {
        log.error("Failed to decrypt WebDAV password for user {}", account.getUserId());
        throw new BusinessException("50001", "WebDAV 配置无效", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return new WebdavClient(account.getUrl(), account.getUsername(), plainPassword);
}
```

同样将 `buildAlistClient` 改为接收 `WebdavAccount` 参数。

- [ ] **Step 3: 更新所有调用 buildClient 的方法**

`listFiles()`、`getFileContent()`、`downloadFile()`、`uploadFile()`、`createDirectory()`、`rename()`、`move()`、`copy()`、`delete()`、`saveTextFile()` — 所有这些方法都先将 `resolveAccount(userId, accountId)` 的结果存入局部变量 `account`，然后将 `buildClient(userId, accountId)` 改为 `buildClient(account)`。

示例（`getFileContent`）：

```java
@Override
public String getFileContent(Long userId, Long accountId, String path) {
    WebdavAccount account = resolveAccount(userId, accountId);
    if (ALIST_TYPE.equals(account.getType())) {
        throw new BusinessException("40002", "Alist 账号不支持获取文件内容", HttpStatus.BAD_REQUEST);
    }
    WebdavClient client = buildClient(account);
    // ... 其余不变
}
```

示例（`downloadFile`）：

```java
@Override
public byte[] downloadFile(Long userId, Long accountId, String path) {
    WebdavAccount account = resolveAccount(userId, accountId);
    if (ALIST_TYPE.equals(account.getType())) {
        throw new BusinessException("40002", "Alist 账号不支持下载，请使用预览功能", HttpStatus.BAD_REQUEST);
    }
    WebdavClient client = buildClient(account);
    // ... 其余不变
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile
```

Expected: 编译通过，所有 `buildClient(userId, accountId)` 调用替换完毕。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/service/impl/CloudFileServiceImpl.java
git commit -m "fix: route Alist accounts to AlistClient.list() in CloudFileServiceImpl"
```

---

### Task 2: 前端 — 创建共享账号管理抽屉组件

**Files:**
- Create: `webapp/src/views/cloud-file/components/AccountDrawer.vue`

设计要点：
- **Props:** `accountType: 'webdav' | 'alist'`（`'webdav'` 显示所有非 Alist 账号，`'alist'` 只显示 Alist 账号）
- **Events:** `account-change(accountId: string)` — 账号增删改后通知父组件刷新
- **本地状态:** 抽屉开关 `showDrawer`、账号列表 `accounts`、Modal 开关 `showModal`、编辑中的账号 `editingAccount`、表单数据 `formData`、提交状态 `submitting`
- **API 调用:** 使用已有的 `@/service/api/webdav` 中的函数
- **表单字段:**
  - WebDAV：`type`（下拉选择，排除 alist）→ `name` → `url` → `username` → `password`（留空不修改）→ `isDefault`
  - Alist：类型固定为 alist（隐藏），表单简化为：`name` → `url` → `username` → `password`（Alist API 密码说明）→ `isDefault`
- **抽屉关闭后:** 保持账号列表，下次打开时刷新

完整组件代码：

```vue
<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import {
  NButton,
  NDataTable,
  NTag,
  NSpace,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NSwitch,
  NPopconfirm,
  useMessage,
  NDrawer,
  NDrawerContent
} from 'naive-ui';
import { AddOutline, CreateOutline, TrashOutline, StarOutline } from '@vicons/ionicons5';
import {
  fetchWebdavAccounts,
  createWebdavAccount,
  updateWebdavAccount,
  deleteWebdavAccount,
  setDefaultWebdavAccount
} from '@/service/api/webdav';

const props = defineProps<{ accountType: 'webdav' | 'alist' }>();
const emit = defineEmits<{ (e: 'account-change', accountId: string): void }>();

const message = useMessage();
const showDrawer = defineModel<boolean>('show', { default: false });

const accounts = ref<Api.Webdav.WebdavAccount[]>([]);
const loading = ref(false);

// type options for webdav
const typeOptions = [
  { label: '坚果云', value: 'jianguoyun' },
  { label: 'NextCloud', value: 'nextcloud' },
  { label: 'OwnCloud', value: 'owncloud' },
  { label: 'Synology', value: 'synology' },
  { label: 'S3', value: 's3' },
  { label: '自定义', value: 'custom' }
];

const typeLabelMap: Record<string, string> = {
  jianguoyun: '坚果云',
  nextcloud: 'NextCloud',
  owncloud: 'OwnCloud',
  synology: 'Synology',
  s3: 'S3',
  custom: '自定义',
  alist: 'Alist'
};

const drawerTitle = computed(() =>
  props.accountType === 'alist' ? 'Alist 账号管理' : 'WebDAV 账号管理'
);

const filteredAccounts = computed(() => {
  if (props.accountType === 'alist') {
    return accounts.value.filter(a => a.type === 'alist');
  }
  return accounts.value.filter(a => a.type !== 'alist');
});

async function loadAccounts() {
  loading.value = true;
  try {
    const { data } = await fetchWebdavAccounts();
    if (data) accounts.value = data;
  } catch {
    message.error('加载账号列表失败');
  } finally {
    loading.value = false;
  }
}

// Modal state
const showModal = ref(false);
const editingAccount = ref<Api.Webdav.WebdavAccount | null>(null);
const formData = ref({
  type: 'alist',
  name: '',
  url: '',
  username: '',
  password: '',
  isDefault: false
});
const submitting = ref(false);

function openCreateModal() {
  editingAccount.value = null;
  formData.value = {
    type: props.accountType === 'alist' ? 'alist' : 'jianguoyun',
    name: '',
    url: '',
    username: '',
    password: '',
    isDefault: false
  };
  showModal.value = true;
}

function openEditModal(account: Api.Webdav.WebdavAccount) {
  editingAccount.value = account;
  formData.value = {
    type: account.type,
    name: account.name,
    url: account.url,
    username: account.username,
    password: '',
    isDefault: account.isDefault === 1
  };
  showModal.value = true;
}

async function handleSubmit() {
  submitting.value = true;
  try {
    const payload = { ...formData.value };
    if (editingAccount.value) {
      await updateWebdavAccount(editingAccount.value.id, payload);
      message.success('账号已更新');
    } else {
      await createWebdavAccount(payload);
      message.success('账号已创建');
    }
    showModal.value = false;
    await loadAccounts();
    // 通知父组件刷新账号选择器
    emit('account-change', '');
  } catch {
    message.error('操作失败');
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(account: Api.Webdav.WebdavAccount) {
  try {
    await deleteWebdavAccount(account.id);
    message.success('账号已删除');
    await loadAccounts();
    emit('account-change', '');
  } catch {
    message.error('删除失败');
  }
}

async function handleSetDefault(account: Api.Webdav.WebdavAccount) {
  try {
    await setDefaultWebdavAccount(account.id);
    message.success('已设为默认账号');
    await loadAccounts();
    emit('account-change', account.id);
  } catch {
    message.error('操作失败');
  }
}

const columns = computed(() => {
  const cols: any[] = [
    {
      title: '账号名称',
      key: 'name',
      width: 140
    }
  ];

  // Only show type column for webdav page
  if (props.accountType === 'webdav') {
    cols.push({
      title: '类型',
      key: 'type',
      width: 100,
      render(row: Api.Webdav.WebdavAccount) {
        return h(NTag, { type: 'info', size: 'small' }, () => typeLabelMap[row.type] ?? row.type);
      }
    });
  }

  cols.push(
    {
      title: '地址',
      key: 'url',
      ellipsis: { tooltip: true }
    },
    {
      title: '用户名',
      key: 'username',
      width: 140
    },
    {
      title: '状态',
      key: 'isDefault',
      width: 80,
      render(row: Api.Webdav.WebdavAccount) {
        return row.isDefault === 1
          ? h(NTag, { type: 'success', size: 'small' }, () => '默认')
          : null;
      }
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render(row: Api.Webdav.WebdavAccount) {
        return h(NSpace, { size: 'small' }, () => [
          row.isDefault !== 1 && h(
            NButton,
            { size: 'small', onClick: () => handleSetDefault(row) },
            { icon: () => h(StarOutline), default: () => '默认' }
          ),
          h(
            NButton,
            { size: 'small', onClick: () => openEditModal(row) },
            { icon: () => h(CreateOutline) }
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row) },
            {
              trigger: () => h(
                NButton,
                { size: 'small', type: 'error' },
                { icon: () => h(TrashOutline) }
              ),
              default: () => `确定删除账号「${row.name}」？`
            }
          )
        ]);
      }
    }
  );
  return cols;
});

async function handleDrawerAfterEnter() {
  await loadAccounts();
}
</script>

<template>
  <n-drawer v-model:show="showDrawer" :width="480" placement="right" @after-enter="handleDrawerAfterEnter">
    <n-drawer-content :title="drawerTitle" closable>
      <template #header-extra>
        <n-button type="primary" size="small" @click="openCreateModal">
          <template #icon><add-outline /></template>
          添加账号
        </n-button>
      </template>

      <n-data-table
        :columns="columns"
        :data="filteredAccounts"
        :loading="loading"
        :bordered="false"
        size="small"
      />
    </n-drawer-content>
  </n-drawer>

  <!-- 创建/编辑账号弹窗 -->
  <n-modal
    v-model:show="showModal"
    :title="editingAccount ? '编辑账号' : '添加账号'"
    preset="card"
    style="width: 520px;"
    :style="{ '--n-color': '#ffffff' }"
  >
    <n-form :model="formData" label-placement="left" label-width="90">
      <n-form-item v-if="accountType === 'webdav'" label="服务类型" required>
        <n-select v-model:value="formData.type" :options="typeOptions" placeholder="请选择服务类型" />
      </n-form-item>
      <n-form-item label="账号名称" required>
        <n-input v-model:value="formData.name" placeholder="如：工作坚果云" />
      </n-form-item>
      <n-form-item :label="accountType === 'alist' ? 'Alist 地址' : 'WebDAV 地址'" required>
        <n-input
          v-model:value="formData.url"
          :placeholder="accountType === 'alist' ? 'https://alist.example.com' : 'https://dav.jianguoyun.com/dav/'"
        />
      </n-form-item>
      <n-form-item label="用户名" required>
        <n-input v-model:value="formData.username" placeholder="用户名" />
      </n-form-item>
      <n-form-item :label="accountType === 'alist' ? 'Alist API 密码' : '密码'" :required="!editingAccount">
        <n-input
          v-model:value="formData.password"
          type="password"
          :placeholder="editingAccount ? '留空不修改' : (accountType === 'alist' ? 'Alist API 密码' : 'WebDAV 密码')"
        />
      </n-form-item>
      <n-form-item label="设为默认">
        <n-switch v-model:value="formData.isDefault" />
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="showModal = false">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ editingAccount ? '保存' : '创建' }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls webapp/src/views/cloud-file/components/
```

Expected: `AccountDrawer.vue` 存在。

- [ ] **Step 3: 提交**

```bash
git add webapp/src/views/cloud-file/components/AccountDrawer.vue
git commit -m "feat: extract shared AccountDrawer component for cloud-file pages"
```

---

### Task 3: 前端 — WebDAV 页面内嵌账号管理抽屉

**Files:**
- Modify: `webapp/src/views/cloud-file/browse/index.vue`

- [ ] **Step 1: 添加抽屉 import 和状态**

在 `<script setup>` 顶部添加：

```ts
import AccountDrawer from '../components/AccountDrawer.vue';
```

添加状态：

```ts
const accountDrawerShow = ref(false);

function openAccountDrawer() {
  accountDrawerShow.value = true;
}

async function handleAccountChange(accountId: string) {
  await loadAccounts();
  if (accountId) {
    await store.init(accountId);
  }
}
```

- [ ] **Step 2: 替换跳转按钮为抽屉触发**

将模板中账号选择器旁的按钮：
```html
<n-button size="small" @click="$router.push('/cloud-file/accounts')">
  <template #icon><settings-outline /></template>
  管理
</n-button>
```

替换为：

```html
<n-button size="small" @click="openAccountDrawer">
  <template #icon><settings-outline /></template>
  管理
</n-button>
```

- [ ] **Step 3: 在模板末尾添加抽屉组件**

在 `</div>`（根 div）闭合标签之前，`<!-- 复制弹窗 -->` 之前添加：

```html
<!-- 账号管理抽屉 -->
<account-drawer
  v-model:show="accountDrawerShow"
  account-type="webdav"
  @account-change="handleAccountChange"
/>
```

- [ ] **Step 4: 类型检查**

```bash
cd webapp && pnpm typecheck 2>&1 | grep -i "cloud-file" | head -20
```

Expected: 无 cloud-file 相关错误。

- [ ] **Step 5: 提交**

```bash
git add webapp/src/views/cloud-file/browse/index.vue
git commit -m "refactor: replace accounts navigation with embedded drawer in browse page"
```

---

### Task 4: 前端 — Alist 页面内嵌账号管理抽屉

**Files:**
- Modify: `webapp/src/views/cloud-file/alist/index.vue`

- [ ] **Step 1: 添加抽屉 import 和状态**

在 `<script setup>` 顶部添加 import：

```ts
import AccountDrawer from '../components/AccountDrawer.vue';
```

在 `const previewDrawer` 之前添加抽屉状态：

```ts
const accountDrawerShow = ref(false);

function openAccountDrawer() {
  accountDrawerShow.value = true;
}

async function handleAccountChange(_accountId: string) {
  await store.loadAccounts();
  const alistAccounts = store.accounts.filter(a => a.type === 'alist');
  if (alistAccounts.length > 0) {
    const defaultAccount = alistAccounts.find(a => a.isDefault === 1) || alistAccounts[0];
    store.currentAccountId = defaultAccount.id;
    await store.init(defaultAccount.id);
  } else {
    store.currentAccountId = '';
    store.clearFiles();
  }
  selectedTreeKey.value = [];
}
```

- [ ] **Step 2: 在账号选择器旁添加管理按钮**

在 `<n-select ... @update:value="handleAccountChange" />` 后添加：

```html
<n-button size="small" @click="openAccountDrawer">
  <template #icon><settings-outline /></template>
  管理
</n-button>
```

（需要引入 `SettingsOutline` 图标 — 在现有的 `@vicons/ionicons5` import 中已包含 `SettingsOutline`，检查现有 import 中是否有，如果没有则添加。）

- [ ] **Step 3: 在模板末尾添加抽屉组件**

在 `</div>`（根 div）闭合标签之前，`<!-- 预览抽屉 -->` 之前添加：

```html
<!-- 账号管理抽屉 -->
<account-drawer
  v-model:show="accountDrawerShow"
  account-type="alist"
  @account-change="handleAccountChange"
/>
```

- [ ] **Step 4: 类型检查**

```bash
cd webapp && pnpm typecheck 2>&1 | grep -i "alist" | head -20
```

Expected: 无 alist 相关错误。

- [ ] **Step 5: 提交**

```bash
git add webapp/src/views/cloud-file/alist/index.vue
git commit -m "refactor: add embedded account drawer to alist page"
```

---

### Task 5: 前端 — 删除 cloud-file_accounts 路由和 i18n

**Files:**
- Modify: `webapp/src/router/elegant/routes.ts`
- Modify: `webapp/src/router/elegant/imports.ts`
- Modify: `webapp/src/router/elegant/transform.ts`
- Modify: `webapp/src/typings/elegant-router.d.ts`
- Modify: `webapp/src/locales/langs/zh-cn.ts`
- Modify: `webapp/src/locales/langs/en-us.ts`
- Delete: `webapp/src/views/cloud-file/accounts/index.vue`

- [ ] **Step 1: 修改 routes.ts — 删除 cloud-file_accounts 子路由**

在 `cloud-file` 的 `children` 数组中，删除整个 `cloud-file_accounts` 对象：

```ts
// 删除这段：
{
  name: 'cloud-file_accounts',
  path: '/cloud-file/accounts',
  component: 'view.cloud-file_accounts',
  meta: {
    title: 'cloud-file_accounts',
    i18nKey: 'route.cloud-file_accounts',
    icon: 'mdi:account-cog',
    order: 2
  }
},
```

保留 `cloud-file_browse` 和 `cloud-file_alist`，并更新 order：
- `cloud-file_browse`: order: 1
- `cloud-file_alist`: order: 2（从 3 改为 2）

- [ ] **Step 2: 修改 imports.ts — 删除 accounts import**

删除这一行：

```ts
"cloud-file_accounts": () => import("@/views/cloud-file/accounts/index.vue"),
```

- [ ] **Step 3: 修改 transform.ts — 删除 routeMap 条目**

在 `routeMap` 对象中删除：

```ts
"cloud-file_accounts": "/cloud-file/accounts",
```

- [ ] **Step 4: 修改 elegant-router.d.ts — 删除 cloud-file_accounts**

在 `RouteMap` 类型中删除：

```ts
"cloud-file_accounts": "/cloud-file/accounts";
```

在 `LastLevelRouteKey` 类型中删除：

```ts
| "cloud-file_accounts"
```

- [ ] **Step 5: 修改 zh-cn.ts — 更新 i18n**

将：
```ts
'cloud-file_browse': '坚果云',
'cloud-file_accounts': 'WebDAV 管理',
```

改为：
```ts
'cloud-file_browse': 'WebDAV',
```

- [ ] **Step 6: 修改 en-us.ts — 更新 i18n**

将：
```ts
'cloud-file_browse': 'JianGuoYun',
'cloud-file_accounts': 'WebDAV Manager',
```

改为：
```ts
'cloud-file_browse': 'WebDAV',
```

- [ ] **Step 7: 删除 accounts 页面文件**

```bash
rm webapp/src/views/cloud-file/accounts/index.vue
```

- [ ] **Step 8: 编译验证**

```bash
cd webapp && pnpm typecheck 2>&1 | grep -E "cloud-file|elegant" | head -20
```

Expected: 无相关错误。

- [ ] **Step 9: 提交**

```bash
git add webapp/src/router/elegant/routes.ts webapp/src/router/elegant/imports.ts webapp/src/router/elegant/transform.ts webapp/src/typings/elegant-router.d.ts webapp/src/locales/langs/zh-cn.ts webapp/src/locales/langs/en-us.ts webapp/src/views/cloud-file/accounts/index.vue
git commit -m "refactor: remove cloud-file_accounts route and rename browse to WebDAV"
```

---

### Task 6: 构建和验证

- [ ] **Step 1: 后端编译**

```bash
mvn compile
```

Expected: 编译通过。

- [ ] **Step 2: 前端类型检查**

```bash
cd webapp && pnpm typecheck
```

Expected: 无错误。

- [ ] **Step 3: 浏览器验证清单**

1. 导航到 `/cloud-file` → 自动重定向到 `/cloud-file/browse`，菜单显示"云端文件"父级展开后显示"WebDAV"和"Alist"
2. WebDAV 页面顶部显示账号选择器 + "管理"按钮，点击"管理"右侧展开 480px 抽屉
3. 抽屉内显示所有非 Alist 类型账号列表（名称、类型、地址、用户名、是否默认、操作）
4. 添加新账号后抽屉内列表刷新，账号选择器选项同步更新
5. 删除当前选中账号后自动切换到默认账号
6. Alist 页面同样内嵌账号管理抽屉，只显示 Alist 类型账号
7. Alist 页面的表单无类型选择器（类型固定为 alist），密码说明为"Alist API 密码"
8. 后端日志确认 Alist 账号的 listFiles 调用走了 `AlistClient.list()` 路径
