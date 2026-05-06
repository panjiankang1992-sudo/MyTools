<script setup lang="ts">
import { reactive, ref, h } from 'vue';
import { fetchCreateAccount, fetchDeleteAccount, fetchGetAccountList, fetchUpdateAccount, fetchUpdateAccountStatus, fetchGetNormalAccounts, fetchRefreshAccountTasks } from '@/service/api/wechat-account';
import { useLoading } from '@sa/hooks';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NForm, NFormItem, useMessage } from 'naive-ui';

defineOptions({ name: 'WechatAccountManagement' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

const showCreateModal = ref(false);
const showEditModal = ref(false);
const currentAccountId = ref<number | null>(null);

// 新增账号表单
const createForm = reactive({
  wechatId: '',
  nickname: '',
  remark: ''
});

// 编辑账号表单
const editForm = reactive({
  id: 0,
  wechatId: '',
  nickname: '',
  remark: ''
});

const columns = [
  { title: '序号', key: 'index', width: 60, render: (_: any, index: number) => index + 1 },
  { title: '微信ID', key: 'wechatId', width: 150 },
  { title: '昵称', key: 'nickname', width: 120 },
  { title: '备注', key: 'remark', width: 150 },
  { title: '状态', key: 'status', width: 70, render: (row: any) => h(NTag, { type: row.status === 1 ? 'success' : 'error', size: 'small' }, () => row.status === 1 ? '正常' : '禁用') },
  { title: '创建时间', key: 'createTime', width: 150 },
  { title: '操作', key: 'actions', width: 240, render: (row: any) => {
    return h(NSpace, { size: 'small' }, () => [
      h(NButton, { size: 'small', type: row.status === 1 ? 'warning' : 'primary', onClick: () => handleToggleStatus(row) }, () => row.status === 1 ? '禁用' : '启用'),
      h(NButton, { size: 'small', type: 'info', onClick: () => handleRefreshTasks(row) }, () => '重启任务'),
      h(NButton, { size: 'small', onClick: () => openEditModal(row) }, () => '编辑'),
      h(NButton, { size: 'small', type: 'error', onClick: () => handleDelete(row) }, () => '删除')
    ]);
  }}
];

const data = reactive<any[]>([]);
const pagination = reactive({ page: 1, pageSize: 10, total: 0 });

async function loadData() {
  startLoading();
  try {
    const result = await fetchGetAccountList({
      page: pagination.page,
      pageSize: pagination.pageSize
    });
    data.length = 0;
    data.push(...(result.data?.list || []));
    pagination.total = result.data?.total || 0;
  } finally {
    endLoading();
  }
}

async function handleToggleStatus(row: any) {
  const newStatus = row.status === 1 ? 2 : 1;
  await fetchUpdateAccountStatus(row.id, newStatus);
  message.success('操作成功');
  loadData();
}

async function handleRefreshTasks(row: any) {
  try {
    const result = await fetchRefreshAccountTasks(row.id);
    message.success(`重启成功：成功${result.data?.successCount || 0}个，失败${result.data?.failCount || 0}个`);
    loadData();
  } catch (error: any) {
    message.error(error.message || '重启失败');
  }
}

async function handleDelete(row: any) {
  if (confirm(`确定删除账号 ${row.wechatId}?`)) {
    await fetchDeleteAccount(row.id);
    message.success('删除成功');
    loadData();
  }
}

function openEditModal(row: any) {
  currentAccountId.value = row.id;
  editForm.id = row.id;
  editForm.wechatId = row.wechatId;
  editForm.nickname = row.nickname;
  editForm.remark = row.remark;
  showEditModal.value = true;
}

async function handleEditSubmit() {
  if (!editForm.id || !editForm.nickname) return;
  await fetchUpdateAccount({
    id: editForm.id,
    nickname: editForm.nickname,
    remark: editForm.remark
  });
  showEditModal.value = false;
  message.success('更新成功');
  loadData();
}

async function handleCreateSubmit() {
  if (!createForm.wechatId || !createForm.nickname) {
    message.error('微信ID和昵称不能为空');
    return;
  }
  await fetchCreateAccount({
    wechatId: createForm.wechatId,
    nickname: createForm.nickname,
    remark: createForm.remark
  });
  showCreateModal.value = false;
  message.success('创建成功');
  // 重置表单
  createForm.wechatId = '';
  createForm.nickname = '';
  createForm.remark = '';
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
            <NButton type="primary" @click="showCreateModal = true">新增账号</NButton>
            <NButton @click="loadData">刷新</NButton>
          </NSpace>
          <NDataTable :columns="columns" :data="data" :loading="loading" :pagination="false" :row-key="(row: any) => row.id" />
        </NSpace>
      </NCard>
    </NSpace>

    <!-- 新增账号弹窗 -->
    <NModal v-model:show="showCreateModal" preset="card" title="新增账号" style="width: 450px">
      <NForm labelPlacement="left" labelWidth="80">
        <NFormItem label="微信ID">
          <NInput v-model:value="createForm.wechatId" placeholder="请输入微信ID/微信号" />
        </NFormItem>
        <NFormItem label="昵称">
          <NInput v-model:value="createForm.nickname" placeholder="请输入昵称" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="createForm.remark" placeholder="请输入备注" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showCreateModal = false">取消</NButton>
          <NButton type="primary" @click="handleCreateSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 编辑账号弹窗 -->
    <NModal v-model:show="showEditModal" preset="card" title="编辑账号" style="width: 450px">
      <NForm labelPlacement="left" labelWidth="80">
        <NFormItem label="微信ID">
          <NInput v-model:value="editForm.wechatId" disabled />
        </NFormItem>
        <NFormItem label="昵称">
          <NInput v-model:value="editForm.nickname" placeholder="请输入昵称" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="editForm.remark" placeholder="请输入备注" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showEditModal = false">取消</NButton>
          <NButton type="primary" @click="handleEditSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>