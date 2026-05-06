<script setup lang="ts">
import { reactive, ref, h, onMounted, watch } from 'vue';
import {
  fetchCreateTask, fetchDeleteTask, fetchGetTaskPage, fetchUpdateTaskStatus, fetchRefreshTask
} from '@/service/api/moments';
import { fetchGetNormalAccounts } from '@/service/api/wechat-account';
import { useLoading } from '@sa/hooks';
import { NButton, NTag, NSpace, NModal, NCard, NInput, NSelect, NDataTable, NCheckbox, useMessage } from 'naive-ui';
import TiptapEditor from './components/TiptapEditor.vue';
import MediaUploader from './components/MediaUploader.vue';

defineOptions({ name: 'MomentsTaskManagement' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

// 账号列表
const accountOptions = ref<{ label: string; value: number }[]>([]);
const statusOptions = [
  { label: '待执行', value: 1 },
  { label: '已执行', value: 3 },
  { label: '已过期', value: -1 }
];

const showCreateModal = ref(false);
const showDetailModal = ref(false);

// 新增任务表单
const createForm = reactive({
  accountId: null as number | null,
  content: '',
  priority: 2,
  scheduledTime: null as string | null,
  mediaUrls: [] as string[]
});

// 任务详情
const currentTask = ref<any>(null);

const columns = [
  { title: '序号', key: 'index', width: 60, render: (_: any, index: number) => index + 1 },
  { title: '账号', key: 'accountNickname', width: 100 },
  { title: '内容', key: 'contentPreview', ellipsis: true, render: (row: any) => h('div', { title: row.contentPreview }, row.contentPreview || '-') },
  { title: '缩略图', key: 'firstMediaUrl', width: 90, render: (row: any) => {
    if (row.firstMediaUrl) {
      return h('img', {
        src: row.firstMediaUrl,
        style: 'width:80px;height:80px;object-fit:cover;border-radius:4px;',
        onError: (e: any) => { e.target.style.display = 'none'; }
      });
    }
    return h('div', { style: 'width:80px;height:80px;background:#f5f5f5;border-radius:4px;display:flex;align-items:center;justify-content:center;color:#999;font-size:12px;' }, '无');
  }},
  { title: '状态', key: 'status', width: 90, render: (row: any) => {
    // 已过期优先显示：scheduledTime < now && status = 1
    const isExpired = row.isExpired;
    const statusText = isExpired ? '已过期' : (row.status === 1 ? '待执行' : row.status === 3 ? '已执行' : '未知');
    const type = isExpired ? 'warning' : row.status === 3 ? 'success' : row.status === 1 ? 'info' : 'default';
    return h(NTag, { type, size: 'small' }, () => statusText);
  }},
  { title: '定时发布时间', key: 'scheduledTime', width: 150, render: (row: any) => row.scheduledTime ? row.scheduledTime.substring(0, 16) : '立即发布' },
  { title: '创建时间', key: 'createTime', width: 150 },
  { title: '操作', key: 'actions', width: 200, render: (row: any) => {
    return h(NSpace, { size: 'small' }, () => [
      h(NButton, { size: 'small', type: 'warning', onClick: () => handleRestart(row) }, () => '重启'),
      h(NButton, { size: 'small', onClick: () => openDetailModal(row) }, () => '详情'),
      h(NButton, { size: 'small', type: 'error', onClick: () => handleDelete(row) }, () => '删除')
    ]);
  }}
];

const data = reactive<any[]>([]);
const pagination = reactive({ page: 1, pageSize: 20, pageSizes: [20, 50, 100, 200], total: 0, showSizePicker: true, showTotal: true });
const searchParams = reactive({ accountId: null as number | null, status: null as number | null, keyword: '', includeExpired: false });

// 加载账号列表
async function loadAccounts() {
  try {
    const result = await fetchGetNormalAccounts();
    const accounts = result.data || [];
    accountOptions.value = accounts.map((acc: any) => ({ label: acc.nickname, value: acc.id }));
  } catch (error) {
    console.error('Failed to load accounts:', error);
  }
}

// 加载任务列表
async function loadData() {
  startLoading();
  try {
    // 处理状态筛选逻辑：
    // - status=-1 (已过期): 不传status，只传includeExpired=true
    // - status=1 (待执行): 传status=1，includeExpired由复选框控制
    // - status=3 (已执行): 传status=3，includeExpired=false
    // - status=null (无筛选): 不传status，includeExpired由复选框控制
    let status: number | undefined;
    let includeExpired: boolean;

    if (searchParams.status === -1) {
      // 已过期筛选：不传status，只传includeExpired=true
      status = undefined;
      includeExpired = true;
    } else {
      status = searchParams.status || undefined;
      includeExpired = searchParams.includeExpired;
    }

    const result = await fetchGetTaskPage({
      page: pagination.page,
      pageSize: pagination.pageSize,
      accountId: searchParams.accountId || undefined,
      status: status,
      includeExpired: includeExpired,
      keyword: searchParams.keyword || undefined
    });
    data.length = 0;
    data.push(...(result.data?.list || []));
    pagination.total = result.data?.total || 0;
  } finally {
    endLoading();
  }
}

async function handleRestart(row: any) {
  try {
    await fetchRefreshTask(row.id);
    message.success('重启成功');
    loadData();
  } catch (error: any) {
    message.error(error.message || '重启失败');
  }
}

async function handleDelete(row: any) {
  if (confirm(`确定删除该任务?`)) {
    await fetchDeleteTask(row.id);
    message.success('删除成功');
    loadData();
  }
}

function openDetailModal(row: any) {
  currentTask.value = row;
  showDetailModal.value = true;
}

async function handleCreateSubmit() {
  if (!createForm.accountId) {
    message.error('请选择微信账号');
    return;
  }
  if (!createForm.content) {
    message.error('请输入任务内容');
    return;
  }
  await fetchCreateTask({
    accountId: createForm.accountId,
    content: createForm.content,
    priority: createForm.priority,
    scheduledTime: createForm.scheduledTime || undefined,
    mediaUrls: createForm.mediaUrls.length > 0 ? createForm.mediaUrls : undefined
  });
  showCreateModal.value = false;
  message.success('创建成功');
  // 重置表单
  createForm.accountId = null;
  createForm.content = '';
  createForm.priority = 2;
  createForm.scheduledTime = null;
  createForm.mediaUrls = [];
  loadData();
}

onMounted(() => {
  loadAccounts();
  loadData();
});

// 筛选变化自动刷新
watch(() => searchParams, () => {
  pagination.page = 1;
  loadData();
}, { deep: true });
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NCard :bordered="false">
        <NSpace vertical :size="12">
          <NSpace>
            <NSelect v-model:value="searchParams.accountId" placeholder="选择账号" clearable style="width: 150px" :options="accountOptions" />
            <NSelect v-model:value="searchParams.status" placeholder="状态" clearable style="width: 120px" :options="statusOptions" />
            <NInput v-model:value="searchParams.keyword" placeholder="搜索内容" clearable style="width: 200px" @keyup.enter="loadData" />
            <NCheckbox v-model:checked="searchParams.includeExpired" style="margin-left: 8px;">显示已过期</NCheckbox>
            <NButton @click="loadData">刷新</NButton>
            <NButton type="primary" @click="showCreateModal = true">新增任务</NButton>
          </NSpace>
          <NDataTable :columns="columns" :data="data" :loading="loading" :pagination="pagination" :row-key="(row: any) => row.id" />
        </NSpace>
      </NCard>
    </NSpace>

    <!-- 新增任务弹窗 -->
    <NModal v-model:show="showCreateModal" preset="card" title="新增任务" style="width: 700px">
      <NSpace vertical :size="12">
        <NSpace align="center">
          <span style="width: 80px;">微信账号:</span>
          <NSelect v-model:value="createForm.accountId" style="width: 200px" :options="accountOptions" placeholder="请选择账号" />
        </NSpace>
        <NSpace align="center">
          <span style="width: 80px;">优先级:</span>
          <NSelect v-model:value="createForm.priority" style="width: 120px" :options="[
            { label: '低', value: 1 },
            { label: '中', value: 2 },
            { label: '高', value: 3 }
          ]" />
        </NSpace>
        <NSpace vertical :size="4">
          <span>任务内容:</span>
          <TiptapEditor v-model="createForm.content" />
        </NSpace>
        <NSpace vertical :size="4">
          <span>附件:</span>
          <MediaUploader v-model="createForm.mediaUrls" />
        </NSpace>
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showCreateModal = false">取消</NButton>
          <NButton type="primary" @click="handleCreateSubmit">确定</NButton>
        </NSpace>
      </template>
    </NModal>

    <!-- 任务详情弹窗 -->
    <NModal v-model:show="showDetailModal" preset="card" title="任务详情" style="width: 600px">
      <NSpace vertical :size="12" v-if="currentTask">
        <NSpace>
          <span style="width: 80px;">账号:</span>
          <span>{{ currentTask.accountNickname }}</span>
        </NSpace>
        <NSpace>
          <span style="width: 80px;">状态:</span>
          <NTag type="success" size="small">{{ currentTask.isExpired ? '已过期' : (currentTask.status === 1 ? '待执行' : currentTask.status === 3 ? '已执行' : '未知') }}</NTag>
        </NSpace>
        <NSpace vertical :size="4">
          <span style="width: 80px;">内容:</span>
          <div v-html="currentTask.content" class="task-content"></div>
        </NSpace>
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showDetailModal = false">关闭</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.task-content {
  padding: 12px;
  background: #f5f5f5;
  border-radius: 4px;
  max-height: 300px;
  overflow-y: auto;
}
</style>