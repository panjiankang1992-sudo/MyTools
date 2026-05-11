<script setup lang="ts">
import { reactive, h } from 'vue';
import { fetchCreateToken, fetchDeleteToken, fetchGetTokenList, fetchUpdateTokenStatus, fetchValidateToken } from '@/service/api';
import { useLoading } from '@sa/hooks';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NForm, NFormItem, useMessage, NAlert, useDialog, NResult } from 'naive-ui';

defineOptions({ name: 'TokenManagement' });

const message = useMessage();
const dialog = useDialog();
const { loading, startLoading, endLoading } = useLoading();

// Token 列表数据

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

// 校验 Token 弹窗
const validateModal = reactive({
  show: false,
  tokenValue: '',
  validating: false,
  result: null as { valid: boolean; message: string } | null
});

const columns = [
  { title: '序号', key: 'index', width: 70, render: (_: any, index: number) => (pagination.page - 1) * pagination.pageSize + index + 1 },
  { title: 'Token 名称', key: 'tokenName', width: 200, render: (row: Api.Token.TokenItem) => row.tokenName || '-' },
  { title: 'Token', key: 'tokenPrefix', width: 220, render: (row: Api.Token.TokenItem) => `${row.tokenPrefix || '****'}****` },
  { title: '状态', key: 'status', width: 80, render: (row: Api.Token.TokenItem) => h(NTag, { type: row.status === 'ACTIVE' ? 'success' : 'warning', size: 'small' }, () => row.status === 'ACTIVE' ? '正常' : '禁用') },
  { title: '创建时间', key: 'createdTime', width: 180 },
  { title: '最后使用时间', key: 'lastUsedTime', width: 180, render: (row: Api.Token.TokenItem) => row.lastUsedTime || '-' },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    render: (row: Api.Token.TokenItem) => [
      h(NButton, { size: 'small', type: row.status === 'ACTIVE' ? 'warning' : 'success', onClick: () => handleToggleStatus(row), style: { marginRight: '8px' } }, () => row.status === 'ACTIVE' ? '禁用' : '启用'),
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
  const newStatus = row.status === 'ACTIVE' ? 'INVALID' : 'ACTIVE';
  await fetchUpdateTokenStatus(row.id, newStatus);
  message.success(newStatus === 'ACTIVE' ? '启用成功' : '禁用成功');
  loadData();
}

async function handleDelete(row: Api.Token.TokenItem) {
  const displayName = row.tokenName || `Token ${row.tokenPrefix}`;
  dialog.error({
    title: '确认删除',
    content: `确定删除 "${displayName}" 吗？删除后将无法恢复！`,
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

function openValidateModal() {
  validateModal.tokenValue = '';
  validateModal.result = null;
  validateModal.show = true;
}

async function handleValidateToken() {
  if (!validateModal.tokenValue.trim()) {
    message.warning('请输入 Token');
    return;
  }
  validateModal.validating = true;
  try {
    const result = await fetchValidateToken(validateModal.tokenValue.trim());
    console.log('[Token校验] raw result:', result);
    console.log('[Token校验] result type:', typeof result);
    console.log('[Token校验] result?.valid:', result?.valid);
    console.log('[Token校验] result?.message:', result?.message);
    // result 为 null 表示请求失败（已在 service 层处理错误提示）
    if (result === null) {
      validateModal.result = { valid: false, message: 'Token 验证请求失败' };
    } else if (result?.valid === true) {
      validateModal.result = { valid: true, message: 'Token 有效' };
    } else if (result?.valid === false) {
      validateModal.result = { valid: false, message: result?.message || 'Token 无效或已过期' };
    } else {
      // 调试：打印更多详情
      console.error('[Token校验] unexpected result structure:', JSON.stringify(result));
      validateModal.result = { valid: false, message: `Token 验证失败(${typeof result})` };
    }
  } catch (e) {
    console.error('[Token校验] exception:', e);
    validateModal.result = { valid: false, message: 'Token 验证失败' };
  } finally {
    validateModal.validating = false;
  }
}

function handleCloseValidateModal() {
  validateModal.show = false;
  validateModal.tokenValue = '';
  validateModal.result = null;
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

  const token = createModal.createdToken;

  // 方案1：navigator.clipboard.writeText
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    try {
      await navigator.clipboard.writeText(token);
      message.success('Token 已复制到剪贴板');
      return;
    } catch {
      // 失败，继续尝试其他方案
    }
  }

  // 方案2：使用 Selection API（浏览器原生，不依赖 execCommand）
  try {
    const span = document.createElement('span');
    span.textContent = token;
    span.style.cssText = 'position:fixed;left:0;top:0;opacity:0;pointer-events:none;white-space:pre;';
    span.id = '__token_copy_span__';
    span.setAttribute('data-value', token);
    document.body.appendChild(span);

    const range = document.createRange();
    range.selectNodeContents(span);
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(range);

    const success = document.execCommand('copy');
    sel?.removeAllRanges();
    document.body.removeChild(span);

    if (success) {
      message.success('Token 已复制到剪贴板');
      return;
    }
  } catch {
    // 失败
  }

  // 方案3：最后的备选方案
  try {
    const ta = document.createElement('textarea');
    ta.value = token;
    ta.style.cssText = 'position:fixed;left:-9999px;top:-9999px;width:1px;height:1px;opacity:0;';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    if (ok) {
      message.success('Token 已复制到剪贴板');
    } else {
      message.warning('复制失败，请手动选中文本后 Ctrl+C 复制');
    }
  } catch {
    message.warning('复制失败，请手动选中文本后 Ctrl+C 复制');
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
            <NButton @click="openValidateModal">校验 Token</NButton>
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
        <NSpace justify="center">
          <NButton @click="createModal.show = false">{{ createModal.createdToken ? '关闭' : '取消' }}</NButton>
          <NButton v-if="!createModal.createdToken" type="primary" :loading="createModal.creating" @click="handleCreateSubmit">创建</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 校验 Token 弹窗 -->
    <NModal v-model:show="validateModal.show" preset="card" title="校验 Token" style="width: 500px" @afterLeave="handleCloseValidateModal">
      <NForm labelPlacement="left" labelWidth="80">
        <NFormItem label="Token">
          <NInput
            v-model:value="validateModal.tokenValue"
            placeholder="请输入完整的 Token"
            @keyup.enter="handleValidateToken"
          />
        </NFormItem>
      </NForm>
      <div v-if="validateModal.result" style="text-align: center; margin-top: 16px;">
        <NResult
          :status="validateModal.result.valid ? 'success' : 'error'"
          :title="validateModal.result.valid ? 'Token 有效' : 'Token 无效'"
          :description="validateModal.result.message"
        />
      </div>
      <template #footer>
        <NSpace justify="center">
          <NButton @click="handleCloseValidateModal">关闭</NButton>
          <NButton type="primary" :loading="validateModal.validating" @click="handleValidateToken">校验</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
