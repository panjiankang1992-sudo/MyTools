<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchGetDirectories } from '@/service/api/localfile';
import { useDirectoryScan } from '@/composables/localfile/useDirectoryScan';
import { useFileMaintenance } from '@/composables/localfile/useFileMaintenance';
import { useLoading } from '@sa/hooks';
import { useDialog, useMessage, NButton, NEmpty, NSpace } from 'naive-ui';
import EbookList from '../components/EbookList.vue';

defineOptions({ name: 'localfile_ebook' });

const message = useMessage();
const dialog = useDialog();
const { startLoading, endLoading } = useLoading();

const directory = ref<any | null>(null);
const listKey = ref(0);
const { scanRunning, startScan } = useDirectoryScan(() => {
  listKey.value += 1;
});
const { maintenanceRunning, maintenanceProgress, startMaintenance } = useFileMaintenance(() => {
  listKey.value += 1;
});

async function loadDirectory() {
  try {
    startLoading();
    const dirs: any = await fetchGetDirectories();
    console.log('[Ebook] fetchGetDirectories result:', dirs);

    // 兼容处理：可能是数组，也可能是 { data: [] } 格式
    let dirArray: any[] = [];
    if (Array.isArray(dirs)) {
      dirArray = dirs;
    } else if (dirs && typeof dirs === 'object') {
      dirArray = dirs.data || dirs.list || [];
    }

    if (!dirArray || dirArray.length === 0) {
      console.warn('[Ebook] 没有配置目录或目录列表为空');
      return;
    }

    const ebookDir = dirArray.find((d: any) => d.directoryType === 'EBOOK');
    directory.value = ebookDir || null;
    console.log('[Ebook] 找到电子书目录:', directory.value);
  } catch (error: any) {
    console.error('[Ebook] 加载目录失败:', error);
    message.error('加载目录失败');
  } finally {
    endLoading();
  }
}

async function handleScan() {
  if (!directory.value) {
    message.warning('目录未配置');
    return;
  }

  await startScan(directory.value.id);
}

function confirmMaintenance(mode: 'EXACT_DEDUP' | 'EBOOK_ORGANIZE') {
  if (!directory.value) return;
  const smart = mode === 'EBOOK_ORGANIZE';
  dialog.warning({
    title: smart ? '确认智能整理电子书' : '确认MD5去重',
    content: smart
      ? '将隔离完全重复和模型判定为被较完整版本包含的电子书，并使用模型净化文件名。文件会移入隐藏隔离目录，可恢复。'
      : '将计算MD5并隔离内容完全相同的重复文件。文件会移入隐藏隔离目录，可恢复。',
    positiveText: '开始执行',
    negativeText: '取消',
    onPositiveClick: () => startMaintenance(directory.value.id, mode)
  });
}

onMounted(() => {
  loadDirectory();
});
</script>

<template>
  <div class="p-4">
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="text-xl font-bold">电子书</h2>
        <p v-if="directory" class="text-gray-500 text-sm mt-1">{{ directory.directoryName }}</p>
        <p v-else class="text-gray-400 text-sm mt-1">未配置目录</p>
      </div>
      <NSpace v-if="directory">
        <NButton :loading="maintenanceRunning" @click="confirmMaintenance('EXACT_DEDUP')">MD5去重</NButton>
        <NButton type="warning" :loading="maintenanceRunning" @click="confirmMaintenance('EBOOK_ORGANIZE')">智能整理</NButton>
        <NButton size="large" type="primary" :loading="scanRunning" @click="handleScan">扫描目录</NButton>
      </NSpace>
    </div>

    <div v-if="maintenanceProgress" class="mb-3 text-sm text-gray-500">{{ maintenanceProgress }}</div>

    <div v-if="directory">
      <EbookList :key="listKey" :directory-id="directory.id" />
    </div>
    <NEmpty v-else description="请在配置文件中添加电子书目录配置" class="py-12" />
  </div>
</template>

<style scoped>
.py-12 {
  padding-top: 48px;
  padding-bottom: 48px;
}
</style>
