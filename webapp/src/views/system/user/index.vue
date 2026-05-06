<script setup lang="ts">
import { reactive, ref, h } from 'vue';
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

const columns = [
  { title: '序号', key: 'index', width: 60, render: (_: any, index: number) => index + 1 },
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
  if (!createForm.username || !createForm.email || !createForm.password) {
    message.error('用户名、邮箱和密码不能为空');
    return;
  }
  await fetchCreateUser(createForm as any);
  showCreateModal.value = false;
  message.success('用户创建成功');
  // 重置表单
  createForm.username = '';
  createForm.password = '';
  createForm.email = '';
  createForm.phone = '';
  createForm.role = 'USER';
  createForm.status = 'ACTIVE';
  loadData();
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
      <NForm labelPlacement="left" labelWidth="80">
        <NFormItem label="用户名">
          <NInput v-model:value="createForm.username" placeholder="请输入用户名" />
        </NFormItem>
        <NFormItem label="密码">
          <NInput v-model:value="createForm.password" type="password" placeholder="请输入密码" />
        </NFormItem>
        <NFormItem label="邮箱">
          <NInput v-model:value="createForm.email" placeholder="请输入邮箱" />
        </NFormItem>
        <NFormItem label="手机号">
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