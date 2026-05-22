<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useAlistStore, type AlistTreeNode } from '@/store/modules/alist';
import { fetchAlistRawUrl } from '@/service/api/alist';
import type { TreeOption } from 'naive-ui';
import {
  NLayout,
  NLayoutSider,
  NLayoutContent,
  NTree,
  NBreadcrumb,
  NBreadcrumbItem,
  NButton,
  NSpace,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NSelect,
  useMessage,
  NEmpty,
  NSpin,
  NImage,
  NIcon
} from 'naive-ui';
import {
  FolderOutline,
  DocumentTextOutline,
  ImageOutline,
  RefreshOutline
} from '@vicons/ionicons5';

defineOptions({ name: 'AlistIndex' });

const message = useMessage();
const store = useAlistStore();

const selectedTreeKey = ref<string[]>([]);

onMounted(async () => {
  await store.loadAccounts();
  const alistAccounts = store.accounts.filter(a => a.type === 'alist');
  if (alistAccounts.length === 0) {
    message.warning('未找到 Alist 账号，请先在 WebDAV 管理中添加 Alist 账号');
    return;
  }
  const defaultAccount = alistAccounts.find(a => a.isDefault === 1) || alistAccounts[0];
  store.currentAccountId = defaultAccount.id;
  await store.init(defaultAccount.id);
});

const accountOptions = computed(() =>
  store.accounts
    .filter(a => a.type === 'alist')
    .map(a => ({ label: a.name, value: a.id }))
);

async function handleAccountChange(accountId: string) {
  await store.init(accountId);
  selectedTreeKey.value = [];
}

const breadcrumbs = computed(() => {
  const parts = store.currentPath.split('/').filter(Boolean);
  const crumbs = [{ label: '根目录', path: '/' }];
  let accumulated = '';
  for (const part of parts) {
    accumulated += '/' + part;
    crumbs.push({ label: part, path: accumulated });
  }
  return crumbs;
});

function handleBreadcrumbNavigate(path: string) {
  if (path !== store.currentPath) {
    store.navigateTo(path);
    selectedTreeKey.value = [path];
  }
}

const columns = [
  {
    title: '名称',
    key: 'name',
    render(row: Api.CloudFile.CloudFileItem) {
      const icon = row.isDirectory
        ? h(NIcon, null, { default: () => h(FolderOutline, { size: 16, color: '#f0ad4e' }) })
        : h(NIcon, null, { default: () => h(DocumentTextOutline, { size: 16, color: '#909399' }) });
      return h('span', {
        style: 'display: flex; align-items: center; cursor: pointer; gap: 6px;',
        onClick: () => row.isDirectory ? store.navigateTo(row.path) : handlePreview(row)
      }, [icon, h('span', null, { default: () => row.name })]);
    }
  },
  {
    title: '大小',
    key: 'size',
    width: 100,
    render(row: Api.CloudFile.CloudFileItem) {
      if (row.isDirectory) return '-';
      return row.size ? formatSize(row.size) : '-';
    }
  },
  {
    title: '修改时间',
    key: 'lastModified',
    width: 180,
    render(row: Api.CloudFile.CloudFileItem) {
      if (!row.lastModified) return '-';
      const iso = row.lastModified;
      return iso.replace('T', ' ').substring(0, 19);
    }
  }
];

function formatSize(bytes: number): string {
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

const previewDrawer = reactive({ show: false, title: '', rawUrl: '', loading: false });

async function handlePreview(file: Api.CloudFile.CloudFileItem) {
  previewDrawer.title = file.name;
  previewDrawer.show = true;
  previewDrawer.rawUrl = '';
  previewDrawer.loading = true;
  try {
    const { data, error } = await fetchAlistRawUrl(
      file.path,
      store.currentAccountId || undefined
    );
    if (error || !data?.rawUrl) {
      message.error('获取预览失败');
      previewDrawer.show = false;
      return;
    }
    previewDrawer.rawUrl = data.rawUrl;
  } catch {
    message.error('获取预览失败');
    previewDrawer.show = false;
  } finally {
    previewDrawer.loading = false;
  }
}

function isImageFile(name: string) {
  return /\.(jpg|jpeg|png|gif|webp|bmp|svg)$/i.test(name);
}

function isTextFile(name: string) {
  return /\.(md|txt|json|xml|html|htm|css|js|ts|py|java|cpp|c|h|sh|yaml|yml|properties)$/i.test(name);
}
</script>

<template>
  <div style="height: 100%">
    <n-layout has-sider style="height: 100%">
      <!-- 左侧目录树 -->
      <n-layout-sider :width="220" bordered content-style="padding: 8px;">
        <n-space vertical :size="8" style="height: 100%;">
          <div style="font-size: 12px; color: #888; padding: 4px; font-weight: 500;">Alist 文件</div>
          <n-spin :show="store.loading" style="flex: 1; overflow: auto;">
            <n-tree
              v-model:selected-keys="selectedTreeKey"
              :data="store.treeData"
              :expand-on-click="true"
              virtual-scroll
              block-node
              @update:selected-keys="(keys) => { if (keys[0]) { store.navigateTo(keys[0] as string); } }"
              @load="(node: TreeOption) => store.loadTreeNodeChildren(node as unknown as AlistTreeNode)"
            />
            <n-empty
              v-if="!store.loading && store.treeData.length === 0"
              description="暂无文件"
              style="margin-top: 16px;"
            />
          </n-spin>
        </n-space>
      </n-layout-sider>

      <!-- 右侧内容区 -->
      <n-layout-content content-style="display: flex; flex-direction: column; height: 100%;">
        <!-- 账号选择器 -->
        <div style="display:flex;align-items:center;gap:8px;padding:8px 16px 0;">
          <n-select
            v-model:value="store.currentAccountId"
            :options="accountOptions"
            placeholder="选择 Alist 账号"
            style="width:200px;"
            @update:value="handleAccountChange"
          />
        </div>

        <!-- 工具栏 -->
        <div style="display:flex;align-items:center;gap:12px;padding:8px 16px;border-bottom:1px solid #f0f0f0;flex-shrink:0;">
          <n-breadcrumb style="flex:1;min-width:0;">
            <n-breadcrumb-item
              v-for="crumb in breadcrumbs"
              :key="crumb.path"
              :clickable="crumb.path !== store.currentPath"
              @click="handleBreadcrumbNavigate(crumb.path)"
            >
              {{ crumb.label }}
            </n-breadcrumb-item>
          </n-breadcrumb>
          <n-space>
            <n-button size="small" @click="store.refresh()">
              <template #icon><n-icon :component="RefreshOutline" /></template>
              刷新
            </n-button>
          </n-space>
        </div>

        <!-- 文件列表 -->
        <div style="flex:1;overflow:auto;padding:0 16px 16px;">
          <n-data-table
            :columns="columns"
            :data="store.fileList"
            :loading="store.loading"
            :row-key="(row: Api.CloudFile.CloudFileItem) => row.path"
            :pagination="false"
            :bordered="false"
            virtual-scroll
            style="margin-top: 8px;"
          />
          <n-empty
            v-if="!store.loading && store.isEmpty"
            description="该目录为空"
            style="margin-top: 48px;"
          />
        </div>
      </n-layout-content>
    </n-layout>

    <!-- 预览抽屉 -->
    <n-drawer v-model:show="previewDrawer.show" :width="800" placement="right">
      <n-drawer-content :title="previewDrawer.title" closable>
        <n-spin :show="previewDrawer.loading" description="加载中...">
          <div v-if="isImageFile(previewDrawer.title)" style="text-align:center;">
            <n-image :src="previewDrawer.rawUrl" width="100%" />
          </div>
          <div v-else-if="isTextFile(previewDrawer.title)" style="height:70vh;">
            <iframe :src="previewDrawer.rawUrl" style="width:100%;height:100%;border:none;" />
          </div>
          <div v-else style="text-align:center;padding:48px;">
            <p>此文件类型不支持内嵌预览</p>
            <n-button
              tag="a"
              :href="previewDrawer.rawUrl"
              target="_blank"
              type="primary"
              style="margin-top:16px;"
            >
              在新窗口打开
            </n-button>
          </div>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>
