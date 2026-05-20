<script setup lang="ts">
import { reactive, h, computed, type VNode } from 'vue';
import {
  fetchGetAppList, fetchDeleteApp, fetchGetAppVersions,
  getFileDownloadUrl
} from '@/service/api/appmarket';
import { useAuthStore } from '@/store/modules/auth';
import { useLoading } from '@sa/hooks';
import {
  NButton, NTag, NSpace, NSwitch, NInput, NSelect,
  NDataTable, NPagination, NPopconfirm, useMessage, NImage
} from 'naive-ui';
import AppMarketDrawer from './components/AppMarketDrawer.vue';

defineOptions({ name: 'AppMarket' });

const message = useMessage();
const authStore = useAuthStore();
const { loading, startLoading, endLoading } = useLoading();

const isAdmin = computed(() => authStore.userInfo.role === 'ADMIN');
const currentUserId = computed(() => authStore.userInfo.id);

// 搜索条件
const searchForm = reactive({
  type: null as string | null,
  name: '',
  includeHistory: false
});

// 分页
const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
});

// 数据
const data: Api.AppMarket.AppItem[] = reactive([]);
// 展开的行（历史版本）
const expandedRows = reactive<Record<string, Api.AppMarket.AppVersion[]>>({});

// 侧滑
const drawer = reactive({
  show: false,
  mode: 'detail' as 'detail' | 'publish',
  appId: null as string | null
});

function getTypeTagType(type: string): 'default' | 'success' | 'info' | 'warning' | 'error' {
  const map: Record<string, 'default' | 'success' | 'info' | 'warning' | 'error'> = {
    app: 'success', cli: 'info', mcp: 'warning', skill: 'error'
  };
  return map[type] || 'default';
}

function getTypeLabel(type: string) {
  const map: Record<string, string> = { app: 'App', cli: 'CLI', mcp: 'MCP', skill: 'Skill' };
  return map[type] || type.toUpperCase();
}

// 表格列
const columns = [
  {
    title: '序号', key: 'index', width: 60,
    render: (_: any, index: number) => (pagination.page - 1) * pagination.pageSize + index + 1
  },
  {
    title: '类型', key: 'type', width: 80,
    render: (row: Api.AppMarket.AppItem) => h(NTag, { size: 'small', type: getTypeTagType(row.type) }, () => getTypeLabel(row.type))
  },
  {
    title: '缩略图', key: 'thumbnailUrl', width: 80,
    render: (row: Api.AppMarket.AppItem) => {
      if (row.thumbnailUrl) {
        return h(NImage, {
          src: row.thumbnailUrl,
          width: 48, height: 48,
          objectFit: 'cover',
          style: { borderRadius: '4px' }
        });
      }
      return h('div', {
        style: 'width:48px;height:48px;background:#f5f5f5;border-radius:4px;display:flex;align-items:center;justify-content:center;color:#999;font-size:12px;'
      }, '无');
    }
  },
  { title: '名称', key: 'name', width: 120, ellipsis: { tooltip: true } },
  { title: '版本', key: 'version', width: 80 },
  {
    title: '简介', key: 'contentPreview', ellipsis: { tooltip: true },
    render: (row: Api.AppMarket.AppItem) => row.contentPreview || '-'
  },
  { title: '上架时间', key: 'createdTime', width: 140, ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    render: (row: Api.AppMarket.AppItem) => {
      const btns: VNode[] = [];

      // 下载按钮（所有人可见）
      btns.push(
        h(NButton, {
          size: 'tiny', type: 'info',
          onClick: () => handleDownload(row),
          style: 'margin-right: 6px; flex-shrink: 0;'
        }, () => '下载')
      );

      // 编辑/删除（仅管理员或所有者）
      if (isAdmin.value || row.userId === currentUserId.value) {
        btns.push(
          h(NButton, {
            size: 'tiny', type: 'warning',
            onClick: () => openDrawer('publish', row.id),
            style: 'margin-right: 6px; flex-shrink: 0;'
          }, () => '编辑')
        );

        btns.push(
          h(NPopconfirm, {
            onPositiveClick: () => handleDelete(row)
          }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'error', style: 'flex-shrink: 0;' }, () => '删除'),
            default: () => `确定删除 "${row.name}" 吗？`
          })
        );
      }

      return h(NSpace, { size: 'small', style: 'flex-wrap: nowrap;' }, () => btns);
    }
  }
];

async function loadData() {
  startLoading();
  try {
    const { data: result } = await fetchGetAppList({
      page: pagination.page,
      pageSize: pagination.pageSize,
      type: searchForm.type || undefined,
      name: searchForm.name || undefined
    });
    data.length = 0;
    data.push(...(result?.list || []));
    pagination.total = result?.total || 0;
  } finally {
    endLoading();
  }
}

async function handleDelete(row: Api.AppMarket.AppItem) {
  await fetchDeleteApp(row.id);
  message.success('删除成功');
  loadData();
}

async function handleDownload(row: Api.AppMarket.AppItem) {
  const { data: detail } = await fetchGetAppDetail(row.id);
  if (detail?.fileId) {
    window.open(getFileDownloadUrl(detail.fileId), '_blank');
  } else {
    message.warning('该应用暂无下载文件');
  }
}

async function fetchGetAppDetail(id: string) {
  const { request } = await import('@/service/request');
  return request<Api.AppMarket.AppDetail>({ url: `/api/market/apps/${id}`, method: 'GET' });
}

function openDrawer(mode: 'detail' | 'publish', appId?: string) {
  drawer.mode = mode;
  drawer.appId = appId || null;
  drawer.show = true;
}

function handleDrawerClose() {
  drawer.show = false;
  loadData();
}

function handleSearch() {
  pagination.page = 1;
  loadData();
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
      <!-- 搜索栏 -->
      <NSpace align="center">
        <NSelect
          v-model:value="searchForm.type"
          placeholder="应用类型"
          :options="[
            { label: '全部类型', value: '' },
            { label: 'App', value: 'app' },
            { label: 'CLI', value: 'cli' },
            { label: 'MCP', value: 'mcp' },
            { label: 'Skill', value: 'skill' }
          ]"
          clearable
          style="width: 140px"
        />
        <NInput
          v-model:value="searchForm.name"
          placeholder="应用名称"
          clearable
          style="width: 180px"
          @keyup.enter="handleSearch"
        />
        <NSpace align="center">
          <span style="font-size: 14px; color: #666;">历史版本</span>
          <NSwitch v-model:value="searchForm.includeHistory" />
        </NSpace>
        <NButton type="primary" @click="handleSearch">搜索</NButton>
        <NButton @click="loadData">刷新</NButton>
        <NButton type="primary" @click="openDrawer('publish')">上架</NButton>
      </NSpace>

      <!-- 表格 -->
      <NDataTable
        :columns="columns"
        :data="data"
        :loading="loading"
        :pagination="false"
        :scroll-x="900"
        :row-key="(row: Api.AppMarket.AppItem) => row.id"
      />

      <!-- 分页 -->
      <NSpace justify="end">
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

    <!-- 侧滑 -->
    <AppMarketDrawer
      v-model:show="drawer.show"
      :mode="drawer.mode"
      :app-id="drawer.appId"
      @close="handleDrawerClose"
    />
  </div>
</template>

<style scoped></style>
