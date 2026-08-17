<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useThumbnail } from '@/composables/cloudfile/useThumbnail';
import { fetchFileContent } from '@/service/api/cloudfile';
import { marked } from 'marked';
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

const textFileExts = ['txt', 'md', 'json', 'xml', 'html', 'htm', 'css', 'js', 'ts', 'jsx', 'tsx',
  'vue', 'py', 'java', 'c', 'cpp', 'h', 'sh', 'bash', 'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf',
  'properties', 'log', 'csv', 'sql', 'env', 'gitignore', 'editorconfig', 'rst', 'tex', 'svg'];
function isTextFile(name: string): boolean {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  return textFileExts.includes(ext);
}

function isJsonFile(name: string): boolean {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  return ext === 'json';
}

function isMarkdownFile(name: string): boolean {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  return ext === 'md';
}

function formatJsonContent(raw: string): string {
  try {
    const obj = JSON.parse(raw);
    return JSON.stringify(obj, null, 2);
  } catch {
    return raw;
  }
}

function highlightJson(raw: string): string {
  // Simple JSON syntax highlighting
  return raw.replace(
    /("(?:[^"\\]|\\.)*")\s*:/g,
    '<span style="color:#9cdcfe;">$1</span>:'
  ).replace(
    /:\s*("(?:[^"\\]|\\.)*")/g,
    ': <span style="color:#ce9178;">$1</span>'
  ).replace(
    /:\s*(true|false)/g,
    ': <span style="color:#569cd6;">$1</span>'
  ).replace(
    /:\s*(null)/g,
    ': <span style="color:#569cd6;">$1</span>'
  ).replace(
    /:\s*(-?\d+\.?\d*(?:[eE][+-]?\d+)?)/g,
    ': <span style="color:#b5cea8;">$1</span>'
  );
}

function renderMarkdown(raw: string): string {
  try {
    return marked.parse(raw) as string;
  } catch {
    return `<p style="color:#f48771;">Markdown 解析失败</p>`;
  }
}

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

const textContent = ref<string>('');
const textLoading = ref(false);
const textError = ref(false);

watch(
  () => props.file,
  async f => {
    textContent.value = '';
    textError.value = false;
    if (!f || f.isDirectory) return;
    if (!isTextFile(f.name)) return;
    textLoading.value = true;
    try {
      const { data, error } = await fetchFileContent(f.path);
      if (!error && data) {
        textContent.value = data;
      } else {
        textError.value = true;
      }
    } catch {
      textError.value = true;
    } finally {
      textLoading.value = false;
    }
  },
  { immediate: true }
);

const jsonFormatted = computed(() => {
  if (!textContent.value) return '';
  return formatJsonContent(textContent.value);
});

const jsonHighlighted = computed(() => {
  if (!jsonFormatted.value) return '';
  return highlightJson(jsonFormatted.value);
});

const markdownHtml = computed(() => {
  if (!textContent.value) return '';
  return renderMarkdown(textContent.value);
});

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
      <NButton size="small" @click="emit('back')">
        <template #icon>
          <ArrowBackOutline />
        </template>
        返回
      </NButton>
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
        <!-- JSON 格式化预览 -->
        <NSpin v-if="isJsonFile(file.name)" :show="textLoading">
          <div
            v-if="textContent && !textError"
            style="
              width: 100%;
              max-height: 500px;
              overflow: auto;
              background: #1e1e1e;
              color: #d4d4d4;
              border-radius: 8px;
              padding: 16px;
              text-align: left;
            "
          >
            <!-- eslint-disable vue/no-v-html -->
            <pre
              style="margin: 0; white-space: pre-wrap; word-break: break-all; font-size: 13px; line-height: 1.6; font-family: 'SF Mono', 'Menlo', 'Monaco', 'Consolas', monospace;"
              v-html="jsonHighlighted"
            /><!-- eslint-enable vue/no-v-html -->
          </div>
          <div v-else-if="textError" style="text-align: center; color: #999;">
            <ConstructOutline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">加载内容失败</p>
          </div>
        </NSpin>

        <!-- Markdown 预览 -->
        <NSpin v-else-if="isMarkdownFile(file.name)" :show="textLoading">
          <!-- eslint-disable vue/no-v-html -->
          <div
            v-if="textContent && !textError"
            class="markdown-preview"
            style="
              width: 100%;
              max-height: 500px;
              overflow: auto;
              background: #fff;
              color: #333;
              border-radius: 8px;
              padding: 24px;
              text-align: left;
              border: 1px solid #e8e8e8;
            "
            v-html="markdownHtml"
          /><!-- eslint-enable vue/no-v-html -->
          <div v-else-if="textError" style="text-align: center; color: #999;">
            <ConstructOutline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">加载内容失败</p>
          </div>
        </NSpin>

        <!-- 其他文本文件预览 -->
        <NSpin v-else-if="isTextFile(file.name)" :show="textLoading">
          <div
            v-if="textContent && !textError"
            style="
              width: 100%;
              max-height: 500px;
              overflow: auto;
              background: #1e1e1e;
              color: #d4d4d4;
              border-radius: 8px;
              padding: 16px;
              text-align: left;
            "
          >
            <pre style="margin: 0; white-space: pre-wrap; word-break: break-all; font-size: 13px; line-height: 1.6; font-family: 'SF Mono', 'Menlo', 'Monaco', 'Consolas', monospace;">{{ textContent }}</pre>
          </div>
          <div v-else-if="textError" style="text-align: center; color: #999;">
            <ConstructOutline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">加载内容失败</p>
          </div>
        </NSpin>

        <!-- 媒体文件预览 -->
        <NSpin v-else :show="thumbnailLoading">
          <NImage
            v-if="thumbnailUrl && !thumbnailError"
            :src="thumbnailUrl"
            object-fit="contain"
            style="max-height: 400px; max-width: 100%;"
            show-toolbar-tooltip
          />
          <div v-else-if="thumbnailError" style="text-align: center; color: #999;">
            <ConstructOutline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">加载预览失败</p>
          </div>
          <div v-else-if="!thumbnailLoading" style="text-align: center; color: #999;">
            <DocumentTextOutline :style="{ fontSize: '64px' }" />
            <p style="margin-top: 8px;">{{ file.contentType || '未知类型' }}</p>
          </div>
        </NSpin>
      </div>

      <!-- 元数据 -->
      <NDescriptions bordered :column="2" label-placement="left">
        <NDescriptionsItem label="文件名">{{ file.name }}</NDescriptionsItem>
        <NDescriptionsItem label="大小">{{ formatSize(file.size) }}</NDescriptionsItem>
        <NDescriptionsItem label="路径" :span="2">{{ file.path }}</NDescriptionsItem>
        <NDescriptionsItem label="MIME 类型">{{ file.contentType || '-' }}</NDescriptionsItem>
        <NDescriptionsItem label="修改时间">{{ formatDate(file.lastModified) }}</NDescriptionsItem>
      </NDescriptions>

      <!-- 操作按钮 -->
      <NSpace justify="center" style="margin-top: 24px;">
        <NButton @click="emit('download')">
          <template #icon><DownloadOutline /></template>
          下载
        </NButton>
        <NButton @click="emit('rename')">
          <template #icon><CreateOutline /></template>
          重命名
        </NButton>
        <NButton @click="emit('move')">
          <template #icon><ArrowForwardOutline /></template>
          移动
        </NButton>
        <NButton @click="emit('copy')">
          <template #icon><CopyOutline /></template>
          复制
        </NButton>
        <NButton type="error" @click="emit('delete')">
          <template #icon><TrashOutline /></template>
          删除
        </NButton>
      </NSpace>
    </div>
  </div>
</template>

<style scoped>
.markdown-preview :deep(h1) {
  font-size: 1.8em;
  font-weight: 700;
  margin: 0.5em 0 0.3em;
  padding-bottom: 0.3em;
  border-bottom: 1px solid #e8e8e8;
}
.markdown-preview :deep(h2) {
  font-size: 1.5em;
  font-weight: 700;
  margin: 0.5em 0 0.3em;
  padding-bottom: 0.25em;
  border-bottom: 1px solid #eee;
}
.markdown-preview :deep(h3) {
  font-size: 1.25em;
  font-weight: 600;
  margin: 0.4em 0 0.2em;
}
.markdown-preview :deep(h4) {
  font-size: 1.1em;
  font-weight: 600;
  margin: 0.3em 0 0.15em;
}
.markdown-preview :deep(p) {
  margin: 0.5em 0;
  line-height: 1.7;
}
.markdown-preview :deep(a) {
  color: #1890ff;
}
.markdown-preview :deep(code) {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 0.9em;
  color: #d63384;
}
.markdown-preview :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 16px;
  border-radius: 6px;
  overflow-x: auto;
  line-height: 1.5;
}
.markdown-preview :deep(pre code) {
  background: transparent;
  padding: 0;
  color: inherit;
  font-size: 13px;
}
.markdown-preview :deep(blockquote) {
  margin: 0.5em 0;
  padding: 8px 16px;
  border-left: 4px solid #1890ff;
  background: #f0f7ff;
  color: #555;
}
.markdown-preview :deep(ul),
.markdown-preview :deep(ol) {
  padding-left: 2em;
  line-height: 1.7;
}
.markdown-preview :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.8em 0;
}
.markdown-preview :deep(th),
.markdown-preview :deep(td) {
  border: 1px solid #ddd;
  padding: 8px 12px;
  text-align: left;
}
.markdown-preview :deep(th) {
  background: #f5f5f5;
  font-weight: 600;
}
.markdown-preview :deep(hr) {
  border: none;
  border-top: 1px solid #e8e8e8;
  margin: 1em 0;
}
.markdown-preview :deep(img) {
  max-width: 100%;
  border-radius: 4px;
}
</style>
