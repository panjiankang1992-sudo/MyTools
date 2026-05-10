<script setup lang="ts">
import { reactive, ref, h, computed } from 'vue';
import { fetchCreateToken, fetchDeleteToken, fetchGetTokenList, fetchUpdateTokenStatus } from '@/service/api';
import { useLoading } from '@sa/hooks';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NForm, NFormItem, useMessage, NP, NAlert, useDialog } from 'naive-ui';
import { useClipboard } from '@vueuse/core';

defineOptions({ name: 'TokenManagement' });

const message = useMessage();
const dialog = useDialog();
const { loading, startLoading, endLoading } = useLoading();

// Token 列表数据
const data = reactive<Api.Token.TokenItem[]>([]);
const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
});

// 创建 Token 弹窗
const createModal = reactive({
  show: false,
  tokenName: '',
  creating: false,
  createdToken: ''
});

const columns = [
  { title: '序号', key: 'index', width: 70, render: (_: any, index: number) => (pagination.page - 1) * pagination.pageSize + index + 1 },
  { title: 'Token 名称', key: 'tokenName', width: 200, render: (row: Api.Token.TokenItem) => row.tokenName || '-' },
  { title: 'Token 前缀', key: 'tokenPrefix', width: 180 },
  { title: '状态', key: 'status', width: 80, render: (row: Api.Token.TokenItem) => h(NTag, { type: row.status === 'ACTIVE' ? 'success' : 'error', size: 'small' }, () => row.status === 'ACTIVE' ? '正常' : '禁用') },
  { title: '创建时间', key: 'createdTime', width: 180 },
  { title: '最后使用时间', key: 'lastUsedTime', width: 180, render: (row: Api.Token.TokenItem) => row.lastUsedTime || '-' },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    render: (row: Api.Token.TokenItem) => [
      h(NButton, { size: 'small', type: row.status === 'ACTIVE' ? 'warning' : 'primary', onClick: () => handleToggleStatus(row), style: { marginRight: '8px' } }, () => row.status === 'ACTIVE' ? '禁用' : '启用'),
      h(NButton, { size: 'small', type: 'error', onClick: () => handleDelete(row) }, () => '删除')
    ]
  }
];

async function loadData() {
  startLoading();
  try {
    const result = await fetchGetTokenList({ page: pagination.page, pageSize: pagination.pageSize });
    console.log('Token result:', result);
    console.log('Token result keys:', Object.keys(result));
    console.log('Token result.data:', result?.data);
    console.log('Token result.data?.list:', result?.data?.list);
    data.length = 0;
    // 处理多种可能的响应结构
    const list = result?.data?.list || result?.list || [];
    // Debug: log each item's status
    list.forEach((item: any, idx: number) => {
      console.log(`Token[${idx}] status:`, item.status, 'type:', typeof item.status);
    });
    pagination.total = result?.data?.total || result?.total || 0;
    data.push(...list);
  } finally {
    endLoading();
  }
}

async function handleToggleStatus(row: Api.Token.TokenItem) {
  const newStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
  await fetchUpdateTokenStatus(row.id, newStatus);
  message.success(newStatus === 'ACTIVE' ? '启用成功' : '禁用成功');
  loadData();
}

async function handleDelete(row: Api.Token.TokenItem) {
  dialog.warning({
    title: '确认删除',
    content: `确定删除 Token "${row.tokenName || row.tokenPrefix}" 吗？删除后将无法恢复！`,
    positiveText: '确定删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await fetchDeleteToken(row.id);
      message.success('删除成功');
      loadData();
    }
  });
}

function openCreateModal() {
  createModal.tokenName = '';
  createModal.createdToken = '';
  createModal.show = true;
}

async function handleCreateSubmit() {
  if (!createModal.tokenName.trim()) {
    message.warning('请输入 Token 名称');
    return;
  }
  createModal.creating = true;
  try {
    const result = await fetchCreateToken({ tokenName: createModal.tokenName.trim() });
    console.log('Create result:', result);
    // 处理可能的响应结构
    const tokenValue = result.tokenValue || (result as any).data?.tokenValue || '';
    createModal.createdToken = tokenValue;
    if (tokenValue) {
      message.success('Token 创建成功！');
    } else {
      message.error('Token 创建失败，请重试');
    }
    loadData();
  } finally {
    createModal.creating = false;
  }
}

async function handleCopyToken() {
  if (!createModal.createdToken) return;
  try {
    await navigator.clipboard.writeText(createModal.createdToken);
    message.success('Token 已复制到剪贴板');
  } catch {
    message.error('复制失败，请手动复制');
  }
}

function handleCloseModal() {
  createModal.show = false;
  createModal.createdToken = '';
  createModal.tokenName = '';
}

function handlePageChange(page: number) {
  pagination.page = page;
  loadData();
}

function handlePageSizeChange(pageSize: number) {
  pagination.pageSize = pageSize;
  pagination.page = 1;
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
            <NButton type="primary" @click="openCreateModal">新增 Token</NButton>
            <NButton @click="loadData">刷新</NButton>
          </NSpace>
          <NDataTable :columns="columns" :data="data" :loading="loading" :pagination="false" />
          <NSpace justify="end" style="margin-top: 12px">
            <NPagination
              v-model:page="pagination.page"
              :page-size="pagination.pageSize"
              :page-sizes="[10, 20, 50]"
              :total="pagination.total"
              show-size-picker
              @update:page="handlePageChange"
              @update:page-size="handlePageSizeChange"
            />
          </NSpace>
        </NSpace>
      </NCard>
    </NSpace>

    <!-- 新增 Token 弹窗 -->
    <NModal v-model:show="createModal.show" preset="card" :title="createModal.createdToken ? 'Token 创建成功' : '新增 Token'" style="width: 500px" @afterLeave="handleCloseModal">
      <NForm v-if="!createModal.createdToken" labelPlacement="left" labelWidth="100">
        <NFormItem label="Token 名称">
          <NInput v-model:value="createModal.tokenName" placeholder="请输入 Token 名称，如：我的开发密钥" />
        </NFormItem>
      </NForm>
      <div v-else>
        <NAlert type="success" title="Token 创建成功" :bordered="false" style="margin-bottom: 16px">
          请妥善保存以下 Token，关闭弹窗后将无法再次查看完整内容！
        </NAlert>
        <NInput :value="createModal.createdToken" type="textarea" :rows="3" readonly style="margin-bottom: 12px" />
        <NButton type="primary" block @click="handleCopyToken">复制 Token</NButton>
      </div>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="createModal.show = false">{{ createModal.createdToken ? '关闭' : '取消' }}</NButton>
          <NButton v-if="!createModal.createdToken" type="primary" :loading="createModal.creating" @click="handleCreateSubmit">创建</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
