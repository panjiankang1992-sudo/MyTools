<script setup lang="ts">
import { ref, watch } from 'vue';
import { useThumbnail } from '@/composables/cloudfile/useThumbnail';
import {
  NButton,
  NSpace,
  NImage,
  NSpin,
  NDescriptions,
  NDescriptionsItem
} from 'naive-ui';
import {
  ArrowBackOutline,
  DownloadOutline,
  CreateOutline,
  ArrowForwardOutline,
  CopyOutline,
  TrashOutline,
  DocumentTextOutline,
  ConstructOutline
} from '@vicons/ionicons5';

const props = defineProps<{
  file: {
    name: string;
    path: string;
    size: number;
    contentType: string | null;
    lastModified: string | null;
    isDirectory: boolean;
  };
}>();

const emit = defineEmits<{
  (e: 'back'): void;
  (e: 'download'): void;
  (e: 'rename'): void;
  (e: 'move'): void;
  (e: 'copy'): void;
  (e: 'delete'): void;
}>();

const { getImageThumbnail, getVideoThumbnail, isImageFile, isVideoFile } = useThumbnail();

const thumbnailUrl = ref<string>('');
const thumbnailLoading = ref(false);
const thumbnailError = ref(false);

watch(
  () => props.file,
  async f => {
    thumbnailUrl.value = '';
    thumbnailError.value = false;
    if (!f) return;
    if (isImageFile(f.name)) {
      thumbnailLoading.value = true;
      try {
        thumbnailUrl.value = await getImageThumbnail(f.path);
      } catch {
        thumbnailError.value = true;
      } finally {
        thumbnailLoading.value = false;
      }
    } else if (isVideoFile(f.name)) {
      thumbnailLoading.value = true;
      try {
        thumbnailUrl.value = await getVideoThumbnail(f.path);
      } catch {
        thumbnailError.value = true;
      } finally {
        thumbnailLoading.value = false;
      }
    }
  },
  { immediate: true }
);

function formatSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

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
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%;">
    <!-- 返回工具栏 -->
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
      <n-button size="small" @click="emit('back')">
        <template #icon>
          <arrow-back-outline />
        </template>
        返回
      </n-button>
      <span style="font-size: 14px; font-weight: 500;">{{ file.name }}</span>
    </div>

    <!-- 内容区 -->
    <div style="flex: 1; overflow: auto; padding: 24px;">
      <!-- 预览区 -->
      <div
        style="
          display: flex;
          justify-content: center;
          align-items: center;
          margin-bottom: 24px;
          min-height: 200px;
          background: #fafafa;
          border-radius: 8px;
          padding: 16px;
        "
      >
        <n-spin :show="thumbnailLoading">
          <n-image
            v-if="thumbnailUrl && !thumbnailError"
            :src="thumbnailUrl"
            object-fit="contain"
            style="max-height: 400px; max-width: 100%;"
            show-toolbar-tooltip
          />
          <div v-else-if="thumbnailError" style="text-align: center; color: #999;">
            <construct-outline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">加载预览失败</p>
          </div>
          <div v-else-if="!thumbnailLoading" style="text-align: center; color: #999;">
            <document-text-outline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">{{ file.contentType || '未知类型' }}</p>
          </div>
        </n-spin>
      </div>

      <!-- 元数据 -->
      <n-descriptions bordered :column="2" label-placement="left">
        <n-descriptions-item label="文件名">{{ file.name }}</n-descriptions-item>
        <n-descriptions-item label="大小">{{ formatSize(file.size) }}</n-descriptions-item>
        <n-descriptions-item label="路径" :span="2">{{ file.path }}</n-descriptions-item>
        <n-descriptions-item label="MIME 类型">{{ file.contentType || '-' }}</n-descriptions-item>
        <n-descriptions-item label="修改时间">{{ formatDate(file.lastModified) }}</n-descriptions-item>
      </n-descriptions>

      <!-- 操作按钮 -->
      <n-space justify="center" style="margin-top: 24px;">
        <n-button @click="emit('download')">
          <template #icon><download-outline /></template>
          下载
        </n-button>
        <n-button @click="emit('rename')">
          <template #icon><create-outline /></template>
          重命名
        </n-button>
        <n-button @click="emit('move')">
          <template #icon><arrow-forward-outline /></template>
          移动
        </n-button>
        <n-button @click="emit('copy')">
          <template #icon><copy-outline /></template>
          复制
        </n-button>
        <n-button type="error" @click="emit('delete')">
          <template #icon><trash-outline /></template>
          删除
        </n-button>
      </n-space>
    </div>
  </div>
</template>

<style scoped></style>
