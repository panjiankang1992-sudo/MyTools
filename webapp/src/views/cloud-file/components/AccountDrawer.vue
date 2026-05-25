<script setup lang="ts">
import { ref, h, computed } from 'vue';
import {
  NDrawer, NDrawerContent, NButton, NSpace, NTag,
  NDataTable, NModal, NForm, NFormItem, NInput,
  NSelect, NSwitch, NPopconfirm, useMessage
} from 'naive-ui';
import {
  CreateOutline, TrashOutline, StarOutline
} from '@vicons/ionicons5';
import {
  fetchWebdavAccounts, createWebdavAccount,
  updateWebdavAccount, deleteWebdavAccount,
  setDefaultWebdavAccount
} from '@/service/api/webdav';

defineOptions({ name: 'AccountDrawer' });

/** Props: accountType determines which accounts to show, show controls drawer visibility */
const props = defineProps<{
  accountType: 'webdav' | 'alist';
  show: boolean;
}>();

const emit = defineEmits<{
  (e: 'account-change', accountId: string): void;
}>();

const message = useMessage();

// ── Local state ────────────────────────────────────────────────────────────────
const accounts = ref<Api.Webdav.WebdavAccount[]>([]);
const loading = ref(false);
const showModal = ref(false);
const editingAccount = ref<Api.Webdav.WebdavAccount | null>(null);
const submitting = ref(false);
const formData = ref({
  type: 'jianguoyun',
  name: '',
  url: '',
  username: '',
  password: '',
  isDefault: false
});

// ── Computed ───────────────────────────────────────────────────────────────────
const filteredAccounts = computed(() =>
  props.accountType === 'alist'
    ? accounts.value.filter(a => a.type === 'alist')
    : accounts.value.filter(a => a.type !== 'alist')
);

const drawerTitle = computed(() =>
  props.accountType === 'alist' ? 'Alist 账号管理' : 'WebDAV 账号管理'
);

// ── Type options (WebDAV only) ─────────────────────────────────────────────────
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

const formRules = {
  name: { required: true, message: '请输入账号名称', trigger: 'blur' },
  url: { required: true, message: '请输入地址', trigger: 'blur' },
  username: { required: true, message: '请输入用户名', trigger: 'blur' }
};

// ── Columns ────────────────────────────────────────────────────────────────────
const columns = computed(() => {
  const cols: any[] = [
    {
      title: '账号名称',
      key: 'name',
      width: 140
    }
  ];

  // Type column only for webdav accountType
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

// ── Data loading ────────────────────────────────────────────────────────────────
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

// ── Modal helpers ──────────────────────────────────────────────────────────────
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

// ── CRUD actions ───────────────────────────────────────────────────────────────
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
</script>

<template>
  <n-drawer v-model:show="show" :width="480" placement="right" @after-enter="loadAccounts">
    <n-drawer-content :title="drawerTitle" closable>
      <!-- header-extra is not in DrawerContent slot types but is accepted at runtime -->
      <!-- @ts-expect-error header-extra slot is supported by n-drawer-content at runtime -->
      <template #header-extra>
        <n-button type="primary" size="small" @click="openCreateModal">添加账号</n-button>
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

  <!-- Create / Edit modal -->
  <n-modal
    v-model:show="showModal"
    :title="editingAccount ? '编辑账号' : '添加账号'"
    preset="card"
    :style="{ '--n-color': '#ffffff' }"
    style="width: 520px;"
  >
    <n-form :model="formData" :rules="formRules" label-placement="left" label-width="100">
      <!-- Type selector: WebDAV only, hidden for Alist -->
      <n-form-item v-if="accountType === 'webdav'" label="服务类型" required>
        <n-select
          v-model:value="formData.type"
          :options="typeOptions"
          placeholder="请选择服务类型"
        />
      </n-form-item>

      <n-form-item label="账号名称" required>
        <n-input v-model:value="formData.name" placeholder="如：工作坚果云" />
      </n-form-item>

      <n-form-item label="服务地址" required>
        <n-input v-model:value="formData.url" placeholder="https://..." />
      </n-form-item>

      <n-form-item label="用户名" required>
        <n-input v-model:value="formData.username" placeholder="用户名" />
      </n-form-item>

      <n-form-item :label="accountType === 'alist' ? 'Alist API 密码' : '密码'" :required="!editingAccount">
        <n-input
          v-model:value="formData.password"
          type="password"
          :placeholder="editingAccount ? '留空不修改' : '密码'"
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
