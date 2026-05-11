<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchGetDirectories, fetchScanDirectory } from '@/service/api/localfile';
import { useLoading } from '@sa/hooks';
import { useMessage, NButton, NEmpty } from 'naive-ui';
import MediaGallery from '../components/MediaGallery.vue';

defineOptions({ name: 'LocalFileMultimedia' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

const directory = ref<any | null>(null);

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

  try {
    startLoading();
    const result = await fetchScanDirectory(directory.value.id, true);
    message.success(`扫描完成：共扫描 ${result.scannedCount} 个文件，新增 ${result.newCount} 个`);
  } catch (error) {
    message.error('扫描失败');
    console.error(error);
  } finally {
    endLoading();
  }
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
      <NButton
        v-if="directory"
        size="large"
        type="primary"
        :loading="loading"
        @click="handleScan"
      >
        扫描目录
      </NButton>
    </div>

    <div v-if="directory">
      <MediaGallery :directory-id="directory.id" />
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
