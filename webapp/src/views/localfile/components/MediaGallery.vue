<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  fetchGetFileContent,
  fetchGetFileFilters,
  fetchGetFilePage,
  fetchGetFileTags,
  fetchGetCachedFileThumbnail,
  fetchRetagFile
} from '@/service/api/localfile';
import { getAuthenticatedFileContentUrl } from '@/service/api/localfile';
import { NButton, NEmpty, NIcon, NModal, NSelect, NSpin, NTag, useMessage } from 'naive-ui';
import { FolderOpenOutline, ImageOutline, MusicalNotesOutline, VideocamOutline } from '@vicons/ionicons5';

defineOptions({ name: 'MediaGallery' });

const props = defineProps<{
  directoryId: number;
  directoryPath: string;
}>();

interface SemanticTag {
  tag: string;
  score: number;
}

interface FileItem {
  id: number;
  fileName: string;
  filePath: string;
  relativePath: string;
  directoryName: string;
  fileType: string;
  fileSize: number;
  thumbnailUrl: string;
  previewUrl?: string;
  semanticTags?: SemanticTag[];
}

const message = useMessage();
const files = ref<FileItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 24;
const loading = ref(false);
const loadingMore = ref(false);
const loadMoreSentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const previewVisible = ref(false);
const previewFile = ref<FileItem | null>(null);
const previewAspectRatio = ref<number | null>(null);
const taggingFileId = ref<number | null>(null);
const selectedDirectory = ref<string | null>(null);
const selectedTag = ref<string | null>(null);
const selectedFileType = ref<string | null>(null);
const directoryOptions = ref<{ label: string; value: string }[]>([]);
const tagOptions = ref<{ label: string; value: string }[]>([]);
const fileTypeOptions = [
  { label: '图片', value: 'IMAGE' },
  { label: '视频', value: 'VIDEO' },
  { label: '音频', value: 'AUDIO' }
];

const hasMore = computed(() => files.value.length < total.value);
const previewModalStyle = computed(() => {
  if (!previewAspectRatio.value || previewFile.value?.fileType === 'AUDIO') {
    return { width: 'min(92vw, 720px)' };
  }

  // 根据媒体原始比例计算弹窗宽度，同时预留标题、标签和视口边距。
  const maxMediaHeight = Math.max(window.innerHeight * 0.68, 240);
  const mediaWidth = Math.min(window.innerWidth * 0.88, maxMediaHeight * previewAspectRatio.value);
  return { width: `${Math.max(mediaWidth + 48, 320)}px`, maxWidth: '92vw' };
});
const groupedFiles = computed(() => {
  const groups = new Map<string, FileItem[]>();
  files.value.forEach(file => {
    const items = groups.get(file.directoryName) || [];
    items.push(file);
    groups.set(file.directoryName, items);
  });
  return Array.from(groups, ([directoryName, items]) => ({ directoryName, items }))
    .sort((left, right) => compareDirectoryNames(left.directoryName, right.directoryName));
});

function getDateDirectoryKey(directoryName: string): string | null {
  const dateSegments = directoryName.split('/').filter(segment => /^\d{6,8}$/.test(segment));
  return dateSegments.length > 0 ? dateSegments.join('') : null;
}

function compareDirectoryNames(left: string, right: string): number {
  const leftDateKey = getDateDirectoryKey(left);
  const rightDateKey = getDateDirectoryKey(right);
  if (leftDateKey && rightDateKey) return rightDateKey.localeCompare(leftDateKey);
  if (leftDateKey) return -1;
  if (rightDateKey) return 1;
  return left.localeCompare(right, 'zh-CN');
}

function normalizeFile(item: any): FileItem {
  const filePath = item.filePath || '';
  const root = props.directoryPath.replace(/\/+$/, '');
  const relativePath = filePath.startsWith(`${root}/`) ? filePath.slice(root.length + 1) : filePath;
  const separator = relativePath.lastIndexOf('/');
  return {
    ...item,
    fileName: item.fileName || item.filename,
    filePath,
    relativePath,
    directoryName: separator > -1 ? relativePath.slice(0, separator) : '根目录',
    fileType: getMediaType(item.mimeType),
    thumbnailUrl: '',
    semanticTags: (item.tags || []).map((tag: any) => ({
      tag: tag.tag || tag.tagName,
      score: tag.score ?? tag.confidence ?? 0
    }))
  };
}

async function loadFiles(reset = false) {
  if (!props.directoryId || loading.value || loadingMore.value) return;
  if (reset) {
    revokeObjectUrls();
    files.value = [];
    total.value = 0;
    page.value = 1;
    loading.value = true;
  } else {
    if (!hasMore.value) return;
    loadingMore.value = true;
  }

  try {
    const { data } = await fetchGetFilePage({
      directoryId: props.directoryId,
      subdirectory: selectedDirectory.value || undefined,
      tagName: selectedTag.value || undefined,
      fileType: selectedFileType.value || undefined,
      page: page.value,
      pageSize
    });
    const incoming = (data?.list || []).map(normalizeFile);
    files.value.push(...incoming);
    total.value = data?.total || 0;
    if (incoming.length > 0) page.value += 1;
    void loadThumbnails(incoming);
  } catch (error) {
    message.error('加载文件失败');
    console.error(error);
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

async function loadThumbnails(items: FileItem[]) {
  const images = items.filter(item => item.fileType === 'IMAGE' || item.fileType === 'VIDEO');
  if (images.length === 0) return;

  // 先展示文件卡片，再逐张替换缩略图，避免整批完成后同时出现。
  await nextTick();
  let cursor = 0;
  // 并发请求缩略图，每个请求完成后独立更新对应卡片，不等待整批完成。
  const workerCount = Math.min(6, images.length);

  async function loadNextImage() {
    while (cursor < images.length) {
      const file = images[cursor];
      cursor += 1;
      try {
        const blob = await fetchGetCachedFileThumbnail(file.id);
        // 必须更新列表中的 Vue 响应式代理，修改分页返回的原始对象不会触发 DOM 刷新。
        const currentFile = files.value.find(item => item.id === file.id);
        if (blob && currentFile) {
          if (currentFile.thumbnailUrl) URL.revokeObjectURL(currentFile.thumbnailUrl);
          currentFile.thumbnailUrl = URL.createObjectURL(blob);
        }
      } catch {
        // 筛选切换后旧请求可能才返回，仅清理当前仍存在的卡片。
        const currentFile = files.value.find(item => item.id === file.id);
        if (currentFile) currentFile.thumbnailUrl = '';
      }

      // 主动让出一帧，使已完成的缩略图立即显示。
      await nextTick();
      await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    }
  }

  await Promise.all(Array.from({ length: workerCount }, () => loadNextImage()));
}

async function loadFilterOptions() {
  const { data } = await fetchGetFileFilters(props.directoryId);
  directoryOptions.value = [...(data?.directories || [])]
    .sort(compareDirectoryNames)
    .map(value => ({
      label: value || '根目录',
      value
    }));
  tagOptions.value = (data?.tags || []).map(value => ({ label: value, value }));
}

async function handlePreview(file: FileItem) {
  try {
    previewAspectRatio.value = null;
    const isStreamMedia = file.fileType === 'VIDEO' || file.fileType === 'AUDIO';
    const contentPromise = isStreamMedia ? Promise.resolve(null) : fetchGetFileContent(file.id);
    const [contentResponse, { data: tags }] = await Promise.all([contentPromise, fetchGetFileTags(file.id)]);
    const blob = contentResponse?.data;
    if (!isStreamMedia && !blob) return;
    const normalizedTags = (tags || []).map((tag: any) => ({
      tag: tag.tag || tag.tagName,
      score: tag.score ?? tag.confidence ?? 0
    }));
    previewFile.value = {
      ...file,
      previewUrl: isStreamMedia ? getAuthenticatedFileContentUrl(file.id) : URL.createObjectURL(blob!),
      semanticTags: normalizedTags
    };
    previewVisible.value = true;
  } catch (error) {
    message.error('预览失败');
    console.error(error);
  }
}

function handleClosePreview() {
  if (previewFile.value?.previewUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(previewFile.value.previewUrl);
  }
  previewFile.value = null;
  previewAspectRatio.value = null;
}

function handleImageLoaded(event: Event) {
  const image = event.currentTarget as HTMLImageElement;
  if (image.naturalWidth > 0 && image.naturalHeight > 0) {
    previewAspectRatio.value = image.naturalWidth / image.naturalHeight;
  }
}

function handleVideoLoaded(event: Event) {
  const video = event.currentTarget as HTMLVideoElement;
  if (video.videoWidth > 0 && video.videoHeight > 0) {
    previewAspectRatio.value = video.videoWidth / video.videoHeight;
  }
}

async function handleRetag(file: FileItem, event?: Event) {
  event?.stopPropagation();
  if (taggingFileId.value === file.id) return;
  try {
    taggingFileId.value = file.id;
    const { data } = await fetchRetagFile(file.id);
    const tags = (data || []).map((tag: any) => ({
      tag: tag.tag || tag.tagName,
      score: tag.score ?? tag.confidence ?? 0
    }));
    file.semanticTags = tags;
    if (previewFile.value?.id === file.id) previewFile.value.semanticTags = tags;
    message.success('打标签成功');
  } catch (error: any) {
    message.error(error?.message || '打标签失败');
  } finally {
    taggingFileId.value = null;
  }
}

async function handleTagFilter(tagName: string, event?: Event, closePreview = false) {
  event?.stopPropagation();
  if (closePreview) previewVisible.value = false;
  if (selectedTag.value === tagName) {
    await loadFiles(true);
    return;
  }
  selectedTag.value = tagName;
}

function getMediaType(mimeType?: string): string {
  if (mimeType?.startsWith('image/')) return 'IMAGE';
  if (mimeType?.startsWith('video/')) return 'VIDEO';
  if (mimeType?.startsWith('audio/')) return 'AUDIO';
  return 'FILE';
}

function mediaIcon(fileType: string) {
  if (fileType === 'VIDEO') return VideocamOutline;
  if (fileType === 'AUDIO') return MusicalNotesOutline;
  return ImageOutline;
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function revokeObjectUrls() {
  files.value.forEach(file => {
    if (file.thumbnailUrl) URL.revokeObjectURL(file.thumbnailUrl);
  });
}

function setupObserver() {
  observer?.disconnect();
  observer = new IntersectionObserver(entries => {
    if (entries[0]?.isIntersecting && hasMore.value) void loadFiles();
  }, { rootMargin: '500px 0px' });
  if (loadMoreSentinel.value) observer.observe(loadMoreSentinel.value);
}

onMounted(async () => {
  await loadFilterOptions();
  await loadFiles(true);
  await nextTick();
  setupObserver();
});

watch(() => [props.directoryId, props.directoryPath], async () => {
  selectedDirectory.value = null;
  selectedTag.value = null;
  selectedFileType.value = null;
  await loadFilterOptions();
  await loadFiles(true);
  await nextTick();
  setupObserver();
});

watch(loadMoreSentinel, setupObserver);

watch([selectedDirectory, selectedTag, selectedFileType], async () => {
  await loadFiles(true);
  await nextTick();
  setupObserver();
});

onBeforeUnmount(() => {
  observer?.disconnect();
  revokeObjectUrls();
  handleClosePreview();
});
</script>

<template>
  <div class="media-gallery">
    <div class="filter-bar">
      <NSelect
        v-model:value="selectedDirectory"
        :options="directoryOptions"
        clearable
        placeholder="全部目录"
      />
      <NSelect v-model:value="selectedTag" :options="tagOptions" clearable placeholder="全部标签" />
      <NSelect
        v-model:value="selectedFileType"
        :options="fileTypeOptions"
        clearable
        placeholder="全部类型"
      />
    </div>
    <NSpin :show="loading">
      <section v-for="group in groupedFiles" :key="group.directoryName" class="directory-section">
        <header class="directory-header">
          <NIcon :component="FolderOpenOutline" :size="21" color="#64748b" />
          <h3>{{ group.directoryName }}</h3>
          <span>{{ group.items.length }} 项</span>
        </header>

        <div class="gallery-grid">
          <article
            v-for="file in group.items"
            :key="file.id"
            class="gallery-item"
            tabindex="0"
            @click="handlePreview(file)"
            @keyup.enter="handlePreview(file)"
          >
            <div class="thumbnail-wrapper">
              <img
                v-if="file.thumbnailUrl"
                :src="file.thumbnailUrl"
                :alt="file.fileName"
                class="thumbnail"
                loading="lazy"
                @error="file.thumbnailUrl = ''"
              />
              <div v-else class="thumbnail-placeholder">
                <NIcon :component="mediaIcon(file.fileType)" :size="42" color="#94a3b8" />
                <span>{{ file.fileName.split('.').pop()?.toUpperCase() || 'FILE' }}</span>
              </div>
            </div>
            <div class="file-info">
              <p class="file-name" :title="file.fileName">{{ file.fileName }}</p>
              <p class="file-size">{{ formatFileSize(file.fileSize) }}</p>
              <div v-if="file.semanticTags?.length" class="file-tags">
                <NTag
                  v-for="tag in file.semanticTags.slice(0, 3)"
                  :key="tag.tag"
                  size="tiny"
                  type="info"
                  :bordered="false"
                  class="clickable-tag"
                  @click="handleTagFilter(tag.tag, $event)"
                >
                  {{ tag.tag }}
                </NTag>
              </div>
            </div>
          </article>
        </div>
      </section>

      <NEmpty v-if="!loading && files.length === 0" description="暂无文件" class="empty-state" />
      <div ref="loadMoreSentinel" class="load-more-sentinel">
        <NSpin v-if="loadingMore" size="small" />
        <span v-else-if="!hasMore && files.length > 0">已加载全部 {{ total }} 个文件</span>
      </div>
    </NSpin>

    <NModal
      v-model:show="previewVisible"
      preset="card"
      :title="previewFile?.fileName"
      class="preview-modal"
      :style="previewModalStyle"
      @after-leave="handleClosePreview"
    >
      <template #header-extra>
        <NButton
          v-if="previewFile"
          size="small"
          type="primary"
          :loading="taggingFileId === previewFile.id"
          @click="handleRetag(previewFile)"
        >
          重新打标签
        </NButton>
      </template>
      <div v-if="previewFile" class="preview-content">
        <img
          v-if="previewFile.fileType === 'IMAGE'"
          :src="previewFile.previewUrl"
          :alt="previewFile.fileName"
          @load="handleImageLoaded"
        />
        <video
          v-else-if="previewFile.fileType === 'VIDEO'"
          :src="previewFile.previewUrl"
          controls
          @loadedmetadata="handleVideoLoaded"
        />
        <audio v-else-if="previewFile.fileType === 'AUDIO'" :src="previewFile.previewUrl" controls />
        <div v-else class="preview-unsupported">该文件类型暂不支持预览</div>
      </div>
      <div v-if="previewFile?.semanticTags?.length" class="preview-tags">
        <NTag
          v-for="tag in previewFile.semanticTags"
          :key="tag.tag"
          size="small"
          type="info"
          class="clickable-tag"
          @click="handleTagFilter(tag.tag, $event, true)"
        >
          {{ tag.tag }} {{ (tag.score * 100).toFixed(0) }}%
        </NTag>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.media-gallery {
  min-height: 420px;
}

.filter-bar {
  display: grid;
  grid-template-columns: repeat(3, minmax(160px, 240px));
  gap: 12px;
  margin-bottom: 20px;
}

.directory-section + .directory-section {
  margin-top: 32px;
}

.directory-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgb(226 232 240 / 80%);
}

.directory-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: #1e293b;
  font-size: 16px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.directory-header span {
  color: #94a3b8;
  font-size: 12px;
}

.gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 22px 18px;
}

.gallery-item {
  min-width: 0;
  cursor: pointer;
  outline: none;
}

.thumbnail-wrapper {
  position: relative;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  border: 1px solid #edf0f4;
  border-radius: 12px;
  background: #f5f6f8;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.gallery-item:hover .thumbnail-wrapper,
.gallery-item:focus-visible .thumbnail-wrapper {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgb(15 23 42 / 10%);
}

.thumbnail {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumbnail-placeholder {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #94a3b8;
  font-size: 14px;
  font-weight: 600;
}

.file-info {
  padding: 10px 4px 0;
}

.file-name,
.file-size {
  margin: 0;
}

.file-tags {
  display: flex;
  min-height: 20px;
  margin-top: 7px;
  flex-wrap: wrap;
  gap: 4px;
}

.clickable-tag {
  cursor: pointer;
}

.clickable-tag:hover {
  filter: brightness(0.94);
}

.file-name {
  overflow: hidden;
  color: #20242c;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  margin-top: 4px;
  color: #77808f;
  font-size: 12px;
}

.load-more-sentinel {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
}

.empty-state {
  padding: 80px 0;
}

.preview-modal {
  transition: width 160ms ease;
}

.preview-content {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
}

.preview-content img,
.preview-content video {
  display: block;
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 68vh;
  object-fit: contain;
}

.preview-content audio {
  width: 100%;
}

.preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

@media (max-width: 640px) {
  .filter-bar {
    grid-template-columns: 1fr;
  }

  .gallery-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 10px;
  }

  .thumbnail-wrapper {
    border-radius: 9px;
  }
}
</style>
