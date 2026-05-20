<script setup lang="ts">
import { computed, h, reactive, ref } from 'vue';
import { useCloudFileStore } from '@/store/modules/cloudfile';
import {
  createCloudDir,
  deleteCloudFile,
  downloadCloudFile,
  renameCloudFile,
  uploadCloudFile
} from '@/service/api/cloudfile';
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
  useMessage,
  useDialog,
  NEmpty,
  NSpin
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
  Folder
} from '@vicons/ionicons5';

defineOptions({ name: 'CloudFile' });

const message = useMessage();
const dialog = useDialog();
const store = useCloudFileStore();
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
  file: null as Api.CloudFile.CloudFileItem | null,
  newName: ''
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
  if (['js', 'ts', 'py', 'java', 'c', 'cpp', 'h', 'sh'].includes(ext)) return DocumentTextOutline;
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
    render: (row: Api.CloudFile.CloudFileItem) => {
      const IconComp = getFileIcon(row.name, row.isDirectory);
      return h('div', { style: 'display:flex;align-items:center;gap:6px;cursor:pointer;' }, [
        h(IconComp, { size: 16, style: 'flex-shrink:0;color:#666;' }),
        h('span', { style: 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }, row.name)
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
    width: 220,
    render: (row: Api.CloudFile.CloudFileItem) => {
      const btns = [];

      // 双击/点击名称时已经在 handleRowClick 中处理，这里只放额外按钮
      btns.push(
        h(
          NButton,
          {
            size: 'tiny',
            onClick: () => handleDownload(row),
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
            onClick: () => handleRename(row),
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
            type: 'error',
            onClick: () => handleDelete(row),
            style: 'flex-shrink:0;'
          },
          { icon: () => h(TrashOutline, { size: 14 }) }
        )
      );

      return h(NSpace, { size: 'small', style: 'flex-wrap:nowrap;' }, () => btns);
    }
  }
];

// 表格行点击：目录→导航，文件→打开
async function handleRowClick(row: Api.CloudFile.CloudFileItem) {
  if (row.isDirectory) {
    await store.navigateTo(row.path);
    selectedTreeKey.value = [row.path];
  } else {
    // TODO: Task 9 替换为 Monaco Editor
    message.warning(`文件已选择: ${row.name}`);
  }
}

// 树节点点击：导航到该目录
async function handleTreeSelect(keys: string[]) {
  if (keys.length === 0) return;
  const path = keys[0]!;
  selectedTreeKey.value = [path];
  uploadCurrentPath.value = path;
  await store.navigateTo(path);
}

// 树节点懒加载（展开时触发）
async function handleTreeLoad({ node }: { node: any }) {
  if (!node.isDirectory) return Promise.resolve();
  await store.loadTreeNodeChildren(node);
  return Promise.resolve();
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
    await createCloudDir(dirPath);
    message.success('目录创建成功');
    mkdirModal.show = false;
    await store.refresh();
  } finally {
    mkdirModal.creating = false;
  }
}

// 打开 rename 弹窗
function handleRename(file: Api.CloudFile.CloudFileItem) {
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
    await renameCloudFile(renameModal.file.path, newName);
    message.success('重命名成功');
    renameModal.show = false;
    await store.refresh();
  } catch {
    // 错误已由 request 拦截器处理
  }
}

// 下载
async function handleDownload(file: Api.CloudFile.CloudFileItem) {
  try {
    const { data: blob, error } = await downloadCloudFile(file.path);
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

// 删除
function handleDelete(file: Api.CloudFile.CloudFileItem) {
  dialog.warning({
    title: '确认删除',
    content: `确定删除 "${file.name}" ${file.isDirectory ? '(包含所有内容)' : ''} 吗？此操作不可恢复！`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteCloudFile(file.path, file.isDirectory);
        message.success('删除成功');
        await store.refresh();
      } catch {
        // 错误已由 request 拦截器处理
      }
    }
  });
}

// 上传：使用 NUpload custom-request
function customUpload({ file, onFinish, onError }: { file: File; onFinish: () => void; onError: (e: Error) => void }) {
  const targetPath = uploadCurrentPath.value === '/' ? '/' : uploadCurrentPath.value + '/';
  isUploading.value = true;
  uploadCloudFile(targetPath, file.name, file)
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
store.init();
</script>

<template>
  <n-layout has-sider style="height: 100%">
    <!-- 左侧目录树 -->
    <n-layout-sider :width="220" bordered content-style="padding: 8px;">
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
      <!-- 工具栏：面包屑 + 操作按钮 -->
      <div
        style="
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 8px 16px;
          border-bottom: 1px solid #f0f0f0;
          flex-shrink: 0;
        "
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
        <n-space>
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

      <!-- 文件列表 -->
      <div style="flex: 1; overflow: auto; padding: 8px 16px;">
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
      </div>
    </n-layout-content>
  </n-layout>

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
</template>

<style scoped></style>
