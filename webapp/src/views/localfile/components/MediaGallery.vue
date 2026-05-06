<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { fetchGetFilePage, fetchGetFileContent, fetchRetagFile, fetchGetFileTags } from '@/service/api/localfile';
import { useLoading } from '@sa/hooks';
import { NImage, NButton, NSpace, NEmpty, NSpin, NModal, useMessage, NTag } from 'naive-ui';

defineOptions({ name: 'MediaGallery' });

const props = defineProps<{
  directoryId: number;
}>();

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

interface FileItem {
  id: number;
  fileName: string;
  relativePath: string;
  fileType: string;
  thumbnailUrl: string;
  fileSize: number;
  tags: { id: number; name: string; color: string }[];
  semanticTags?: { tag: string; score: number }[];
}

const files = ref<FileItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(50);

// 预览相关
const previewVisible = ref(false);
const previewFile = ref<FileItem | null>(null);

// 打标签相关
const taggingFileId = ref<number | null>(null);

async function loadFiles() {
  if (!props.directoryId) return;

  try {
    startLoading();
    const result = await fetchGetFilePage({
      directoryId: props.directoryId,
      page: page.value,
      pageSize: pageSize.value
    });
    files.value = result.list || [];
    total.value = result.total || 0;

    // 加载语义标签
    await loadSemanticTags();
  } catch (error) {
    message.error('加载文件失败');
    console.error(error);
  } finally {
    endLoading();
  }
}

// 加载语义标签
async function loadSemanticTags() {
  for (const file of files.value) {
    try {
      const tags = await fetchGetFileTags(file.id);
      file.semanticTags = tags || [];
    } catch {
      file.semanticTags = [];
    }
  }
}

async function handlePreview(file: FileItem) {
  try {
    const blob = await fetchGetFileContent(file.id);
    const url = URL.createObjectURL(blob);
    previewFile.value = { ...file, semanticTags: file.semanticTags || [] };
    previewVisible.value = true;
  } catch (error) {
    message.error('预览失败');
    console.error(error);
  }
}

function handleClosePreview() {
  if (previewFile.value) {
    URL.revokeObjectURL((previewFile.value as any).url || '');
  }
  previewVisible.value = false;
  previewFile.value = null;
}

// 重新打标签
async function handleRetag(file: FileItem, event?: Event) {
  if (event) {
    event.stopPropagation();
  }

  if (taggingFileId.value === file.id) {
    message.warning('正在打标签中，请稍候');
    return;
  }

  try {
    taggingFileId.value = file.id;
    const result = await fetchRetagFile(file.id);
    file.semanticTags = result.tags || [];

    // 更新预览弹窗中的文件信息
    if (previewFile.value && previewFile.value.id === file.id) {
      previewFile.value.semanticTags = result.tags || [];
    }

    message.success('打标签成功');
  } catch (error: any) {
    message.error(error?.message || '打标签失败');
    console.error(error);
  } finally {
    taggingFileId.value = null;
  }
}

function formatFileSize(size: number): string {
  if (size < 1024) return size + ' B';
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
  return (size / (1024 * 1024)).toFixed(1) + ' MB';
}

onMounted(() => {
  loadFiles();
});

watch(() => props.directoryId, () => {
  page.value = 1;
  loadFiles();
});
</script>

<template>
  <div class="media-gallery">
    <NSpin :show="loading">
      <div v-if="files.length > 0" class="gallery-grid">
        <div
          v-for="file in files"
          :key="file.id"
          class="gallery-item"
          @click="handlePreview(file)"
        >
          <div class="thumbnail-wrapper">
            <NImage
              v-if="file.fileType === 'IMAGE' && file.thumbnailUrl"
              :src="file.thumbnailUrl"
              object-fit="cover"
              class="thumbnail"
              preview-src=""
            />
            <div v-else class="file-icon">
              <span class="icon-text">{{ file.fileName.split('.').pop()?.toUpperCase() || 'FILE' }}</span>
            </div>
            <!-- 打标签按钮 -->
            <div class="tag-button-wrapper">
              <NButton
                size="tiny"
                :loading="taggingFileId === file.id"
                @click="handleRetag(file, $event)"
                class="tag-button"
              >
                {{ taggingFileId === file.id ? '打标签中...' : '打标签' }}
              </NButton>
            </div>
          </div>
          <div class="file-info">
            <p class="file-name" :title="file.fileName">{{ file.fileName }}</p>
            <p class="file-size">{{ formatFileSize(file.fileSize) }}</p>
            <!-- 语义标签展示 -->
            <div v-if="file.semanticTags && file.semanticTags.length > 0" class="semantic-tags">
              <NTag
                v-for="(tag, index) in file.semanticTags.slice(0, 3)"
                :key="index"
                size="small"
                type="info"
                class="semantic-tag"
              >
                {{ tag.tag }}
              </NTag>
              <span v-if="file.semanticTags.length > 3" class="more-tags">
                +{{ file.semanticTags.length - 3 }}
              </span>
            </div>
          </div>
        </div>
      </div>
      <NEmpty v-else description="暂无文件" />
    </NSpin>

    <!-- 预览弹窗 -->
    <NModal
      v-model:show="previewVisible"
      preset="card"
      :title="previewFile?.fileName"
      style="width: 90%; max-width: 1200px;"
      @after-leave="handleClosePreview"
    >
      <template #header-extra>
        <NButton
          size="small"
          type="primary"
          :loading="taggingFileId === previewFile?.id"
          @click="handleRetag(previewFile!)"
        >
          {{ taggingFileId === previewFile?.id ? '打标签中...' : '重新打标签' }}
        </NButton>
      </template>
      <div class="preview-content">
        <img
          v-if="previewFile && previewFile.fileType === 'IMAGE'"
          :src="(previewFile as any).url"
          :alt="previewFile.fileName"
          style="max-width: 100%; max-height: 60vh; object-fit: contain;"
        />
        <video
          v-else-if="previewFile && previewFile.fileType === 'VIDEO'"
          :src="(previewFile as any).url"
          controls
          style="max-width: 100%; max-height: 60vh;"
        />
        <audio
          v-else-if="previewFile && previewFile.fileType === 'AUDIO'"
          :src="(previewFile as any).url"
          controls
          style="width: 100%;"
        />
        <div v-else-if="previewFile" class="preview-unsupported">
          <p>该文件类型暂不支持预览</p>
          <p class="file-path">{{ previewFile.absolutePath }}</p>
        </div>
      </div>
      <!-- 文件信息 -->
      <div v-if="previewFile" class="file-detail">
        <div class="detail-row">
          <span class="detail-label">文件名：</span>
          <span class="detail-value">{{ previewFile.fileName }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">文件大小：</span>
          <span class="detail-value">{{ formatFileSize(previewFile.fileSize) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">文件类型：</span>
          <span class="detail-value">{{ previewFile.fileType }}</span>
        </div>
        <!-- 语义标签展示 -->
        <div class="detail-row tags-row">
          <span class="detail-label">语义标签：</span>
          <div class="detail-tags">
            <NTag
              v-for="(tag, index) in previewFile.semanticTags"
              :key="index"
              size="small"
              type="info"
              class="detail-tag"
            >
              {{ tag.tag }} <span class="tag-score">{{ (tag.score * 100).toFixed(0) }}%</span>
            </NTag>
            <span v-if="!previewFile.semanticTags || previewFile.semanticTags.length === 0" class="no-tags">
              暂无标签
            </span>
          </div>
        </div>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.media-gallery {
  min-height: 400px;
}

.gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
  padding: 8px;
}

.gallery-item {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  background: var(--n-card-color);
  transition: transform 0.2s, box-shadow 0.2s;
}

.gallery-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.thumbnail-wrapper {
  width: 100%;
  height: 160px;
  overflow: hidden;
  background: var(--n-border-color);
  position: relative;
}

.thumbnail {
  width: 100%;
  height: 100%;
}

.file-icon {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
}

.icon-text {
  font-size: 24px;
  font-weight: bold;
  color: #999;
}

.tag-button-wrapper {
  position: absolute;
  bottom: 8px;
  right: 8px;
  opacity: 0;
  transition: opacity 0.2s;
}

.gallery-item:hover .tag-button-wrapper {
  opacity: 1;
}

.tag-button {
  background: rgba(0, 0, 0, 0.6);
  color: white;
  border: none;
}

.file-info {
  padding: 8px 12px;
}

.file-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0;
}

.file-size {
  font-size: 12px;
  color: var(--n-text-color-3);
  margin: 4px 0 0;
}

.semantic-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.semantic-tag {
  font-size: 10px;
}

.more-tags {
  font-size: 10px;
  color: var(--n-text-color-3);
  line-height: 22px;
}

.preview-content {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 300px;
  background: var(--n-card-color);
  border-radius: 8px;
}

.preview-unsupported {
  text-align: center;
  color: var(--n-text-color-3);
}

.file-path {
  font-size: 12px;
  color: var(--n-text-color-2);
  word-break: break-all;
  margin-top: 8px;
}

.file-detail {
  margin-top: 16px;
  padding: 16px;
  background: var(--n-card-color);
  border-radius: 8px;
}

.detail-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 12px;
}

.detail-row:last-child {
  margin-bottom: 0;
}

.detail-label {
  font-size: 14px;
  color: var(--n-text-color-3);
  min-width: 80px;
}

.detail-value {
  font-size: 14px;
  color: var(--n-text-color-1);
}

.tags-row {
  align-items: flex-start;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-tag {
  font-size: 12px;
}

.tag-score {
  font-size: 10px;
  opacity: 0.7;
  margin-left: 4px;
}

.no-tags {
  font-size: 14px;
  color: var(--n-text-color-3);
}
</style>
