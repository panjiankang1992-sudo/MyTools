<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { useCloudFileStore, type CloudFileTreeNode } from '@/store/modules/cloudfile';
import { useAppStore } from '@/store/modules/app';
import {
  createCloudDir,
  copyCloudFile,
  deleteCloudFile,
  downloadCloudFile,
  fetchFileContent,
  moveCloudFile,
  renameCloudFile,
  uploadCloudFile
} from '@/service/api/cloudfile';
import CloudFileEditor from './CloudFileEditor.vue';
import FileDetailPanel from './FileDetailPanel.vue';
import { useThumbnail } from '@/composables/cloudfile/useThumbnail';
import type { TreeOption, UploadFileInfo } from 'naive-ui';
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
  NModal,
  NInput,
  NUpload,
  NSelect,
  useMessage,
  useDialog,
  NEmpty,
  NSpin,
  NImage
} from 'naive-ui';
import {
  FolderOutline,
  DocumentTextOutline,
  ImageOutline,
  FilmOutline,
  MusicalNotesOutline,
  ConstructOutline,
  FileTrayOutline,
  DownloadOutline,
  CreateOutline,
  TrashOutline,
  CloudUploadOutline,
  Folder,
  ArrowForwardOutline,
  CopyOutline,
  SettingsOutline
} from '@vicons/ionicons5';
import { fetchWebdavAccounts } from '@/service/api/webdav';
import AccountDrawer from '../components/AccountDrawer.vue';

defineOptions({ name: 'CloudFile' });

const message = useMessage();
const dialog = useDialog();
const store = useCloudFileStore();
const appStore = useAppStore();
const { getImageThumbnail, isImageFile, isVideoFile } = useThumbnail();

// 账号相关
const accounts = ref<Api.Webdav.WebdavAccount[]>([]);
const accountOptions = computed(() =>
  accounts.value.map(a => ({ label: a.name, value: a.id }))
);

async function loadAccounts() {
  try {
    const { data } = await fetchWebdavAccounts();
    if (data) {
      accounts.value = data;
      const defaultAccount = data.find(a => a.isDefault === 1) || data[0];
      if (defaultAccount) {
        store.currentAccountId = defaultAccount.id;
        await store.init(defaultAccount.id);
      } else {
        await store.init();
      }
    }
  } catch {
    await store.init();
  }
}

async function handleAccountChange(accountId: string) {
  selectedTreeKey.value = [];
  await store.init(accountId);
  await loadAccounts();
}

const accountDrawerShow = ref(false);

function openAccountDrawer() {
  accountDrawerShow.value = true;
}

// 表格缩略图缓存
const tableThumbnails = reactive<Record<string, string>>({});
const thumbnailLoading = reactive<Record<string, boolean>>({});
// 树相关
const selectedTreeKey = ref<string[]>([]);

// mkdir 弹窗
const mkdirModal = reactive({
  show: false,
  name: '',
  creating: false
});

// rename 弹窗
const renameModal = reactive({
  show: false,
  file: null as { name: string; path: string } | null,
  newName: ''
});

// 编辑器弹窗
const editorModal = reactive({
  show: false,
  file: null as { path: string; name: string; content: string } | null,
  loading: false
});

// 移动弹窗
const moveModal = reactive({
  show: false,
  file: null as { name: string; path: string } | null,
  targetPath: '',
  moving: false
});

// 复制弹窗
const copyModal = reactive({
  show: false,
  file: null as { name: string; path: string } | null,
  targetPath: '',
  copying: false
});

// 图片预览
const imagePreview = reactive({
  show: false,
  url: '',
  name: '',
  loading: false
});

watch(() => imagePreview.show, (visible) => {
  if (!visible && imagePreview.url) {
    URL.revokeObjectURL(imagePreview.url);
    imagePreview.url = '';
  }
});

// 文件列表变化时预加载缩略图
watch(() => store.fileList, async (items) => {
  for (const item of items) {
    if (item.isDirectory) continue;
    if (tableThumbnails[item.path] || thumbnailLoading[item.path]) continue;
    if (!isImageFile(item.name) && !isVideoFile(item.name)) continue;

    thumbnailLoading[item.path] = true;
    try {
      tableThumbnails[item.path] = await getImageThumbnail(item.path);
    } catch {
      // 缩略图加载失败，静默处理
    } finally {
      thumbnailLoading[item.path] = false;
    }
  }
});

// 上传状态
const isUploading = ref(false);

// 上传时需要保持当前目录
const uploadCurrentPath = ref('/');

// 格式化文件大小
function formatSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

// 格式化日期
function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-';
  try {
    const d = new Date(dateStr);
    return d.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  } catch {
    return dateStr;
  }
}

// 根据文件名获取文件图标组件
function getFileIcon(name: string, isDirectory: boolean) {
  if (isDirectory) return Folder;
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg'].includes(ext)) return ImageOutline;
  if (['mp4', 'avi', 'mov', 'mkv', 'flv'].includes(ext)) return FilmOutline;
  if (['mp3', 'wav', 'flac', 'aac', 'ogg'].includes(ext)) return MusicalNotesOutline;
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return FileTrayOutline;
  if (['js', 'ts', 'py', 'java', 'c', 'cpp', 'h', 'sh', 'json', 'xml', 'yaml', 'yml', 'md', 'txt', 'html', 'css', 'sql', 'log', 'vue', 'jsx', 'tsx'].includes(ext)) return DocumentTextOutline;
  return FileTrayOutline;
}

// 路径面包屑数组
const breadcrumbs = computed(() => {
  const parts = store.currentPath.split('/').filter(Boolean);
  const crumbs = [{ label: '根目录', path: '/' }];
  let cumPath = '';
  for (const part of parts) {
    cumPath += '/' + part;
    crumbs.push({ label: part, path: cumPath });
  }
  return crumbs;
});

// 表格列定义
const columns = [
  {
    title: '名称',
    key: 'name',
    width: 280,
    render: (row: Api.CloudFile.CloudFileItem) => {
      const thumbUrl = tableThumbnails[row.path];
      const isMedia = isImageFile(row.name) || isVideoFile(row.name);

      const iconSize = appStore.isMobile ? 36 : 72;
      const baseFlexStyle = `display:flex;align-items:center;gap:8px;cursor:pointer;min-width:0;${appStore.isMobile ? 'min-height:40px;' : ''}`;
      const nameSpan = h('span', { style: 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;' }, row.name);
      const nameProps = {
        role: 'button',
        tabindex: 0,
        style: baseFlexStyle,
        onClick: (event: MouseEvent) => {
          event.stopPropagation();
          void handleRowClick(row);
        },
        onKeydown: (event: KeyboardEvent) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            event.stopPropagation();
            void handleRowClick(row);
          }
        }
      };

      if (row.isDirectory) {
        return h('div', nameProps, [
          h(Folder, { size: iconSize, style: `flex-shrink:0;color:#f0a020;width:${iconSize}px;height:${iconSize}px;` }),
          nameSpan
        ]);
      }

      if (isMedia && thumbUrl) {
        return h('div', nameProps, [
          h('img', { src: thumbUrl, style: `width:${iconSize}px;height:${iconSize}px;object-fit:cover;border-radius:4px;flex-shrink:0;` }),
          nameSpan
        ]);
      }

      const IconComp = getFileIcon(row.name, row.isDirectory);
      return h('div', nameProps, [
        h(IconComp, { size: iconSize, style: `flex-shrink:0;color:#666;width:${iconSize}px;height:${iconSize}px;` }),
        nameSpan
      ]);
    }
  },
  {
    title: '大小',
    key: 'size',
    width: 100,
    render: (row: Api.CloudFile.CloudFileItem) => row.isDirectory ? '-' : formatSize(row.size)
  },
  {
    title: '修改时间',
    key: 'lastModified',
    width: 160,
    render: (row: Api.CloudFile.CloudFileItem) => formatDate(row.lastModified)
  },
  {
    title: '操作',
    key: 'actions',
    width: 300,
    render: (row: Api.CloudFile.CloudFileItem) => {
      const btns: ReturnType<typeof h>[] = [];

      // 双击/点击名称时已经在 handleRowClick 中处理，这里只放额外按钮
      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              void handleDownload(row);
            },
            style: 'margin-right:4px;flex-shrink:0;'
          },
          { icon: () => h(DownloadOutline, { size: 14 }) }
        )
      );

      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              handleRename(row);
            },
            style: 'margin-right:4px;flex-shrink:0;'
          },
          { icon: () => h(CreateOutline, { size: 14 }) }
        )
      );

      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              handleMove(row);
            },
            style: 'margin-right:4px;flex-shrink:0;'
          },
          { icon: () => h(ArrowForwardOutline, { size: 14 }) }
        )
      );

      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              handleCopy(row);
            },
            style: 'margin-right:4px;flex-shrink:0;'
          },
          { icon: () => h(CopyOutline, { size: 14 }) }
        )
      );

      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            type: 'error',
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              handleDelete(row);
            },
            style: 'flex-shrink:0;'
          },
          { icon: () => h(TrashOutline, { size: 14 }) }
        )
      );

      return h(NSpace, { size: 'small', style: 'flex-wrap:nowrap;' }, () => btns);
    }
  }
];

// 表格行点击：目录→导航，文本文件→编辑器，图片→预览
async function handleRowClick(row: Api.CloudFile.CloudFileItem) {
  if (row.isDirectory) {
    await store.navigateTo(row.path);
    selectedTreeKey.value = [row.path];
    uploadCurrentPath.value = row.path;
  } else {
    const ext = row.name.split('.').pop()?.toLowerCase() || '';
    const imageExts = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico'];
    if (imageExts.includes(ext)) {
      if (imagePreview.url) URL.revokeObjectURL(imagePreview.url);
      imagePreview.loading = true;
      imagePreview.name = row.name;
      imagePreview.url = '';
      imagePreview.show = true;
      try {
        const { data: blob } = await downloadCloudFile(row.path, store.getAccountId());
        if (blob) {
          imagePreview.url = URL.createObjectURL(blob);
        }
      } finally {
        imagePreview.loading = false;
      }
    } else {
      const textExts = ['md', 'txt', 'json', 'xml', 'html', 'css', 'js', 'ts', 'vue', 'py', 'java', 'c', 'cpp', 'h', 'sh', 'yaml', 'yml', 'properties', 'jsx', 'tsx'];
      if (textExts.includes(ext)) {
        editorModal.loading = true;
        editorModal.file = null;
        editorModal.show = true;
        try {
          const { data } = await fetchFileContent(row.path, store.getAccountId());
          editorModal.file = { path: row.path, name: row.name, content: data || '' };
        } finally {
          editorModal.loading = false;
        }
      } else {
        message.warning('该文件类型不支持在线预览');
      }
    }
  }
}

// 树节点点击：目录→导航，文件→切换到详情视图
async function handleTreeSelect(keys: string[]) {
  if (keys.length === 0) return;
  const path = keys[0]!;
  const node = store.findNode(path);
  if (!node) return;
  selectedTreeKey.value = [path];

  if (node.isDirectory) {
    store.clearSelection();
    uploadCurrentPath.value = path;
    await store.navigateTo(path);
  } else {
    store.selectFile(path);
    const parentPath = path.substring(0, path.lastIndexOf('/')) || '/';
    uploadCurrentPath.value = parentPath;
    await store.navigateTo(parentPath);
  }
}

// 树节点懒加载（展开时触发）
async function handleTreeLoad(node: TreeOption) {
  if (!node.isDirectory) return Promise.resolve();
  await store.loadTreeNodeChildren(node as unknown as CloudFileTreeNode);
  return Promise.resolve();
}

// 从文件详情返回目录视图
function handleBackToDirectory() {
  store.clearSelection();
  selectedTreeKey.value = [store.currentPath];
}

// 刷新
async function handleRefresh() {
  await store.refresh();
}

// 打开 mkdir 弹窗
function openMkdirModal() {
  mkdirModal.name = '';
  mkdirModal.show = true;
}

// 提交 mkdir
async function handleMkdirSubmit() {
  if (!mkdirModal.name.trim()) {
    message.warning('请输入目录名称');
    return;
  }
  mkdirModal.creating = true;
  try {
    const dirPath = (store.currentPath === '/' ? '' : store.currentPath) + '/' + mkdirModal.name.trim();
    await createCloudDir(dirPath, store.getAccountId());
    message.success('目录创建成功');
    mkdirModal.show = false;
    await store.refresh();
  } finally {
    mkdirModal.creating = false;
  }
}

// 打开 rename 弹窗
function handleRename(file: { name: string; path: string }) {
  renameModal.file = file;
  renameModal.newName = file.name;
  renameModal.show = true;
}

// 提交 rename
async function handleRenameSubmit() {
  if (!renameModal.file) return;
  const newName = renameModal.newName.trim();
  if (!newName) {
    message.warning('请输入新名称');
    return;
  }
  if (newName === renameModal.file.name) {
    renameModal.show = false;
    return;
  }
  try {
    await renameCloudFile(renameModal.file.path, newName, store.getAccountId());
    message.success('重命名成功');
    renameModal.show = false;
    await store.refresh();
  } catch {
    // 错误已由 request 拦截器处理
  }
}

// 下载
async function handleDownload(file: { name: string; path: string }) {
  try {
    const { data: blob, error } = await downloadCloudFile(file.path, store.getAccountId());
    if (error || !blob) {
      message.error('下载失败');
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    message.success('下载已开始');
  } catch {
    message.error('下载失败');
  }
}

// 移动
function handleMove(file: { name: string; path: string }) {
  moveModal.file = file;
  moveModal.targetPath = store.currentPath === '/' ? '/' : store.currentPath + '/';
  moveModal.show = true;
}

async function handleMoveSubmit() {
  if (!moveModal.file || !moveModal.targetPath.trim()) return;
  moveModal.moving = true;
  try {
    const toPath =
      (moveModal.targetPath.endsWith('/') ? moveModal.targetPath : moveModal.targetPath + '/') +
      moveModal.file.name;
    await moveCloudFile(moveModal.file.path, toPath, store.getAccountId());
    message.success('移动成功');
    moveModal.show = false;
    await store.refresh();
  } finally {
    moveModal.moving = false;
  }
}

// 复制
function handleCopy(file: { name: string; path: string }) {
  copyModal.file = file;
  copyModal.targetPath = store.currentPath === '/' ? '/' : store.currentPath + '/';
  copyModal.show = true;
}

async function handleCopySubmit() {
  if (!copyModal.file || !copyModal.targetPath.trim()) return;
  copyModal.copying = true;
  try {
    const toPath =
      (copyModal.targetPath.endsWith('/') ? copyModal.targetPath : copyModal.targetPath + '/') +
      copyModal.file.name;
    await copyCloudFile(copyModal.file.path, toPath, store.getAccountId());
    message.success('复制成功');
    copyModal.show = false;
    await store.refresh();
  } finally {
    copyModal.copying = false;
  }
}

// 编辑器保存后刷新
function handleEditorSaved() {
  store.refresh();
}

// 删除
function handleDelete(file: { name: string; path: string; isDirectory?: boolean }) {
  dialog.warning({
    title: '确认删除',
    content: `确定删除 "${file.name}" ${file.isDirectory ? '(包含所有内容)' : ''} 吗？此操作不可恢复！`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteCloudFile(file.path, file.isDirectory, store.getAccountId());
        message.success('删除成功');
        await store.refresh();
      } catch {
        // 错误已由 request 拦截器处理
      }
    }
  });
}

// 上传：使用 NUpload custom-request
function customUpload({ file, onFinish, onError }: { file: UploadFileInfo; onFinish: () => void; onError: (e: Error) => void }) {
  const targetPath = uploadCurrentPath.value === '/' ? '/' : uploadCurrentPath.value + '/';
  const rawFile = file.file;
  if (!rawFile) return;
  isUploading.value = true;
  uploadCloudFile(targetPath, file.name, rawFile, store.getAccountId())
    .then(({ error }) => {
      if (error) {
        onError(new Error(String(error)));
      } else {
        onFinish();
        store.refresh();
      }
    })
    .catch((e: Error) => onError(e))
    .finally(() => {
      isUploading.value = false;
    });
}

// 面包屑导航
async function handleBreadcrumbNavigate(path: string) {
  await store.navigateTo(path);
  selectedTreeKey.value = [path];
  uploadCurrentPath.value = path;
}

// 初始化
onMounted(() => loadAccounts());
</script>

<template>
  <div style="height: 100%">
    <n-layout :has-sider="!appStore.isMobile" style="height: 100%">
    <!-- 左侧目录树 -->
    <n-layout-sider v-if="!appStore.isMobile" :width="220" bordered content-style="padding: 8px;">
      <n-space vertical :size="8" style="height: 100%;">
        <div style="font-size: 12px; color: #888; padding: 4px 4px 8px;">
          云文件
        </div>
        <n-spin :show="store.loading" style="flex: 1; overflow: auto;">
          <n-tree
            v-model:selected-keys="selectedTreeKey"
            :data="store.treeData"
            block-line
            expand-on-click
            select-on-click
            virtual-scroll
            :load-mode="(mode: string) => mode"
            @load="handleTreeLoad"
            @update:selected-keys="handleTreeSelect"
          />
        </n-spin>
      </n-space>
    </n-layout-sider>

    <!-- 右侧内容区 -->
    <n-layout-content content-style="display: flex; flex-direction: column; height: 100%;">
      <!-- 账号选择器 -->
      <div
        :style="{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          padding: appStore.isMobile ? '8px 8px 0' : '8px 16px 0',
          flexWrap: appStore.isMobile ? 'wrap' : 'nowrap'
        }"
      >
        <n-select
          v-model:value="store.currentAccountId"
          :options="accountOptions"
          placeholder="选择账号"
          :style="{ width: appStore.isMobile ? '220px' : '200px' }"
          @update:value="handleAccountChange"
        />
        <n-button size="small" @click="openAccountDrawer">
          <template #icon><settings-outline /></template>
          管理
        </n-button>
      </div>

      <!-- 工具栏：面包屑 + 操作按钮 -->
      <div
        :style="{
          display: 'flex',
          alignItems: appStore.isMobile ? 'stretch' : 'center',
          flexDirection: appStore.isMobile ? 'column' : 'row',
          gap: '12px',
          padding: appStore.isMobile ? '8px' : '8px 16px',
          borderBottom: '1px solid #f0f0f0',
          flexShrink: 0
        }"
      >
        <!-- 面包屑 -->
        <n-breadcrumb style="flex: 1; min-width: 0;">
          <n-breadcrumb-item
            v-for="crumb in breadcrumbs"
            :key="crumb.path"
            :clickable="crumb.path !== store.currentPath"
            @click="handleBreadcrumbNavigate(crumb.path)"
          >
            {{ crumb.label }}
          </n-breadcrumb-item>
        </n-breadcrumb>

        <!-- 操作按钮 -->
        <n-space :wrap="appStore.isMobile">
          <!-- 新建目录 -->
          <n-button size="small" @click="openMkdirModal">
            <template #icon>
              <folder-outline />
            </template>
            新建目录
          </n-button>

          <!-- 上传 -->
          <n-upload
            :custom-request="customUpload"
            :show-file-list="false"
            :disabled="isUploading"
            multiple
          >
            <n-button size="small" :loading="isUploading">
              <template #icon>
                <cloud-upload-outline />
              </template>
              {{ isUploading ? '上传中...' : '上传文件' }}
            </n-button>
          </n-upload>

          <!-- 刷新 -->
          <n-button size="small" @click="handleRefresh">
            <template #icon>
              <construct-outline />
            </template>
            刷新
          </n-button>
        </n-space>
      </div>

      <!-- 文件列表 / 文件详情 -->
      <div :style="{ flex: 1, overflow: 'auto', padding: appStore.isMobile ? '8px' : '8px 16px' }">
        <!-- 文件详情视图 -->
        <FileDetailPanel
          v-if="store.viewMode === 'file-detail' && store.selectedNode"
          :file="{
            name: store.selectedNode.label,
            path: store.selectedNode.path,
            size: store.selectedNode.size ?? 0,
            contentType: store.selectedNode.contentType ?? null,
            lastModified: store.selectedNode.lastModified ?? null,
            isDirectory: false
          }"
          @back="handleBackToDirectory"
          @download="handleDownload(store.selectedNode as any)"
          @rename="handleRename(store.selectedNode as any)"
          @move="handleMove(store.selectedNode as any)"
          @copy="handleCopy(store.selectedNode as any)"
          @delete="handleDelete(store.selectedNode as any)"
        />

        <!-- 目录表格视图 -->
        <template v-else>
          <n-data-table
            :columns="columns"
            :data="store.fileList"
            :loading="store.loading"
            :pagination="false"
            :row-key="(row: Api.CloudFile.CloudFileItem) => row.path"
            :row-props="(row: Api.CloudFile.CloudFileItem) => ({
              style: 'cursor: pointer;',
              onClick: () => handleRowClick(row)
            })"
            striped
          />

          <!-- 空状态 -->
          <n-empty
            v-if="!store.loading && store.isEmpty"
            description="该目录为空"
            style="margin-top: 48px;"
          />
        </template>
      </div>
    </n-layout-content>
  </n-layout>
  </div>

  <!-- 账号管理抽屉 -->
  <account-drawer
    v-model:show="accountDrawerShow"
    account-type="webdav"
    @account-change="handleAccountChange"
  />

  <!-- 新建目录弹窗 -->
  <n-modal
    v-model:show="mkdirModal.show"
    preset="card"
    title="新建目录"
    style="width: 400px;"
  >
    <n-input
      v-model:value="mkdirModal.name"
      placeholder="请输入目录名称"
      @keyup.enter="handleMkdirSubmit"
    />
    <template #footer>
      <n-space justify="end">
        <n-button @click="mkdirModal.show = false">取消</n-button>
        <n-button type="primary" :loading="mkdirModal.creating" @click="handleMkdirSubmit">
          创建
        </n-button>
      </n-space>
    </template>
  </n-modal>

  <!-- 重命名弹窗 -->
  <n-modal
    v-model:show="renameModal.show"
    preset="card"
    title="重命名"
    style="width: 400px;"
  >
    <n-input
      v-model:value="renameModal.newName"
      placeholder="请输入新名称"
      @keyup.enter="handleRenameSubmit"
    />
    <template #footer>
      <n-space justify="end">
        <n-button @click="renameModal.show = false">取消</n-button>
        <n-button type="primary" @click="handleRenameSubmit">
          确认
        </n-button>
      </n-space>
    </template>
  </n-modal>

  <!-- 编辑器弹窗 -->
  <cloud-file-editor
    v-model:show="editorModal.show"
    :file="editorModal.file"
    :loading="editorModal.loading"
    :account-id="store.currentAccountId"
    @saved="handleEditorSaved"
  />

  <!-- 图片预览弹窗 -->
  <n-modal
    v-model:show="imagePreview.show"
    preset="card"
    :title="imagePreview.name"
    style="width: 90vw; max-width: 1200px;"
    :mask-closable="true"
  >
    <div style="display: flex; justify-content: center; align-items: center; min-height: 200px;">
      <n-spin :show="imagePreview.loading">
        <n-image
          v-if="imagePreview.url"
          :src="imagePreview.url"
          style="max-height: 80vh;"
          object-fit="contain"
          show-toolbar-tooltip
        />
      </n-spin>
    </div>
  </n-modal>

  <!-- 移动弹窗 -->
  <n-modal v-model:show="moveModal.show" preset="card" title="移动文件" style="width: 400px;">
    <div style="margin-bottom: 12px;">
      移动 <b>{{ moveModal.file?.name }}</b> 到：
    </div>
    <n-input
      v-model:value="moveModal.targetPath"
      placeholder="目标路径，如 /docs"
      @keyup.enter="handleMoveSubmit"
    />
    <template #footer>
      <n-space justify="end">
        <n-button @click="moveModal.show = false">取消</n-button>
        <n-button type="primary" :loading="moveModal.moving" @click="handleMoveSubmit">移动</n-button>
      </n-space>
    </template>
  </n-modal>

  <!-- 复制弹窗 -->
  <n-modal v-model:show="copyModal.show" preset="card" title="复制文件" style="width: 400px;">
    <div style="margin-bottom: 12px;">
      复制 <b>{{ copyModal.file?.name }}</b> 到：
    </div>
    <n-input
      v-model:value="copyModal.targetPath"
      placeholder="目标路径，如 /backup"
      @keyup.enter="handleCopySubmit"
    />
    <template #footer>
      <n-space justify="end">
        <n-button @click="copyModal.show = false">取消</n-button>
        <n-button type="primary" :loading="copyModal.copying" @click="handleCopySubmit">复制</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped></style>
