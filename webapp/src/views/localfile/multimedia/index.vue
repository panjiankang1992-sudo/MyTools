<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchGetDirectories } from '@/service/api/localfile';
import { useDirectoryScan } from '@/composables/localfile/useDirectoryScan';
import { useFileMaintenance } from '@/composables/localfile/useFileMaintenance';
import { useLoading } from '@sa/hooks';
import { useDialog, useMessage, NButton, NEmpty, NSpace } from 'naive-ui';
import MediaGallery from '../components/MediaGallery.vue';

defineOptions({ name: 'localfile_multimedia' });

const message = useMessage();
const dialog = useDialog();
const { startLoading, endLoading } = useLoading();

const directory = ref<any | null>(null);
const listKey = ref(0);
const { scanRunning, startScan } = useDirectoryScan(() => {
  listKey.value += 1;
});
const { maintenanceRunning, startMaintenance } = useFileMaintenance(() => {
  listKey.value += 1;
});

function confirmDedup() {
  if (!directory.value) return;
  dialog.warning({
    title: '确认MD5去重',
    content: '将隔离内容完全相同的重复文件。文件会移入隐藏隔离目录，可恢复。',
    positiveText: '开始执行',
    negativeText: '取消',
    onPositiveClick: () => startMaintenance(directory.value.id, 'EXACT_DEDUP')
  });
}

async function loadDirectory() {
  try {
    startLoading();
    const dirs: any = await fetchGetDirectories();
    console.log('[Multimedia] fetchGetDirectories result:', dirs);

    // 兼容处理：可能是数组，也可能是 { data: [] } 格式
    let dirArray: any[] = [];
    if (Array.isArray(dirs)) {
      dirArray = dirs;
    } else if (dirs && typeof dirs === 'object') {
      dirArray = dirs.data || dirs.list || [];
    }

    if (!dirArray || dirArray.length === 0) {
      console.warn('[Multimedia] 没有配置目录或目录列表为空');
      return;
    }

    const multimediaDir = dirArray.find((d: any) => d.directoryType === 'MULTIMEDIA');
    directory.value = multimediaDir || null;
    console.log('[Multimedia] 找到多媒体目录:', directory.value);
  } catch (error: any) {
    console.error('[Multimedia] 加载目录失败:', error);
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

onMounted(() => {
  loadDirectory();
});
</script>

<template>
  <div class="p-4">
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="text-xl font-bold">多媒体</h2>
        <p v-if="directory" class="text-gray-500 text-sm mt-1">{{ directory.directoryName }}</p>
        <p v-else class="text-gray-400 text-sm mt-1">未配置目录</p>
      </div>
      <NSpace v-if="directory">
        <NButton :loading="maintenanceRunning" @click="confirmDedup">MD5去重</NButton>
        <NButton size="large" type="primary" :loading="scanRunning" @click="handleScan">扫描目录</NButton>
      </NSpace>
    </div>

    <div v-if="directory">
      <MediaGallery :key="listKey" :directory-id="directory.id" :directory-path="directory.directoryPath" />
    </div>
    <NEmpty v-else description="请在配置文件中添加多媒体目录配置" class="py-12" />
  </div>
</template>

<style scoped>
.py-12 {
  padding-top: 48px;
  padding-bottom: 48px;
}
</style>
