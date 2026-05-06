<script setup lang="ts">
import { reactive, ref, h } from 'vue';
import { fetchCreateRole, fetchDeleteRole, fetchGetRoleList, fetchUpdateRole } from '@/service/api';
import { useLoading } from '@sa/hooks';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NForm, NFormItem, useMessage } from 'naive-ui';

defineOptions({ name: 'RoleManagement' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

const columns = [
  { title: '序号', key: 'index', width: 70 },
  { title: '角色名称', key: 'roleName', width: 150 },
  { title: '角色代码', key: 'roleCode', width: 120 },
  { title: '描述', key: 'description', width: 200 },
  { title: '状态', key: 'status', width: 80, render: (row: Api.Role.RoleItem) => h(NTag, { type: row.status === 'ACTIVE' ? 'success' : 'error', size: 'small' }, () => row.status === 'ACTIVE' ? '启用' : '禁用') },
  { title: '操作', key: 'actions', width: 200, render: (row: Api.Role.RoleItem) => {
    return h(NSpace, { size: 'small' }, () => [
      h(NButton, { size: 'small', onClick: () => openEditModal(row) }, () => '编辑'),
      h(NButton, { size: 'small', type: row.status === 'ACTIVE' ? 'warning' : 'primary', onClick: () => handleToggleStatus(row) }, () => row.status === 'ACTIVE' ? '禁用' : '启用'),
      h(NButton, { size: 'small', type: 'error', onClick: () => handleDelete(row) }, () => '删除')
    ]);
  }}
];

const data = reactive<Api.Role.RoleItem[]>([]);

// Edit modal
const editModal = reactive({
  show: false,
  id: null as number | null,
  roleName: '',
  roleCode: '',
  description: '',
  status: 'ACTIVE'
});

async function loadData() {
  startLoading();
  try {
    const result = await fetchGetRoleList();
    console.log('Role result:', result);
    // Handle both possible structures - result could be array directly or {data: [...]}
    const list = Array.isArray(result) ? result : (result?.data || []);
    data.length = 0;
    data.push(...list);
  } finally {
    endLoading();
  }
}

async function handleToggleStatus(row: Api.Role.RoleItem) {
  const newStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
  await fetchUpdateRole(row.id, { status: newStatus });
  loadData();
}

async function handleDelete(row: Api.Role.RoleItem) {
  if (confirm(`确定删除角色 ${row.roleName}?`)) {
    await fetchDeleteRole(row.id);
    message.success('删除成功');
    loadData();
  }
}

function openEditModal(row?: Api.Role.RoleItem) {
  if (row) {
    editModal.id = row.id;
    editModal.roleName = row.roleName;
    editModal.roleCode = row.roleCode;
    editModal.description = row.description || '';
    editModal.status = row.status;
  } else {
    editModal.id = null;
    editModal.roleName = '';
    editModal.roleCode = '';
    editModal.description = '';
    editModal.status = 'ACTIVE';
  }
  editModal.show = true;
}

async function handleEditSubmit() {
  if (!editModal.roleName || !editModal.roleCode) return;
  if (editModal.id) {
    await fetchUpdateRole(editModal.id, {
      roleName: editModal.roleName,
      roleCode: editModal.roleCode,
      description: editModal.description,
      status: editModal.status
    });
  } else {
    await fetchCreateRole({
      roleName: editModal.roleName,
      roleCode: editModal.roleCode,
      description: editModal.description
    });
  }
  editModal.show = false;
  loadData();
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
            <NButton type="primary" @click="openEditModal()">新增角色</NButton>
            <NButton @click="loadData">刷新</NButton>
          </NSpace>
          <NDataTable :columns="columns" :data="data" :loading="loading" :pagination="false" />
        </NSpace>
      </NCard>
    </NSpace>

    <!-- 编辑/新增角色弹窗 -->
    <NModal v-model:show="editModal.show" preset="card" :title="editModal.id ? '编辑角色' : '新增角色'" style="width: 450px">
      <NForm labelPlacement="left" labelWidth="80">
        <NFormItem label="角色名称">
          <NInput v-model:value="editModal.roleName" placeholder="请输入角色名称" />
        </NFormItem>
        <NFormItem label="角色代码">
          <NInput v-model:value="editModal.roleCode" placeholder="请输入角色代码" :disabled="!!editModal.id" />
        </NFormItem>
        <NFormItem label="描述">
          <NInput v-model:value="editModal.description" type="textarea" placeholder="请输入描述" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="editModal.show = false">取消</NButton>
          <NButton type="primary" @click="handleEditSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>