<script setup lang="ts">
import { ref, onMounted, watch, h } from 'vue';
import { fetchGetFilePage, fetchGetFileContent } from '@/service/api/localfile';
import { useLoading } from '@sa/hooks';
import { NDataTable, NButton, NSpace, NEmpty, NSpin, NTag, NImage, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';

defineOptions({ name: 'LargeMediaList' });

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
  fileSize: number;
  thumbnailUrl: string;
  tags: { id: number; name: string; color: string }[];
  createTime: string;
}

const files = ref<FileItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

const columns: DataTableColumns<FileItem> = [
  {
    title: '序号',
    key: 'index',
    width: 60,
    render: (_: any, index: number) => index + 1
  },
  {
    title: '缩略图',
    key: 'thumbnailUrl',
    width: 80,
    render: (row) => {
      if (row.fileType === 'IMAGE' && row.thumbnailUrl) {
        return h(NImage, {
          src: row.thumbnailUrl,
          width: 60,
          height: 60,
          style: 'object-fit: cover; border-radius: 4px;'
        });
      }
      return h('div', {
        style: 'width: 60px; height: 60px; background: #f5f5f5; border-radius: 4px; display: flex; align-items: center; justify-content: center;'
      }, row.fileType?.charAt(0) || 'F');
    }
  },
  {
    title: '文件名',
    key: 'fileName',
    ellipsis: true,
    render: (row) => h('span', { title: row.fileName }, row.fileName)
  },
  {
    title: '大小',
    key: 'fileSize',
    width: 100,
    render: (row) => formatFileSize(row.fileSize)
  },
  {
    title: '类型',
    key: 'fileType',
    width: 80
  },
  {
    title: '标签',
    key: 'tags',
    width: 150,
    render: (row) => {
      if (!row.tags || row.tags.length === 0) return '-';
      return h(NSpace, { size: 4 }, () =>
        row.tags.map(tag =>
          h(NTag, { size: 'small', type: 'info' }, () => tag.name)
        )
      );
    }
  },
  {
    title: '创建时间',
    key: 'createTime',
    width: 160,
    render: (row) => row.createTime?.split('T')[0] || '-'
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h(NSpace, null, () => [
        h(NButton, { size: 'small', onClick: () => handlePlay(row) }, () => '播放'),
        h(NButton, { size: 'small', onClick: () => handleDownload(row) }, () => '下载')
      ])
  }
];

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
  } catch (error) {
    message.error('加载文件失败');
    console.error(error);
  } finally {
    endLoading();
  }
}

async function handlePlay(file: FileItem) {
  try {
    const blob = await fetchGetFileContent(file.id);
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
  } catch (error) {
    message.error('播放失败');
    console.error(error);
  }
}

async function handleDownload(file: FileItem) {
  try {
    const blob = await fetchGetFileContent(file.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.fileName;
    a.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    message.error('下载失败');
    console.error(error);
  }
}

function formatFileSize(size: number): string {
  if (size < 1024) return size + ' B';
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
  if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB';
  return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function handlePageChange(newPage: number) {
  page.value = newPage;
  loadFiles();
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
  <div class="large-media-list">
    <NSpin :show="loading">
      <NDataTable
        v-if="files.length > 0"
        :columns="columns"
        :data="files"
        :pagination="{
          page: page,
          pageSize: pageSize,
          total: total,
          onUpdatePage: handlePageChange
        }"
        :bordered="false"
        :row-key="(row: FileItem) => row.id"
      />
      <NEmpty v-else description="暂无文件" />
    </NSpin>
  </div>
</template>

<style scoped>
.large-media-list {
  min-height: 400px;
}
</style>
