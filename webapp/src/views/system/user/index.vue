<script setup lang="ts">
import { reactive, ref, h, onMounted, onUnmounted } from 'vue';
import { fetchCreateUser, fetchDeleteUser, fetchGetUserList, fetchUpdateUser, fetchUpdateUserStatus, fetchUpdateUserRole } from '@/service/api';
import { useLoading } from '@sa/hooks';
import { $t } from '@/locales';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NSelect, NForm, NFormItem, useMessage } from 'naive-ui';

defineOptions({ name: 'UserManagement' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

const showPasswordModal = ref(false);
const showRoleModal = ref(false);
const showCreateModal = ref(false);
const currentUserId = ref<number | null>(null);
const currentUserRole = ref('');
const newPassword = ref('');

// 新增用户表单
const createForm = reactive({
  username: '',
  password: '',
  email: '',
  phone: '',
  role: 'USER',
  status: 'ACTIVE'
});

// 表单校验规则
const rules = {
  username: [
    { required: true, message: '用户名不能为空', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '用户名4-20位字母数字下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '密码不能为空', trigger: 'blur' },
    { pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/,
      message: '密码必须包含大小写字母和数字', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '邮箱不能为空', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
};

// 表单引用
const formRef = ref<any>(null);

// 字段错误
const fieldErrors = reactive<Record<string, string>>({});

// 监听字段错误事件
function handleFieldErrors(event: CustomEvent) {
  const errors = event.detail as Record<string, string>;
  Object.assign(fieldErrors, errors);
}

// 组件挂载时监听字段错误事件
onMounted(() => {
  window.addEventListener('fieldErrors', handleFieldErrors as EventListener);
});

// 组件卸载时移除监听
onUnmounted(() => {
  window.removeEventListener('fieldErrors', handleFieldErrors as EventListener);
});

// 重置表单
function resetForm() {
  createForm.username = '';
  createForm.password = '';
  createForm.email = '';
  createForm.phone = '';
  createForm.role = 'USER';
  createForm.status = 'ACTIVE';
  Object.keys(fieldErrors).forEach(key => delete fieldErrors[key]);
}

const columns = [
  { title: '序号', key: 'index', width: 60, render: (_: any, index: number) => index + 1 },
  {
    title: '头像',
    key: 'avatar',
    width: 70,
    render: (row: Api.User.UserItem) => {
      if (row.avatar) {
        return h('img', {
          src: row.avatar,
          style: 'width: 40px; height: 40px; border-radius: 50%; object-fit: cover;',
          onError: (e: Event) => {
            const img = e.target as HTMLImageElement;
            img.style.display = 'none';
          }
        });
      }
      return h('div', {
        style: 'width: 40px; height: 40px; border-radius: 50%; background: #646cff; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 16px; font-weight: bold;'
      }, row.nickname?.charAt(0) || row.username?.charAt(0) || '?');
    }
  },
  {
    title: '昵称',
    key: 'nickname',
    width: 100,
    render: (row: Api.User.UserItem) => row.nickname || '-'
  },
  { title: '用户名', key: 'username', width: 120 },
  { title: '邮箱', key: 'email', width: 180 },
  { title: '手机', key: 'phone', width: 130 },
  { title: '角色', key: 'role', width: 80, render: (row: Api.User.UserItem) => row.role === 'ADMIN' ? '管理员' : '普通用户' },
  { title: '状态', key: 'status', width: 70, render: (row: Api.User.UserItem) => h(NTag, { type: row.status === 'ACTIVE' ? 'success' : 'error', size: 'small' }, () => row.status === 'ACTIVE' ? '启用' : '禁用') },
  { title: '注册时间', key: 'registerTime', width: 150 },
  { title: '操作', key: 'actions', width: 260, render: (row: Api.User.UserItem) => {
    return h(NSpace, { size: 'small' }, () => [
      h(NButton, { size: 'small', type: row.status === 'ACTIVE' ? 'warning' : 'primary', onClick: () => handleToggleStatus(row) }, () => row.status === 'ACTIVE' ? '禁用' : '启用'),
      h(NButton, { size: 'small', onClick: () => openRoleModal(row) }, () => '改角色'),
      h(NButton, { size: 'small', onClick: () => openPasswordModal(row) }, () => '改密码'),
      h(NButton, { size: 'small', type: 'error', onClick: () => handleDelete(row) }, () => '删除')
    ]);
  }}
];

const data = reactive<Api.User.UserItem[]>([]);
const pagination = reactive({ page: 1, pageSize: 10, total: 0 });
const searchParams = reactive({ keyword: '', status: '' });

async function loadData() {
  startLoading();
  try {
    const result = await fetchGetUserList({
      page: pagination.page,
      pageSize: pagination.pageSize,
      keyword: searchParams.keyword || undefined,
      status: searchParams.status || undefined
    });
    const list = result?.list || result?.data?.list || [];
    data.length = 0;
    data.push(...list);
    pagination.total = result?.total || result?.data?.total || 0;
  } finally {
    endLoading();
  }
}

function handleSearch() {
  pagination.page = 1;
  loadData();
}

async function handleToggleStatus(row: Api.User.UserItem) {
  const newStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
  await fetchUpdateUserStatus(row.id, newStatus);
  message.success('操作成功');
  loadData();
}

async function handleDelete(row: Api.User.UserItem) {
  if (confirm(`确定删除用户 ${row.username}?`)) {
    await fetchDeleteUser(row.id);
    message.success('删除成功');
    loadData();
  }
}

function openPasswordModal(row: Api.User.UserItem) {
  currentUserId.value = row.id;
  newPassword.value = '';
  showPasswordModal.value = true;
}

async function handlePasswordSubmit() {
  if (!currentUserId.value || !newPassword.value) return;
  await fetchUpdateUser(currentUserId.value, { password: newPassword.value } as any);
  showPasswordModal.value = false;
  message.success('密码修改成功');
}

function openRoleModal(row: Api.User.UserItem) {
  currentUserId.value = row.id;
  currentUserRole.value = row.role;
  showRoleModal.value = true;
}

async function handleRoleSubmit() {
  if (!currentUserId.value || !currentUserRole.value) return;
  await fetchUpdateUser(currentUserId.value, { role: currentUserRole.value } as any);
  showRoleModal.value = false;
  message.success('角色修改成功');
  loadData();
}

async function handleCreateUser() {
  try {
    await formRef.value?.validate();
    await fetchCreateUser(createForm as any);
    showCreateModal.value = false;
    message.success('用户创建成功');
    resetForm();
    loadData();
  } catch (errors) {
    // 表单校验失败，不做处理（NaiveUI自动显示错误）
  }
}

function formatDate(date: string) {
  if (!date) return '-';
  return date.substring(0, 16);
}

// 初始加载
loadData();
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NCard :bordered="false">
        <NSpace vertical :size="12">
          <NSpace>
            <NInput v-model:value="searchParams.keyword" placeholder="搜索用户名/邮箱" clearable style="width: 200px" />
            <NSelect v-model:value="searchParams.status" placeholder="状态" clearable style="width: 120px" :options="[
              { label: '启用', value: 'ACTIVE' },
              { label: '禁用', value: 'DISABLED' }
            ]" />
            <NButton type="primary" @click="handleSearch">搜索</NButton>
            <NButton @click="loadData">刷新</NButton>
            <NButton type="primary" @click="showCreateModal = true">新增用户</NButton>
          </NSpace>
          <NDataTable :columns="columns" :data="data" :loading="loading" :pagination="false" :row-key="(row: any) => row.id" />
        </NSpace>
      </NCard>
    </NSpace>

    <!-- 修改密码弹窗 -->
    <NModal v-model:show="showPasswordModal" preset="card" title="修改密码" style="width: 400px">
      <NForm>
        <NFormItem label="新密码">
          <NInput v-model:value="newPassword" type="password" placeholder="请输入新密码" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showPasswordModal = false">取消</NButton>
          <NButton type="primary" @click="handlePasswordSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 修改角色弹窗 -->
    <NModal v-model:show="showRoleModal" preset="card" title="修改角色" style="width: 400px">
      <NForm>
        <NFormItem label="角色">
          <NSelect v-model:value="currentUserRole" :options="[
            { label: '普通用户', value: 'USER' },
            { label: '管理员', value: 'ADMIN' }
          ]" style="width: 200px" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showRoleModal = false">取消</NButton>
          <NButton type="primary" @click="handleRoleSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 新增用户弹窗 -->
    <NModal v-model:show="showCreateModal" preset="card" title="新增用户" style="width: 450px">
      <NForm ref="formRef" :model="createForm" :rules="rules" labelPlacement="left" labelWidth="80">
        <NFormItem label="用户名" path="username">
          <NInput v-model:value="createForm.username" placeholder="请输入用户名" />
        </NFormItem>
        <NFormItem label="密码" path="password">
          <NInput v-model:value="createForm.password" type="password" placeholder="请输入密码" />
        </NFormItem>
        <NFormItem label="邮箱" path="email">
          <NInput v-model:value="createForm.email" placeholder="请输入邮箱" />
        </NFormItem>
        <NFormItem label="手机号" path="phone">
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
  </div>
</template>

<style scoped></style>