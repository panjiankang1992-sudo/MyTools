<script setup lang="ts">
import { ref, onMounted, watch, h } from 'vue';
import { fetchGetFilePage, fetchGetFileContent } from '@/service/api/localfile';
import { useLoading } from '@sa/hooks';
import { NDataTable, NButton, NSpace, NEmpty, NSpin, NTag, NAvatar, useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';

defineOptions({ name: 'EbookList' });

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
    width: 80,
    render: (row) => {
      const typeMap: Record<string, string> = {
        EPUB: 'EPUB',
        PDF: 'PDF',
        MOBI: 'MOBI',
        TXT: 'TXT'
      };
      return typeMap[row.fileType] || row.fileType;
    }
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
    title: '操作',
    key: 'actions',
    width: 120,
    render: (row) =>
      h(NSpace, null, () => [
        h(NButton, { size: 'small', onClick: () => handleRead(row) }, () => '阅读'),
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

async function handleRead(file: FileItem) {
  try {
    const blob = await fetchGetFileContent(file.id);
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
  } catch (error) {
    message.error('打开失败');
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
  return (size / (1024 * 1024)).toFixed(1) + ' MB';
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
  <div class="ebook-list">
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
.ebook-list {
  min-height: 400px;
}
</style>
