<script setup lang="ts">
import { ref, onMounted, h } from 'vue';
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
  useMessage
} from 'naive-ui';
import {
  AddOutline,
  CreateOutline,
  TrashOutline,
  StarOutline
} from '@vicons/ionicons5';
import {
  fetchWebdavAccounts,
  createWebdavAccount,
  updateWebdavAccount,
  deleteWebdavAccount,
  setDefaultWebdavAccount
} from '@/service/api/webdav';

const message = useMessage();
const accounts = ref<Api.Webdav.WebdavAccount[]>([]);
const loading = ref(false);

const typeOptions = [
  { label: '坚果云', value: 'jianguoyun' },
  { label: 'NextCloud', value: 'nextcloud' },
  { label: 'OwnCloud', value: 'owncloud' },
  { label: 'Synology', value: 'synology' },
  { label: 'Alist', value: 'alist' },
  { label: 'S3', value: 's3' },
  { label: '自定义', value: 'custom' }
];

const typeLabelMap: Record<string, string> = {
  jianguoyun: '坚果云',
  nextcloud: 'NextCloud',
  owncloud: 'OwnCloud',
  synology: 'Synology',
  alist: 'Alist',
  s3: 'S3',
  custom: '自定义'
};

// Modal state
const showModal = ref(false);
const editingAccount = ref<Api.Webdav.WebdavAccount | null>(null);
const formData = ref({
  type: 'jianguoyun',
  name: '',
  url: '',
  username: '',
  password: '',
  isDefault: false
});
const submitting = ref(false);

function openCreateModal() {
  editingAccount.value = null;
  formData.value = { type: 'jianguoyun', name: '', url: '', username: '', password: '', isDefault: false };
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
    if (editingAccount.value) {
      await updateWebdavAccount(editingAccount.value.id, formData.value);
      message.success('账号已更新');
    } else {
      await createWebdavAccount(formData.value);
      message.success('账号已创建');
    }
    showModal.value = false;
    await loadAccounts();
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
  } catch {
    message.error('删除失败');
  }
}

async function handleSetDefault(account: Api.Webdav.WebdavAccount) {
  try {
    await setDefaultWebdavAccount(account.id);
    message.success('已设为默认账号');
    await loadAccounts();
  } catch {
    message.error('操作失败');
  }
}

const columns = [
  {
    title: '账号名称',
    key: 'name',
    width: 150
  },
  {
    title: '类型',
    key: 'type',
    width: 100,
    render(row: Api.Webdav.WebdavAccount) {
      return h(NTag, { type: 'info', size: 'small' }, () => typeLabelMap[row.type] ?? row.type);
    }
  },
  {
    title: '地址',
    key: 'url',
    ellipsis: { tooltip: true }
  },
  {
    title: '用户名',
    key: 'username',
    width: 160
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
];

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

onMounted(() => loadAccounts());
</script>

<template>
  <div style="padding: 24px;">
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
      <h2 style="margin: 0; font-size: 18px; font-weight: 600;">WebDAV 账号管理</h2>
      <n-button type="primary" @click="openCreateModal">
        <template #icon><add-outline /></template>
        添加账号
      </n-button>
    </div>

    <n-data-table :columns="columns" :data="accounts" :loading="loading" :bordered="false" />

    <n-modal v-model:show="showModal" :title="editingAccount ? '编辑账号' : '添加账号'" style="width: 520px;">
      <n-form :model="formData" label-placement="left" label-width="90">
        <n-form-item label="服务类型" required>
          <n-select v-model:value="formData.type" :options="typeOptions" placeholder="请选择服务类型" />
        </n-form-item>
        <n-form-item label="账号名称" required>
          <n-input v-model:value="formData.name" placeholder="如：工作坚果云" />
        </n-form-item>
        <n-form-item label="WebDAV 地址" required>
          <n-input v-model:value="formData.url" placeholder="https://dav.jianguoyun.com/dav/" />
        </n-form-item>
        <n-form-item label="用户名" required>
          <n-input v-model:value="formData.username" placeholder="WebDAV 用户名" />
        </n-form-item>
        <n-form-item :label="editingAccount ? '密码' : '密码'" :required="!editingAccount">
          <n-input
            v-model:value="formData.password"
            type="password"
            :placeholder="editingAccount ? '留空不修改' : 'WebDAV 密码'"
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
  </div>
</template>
