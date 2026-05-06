<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { fetchGetDirectories, fetchGetFilePage, fetchScanDirectory } from '@/service/api/localfile';
import { useLoading } from '@sa/hooks';
import { NButton, NSpace, NCard, NTabs, NTabPane, NSpin, useMessage } from 'naive-ui';
import MediaGallery from './components/MediaGallery.vue';
import EbookList from './components/EbookList.vue';
import LargeMediaList from './components/LargeMediaList.vue';

defineOptions({ name: 'LocalFileManagement' });

const message = useMessage();
const { loading, startLoading, endLoading } = useLoading();

// 目录类型
const DIRECTORY_TYPES = {
  MULTIMEDIA: 'MULTIMEDIA',
  EBOOK: 'EBOOK',
  LARGE_MEDIA: 'LARGE_MEDIA'
};

// 目录映射
const directories = ref<{
  multimedia: any | null;
  ebook: any | null;
  largeMedia: any | null;
}>({
  multimedia: null,
  ebook: null,
  largeMedia: null
});

// 当前选中的标签页
const activeTab = ref('multimedia');

// 文件列表数据
const fileList = ref<any[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

// 加载目录列表
async function loadDirectories() {
  try {
    startLoading();
    const dirs = await fetchGetDirectories();

    // 按类型分类目录
    directories.value.multimedia = dirs.find((d: any) => d.directoryType === DIRECTORY_TYPES.MULTIMEDIA) || null;
    directories.value.ebook = dirs.find((d: any) => d.directoryType === DIRECTORY_TYPES.EBOOK) || null;
    directories.value.largeMedia = dirs.find((d: any) => d.directoryType === DIRECTORY_TYPES.LARGE_MEDIA) || null;
  } catch (error) {
    message.error('加载目录失败');
    console.error(error);
  } finally {
    endLoading();
  }
}

// 扫描目录
async function handleScan(dirType: string) {
  const dir = directories.value[dirType as keyof typeof directories.value];
  if (!dir) {
    message.warning('目录未配置');
    return;
  }

  try {
    startLoading();
    const result = await fetchScanDirectory(dir.id, true);
    message.success(`扫描完成：共扫描 ${result.scannedCount} 个文件，新增 ${result.newCount} 个`);
  } catch (error) {
    message.error('扫描失败');
    console.error(error);
  } finally {
    endLoading();
  }
}

// 加载文件列表
async function loadFileList(dirType: string) {
  const dir = directories.value[dirType as keyof typeof directories.value];
  if (!dir) return;

  try {
    startLoading();
    const result = await fetchGetFilePage({
      directoryId: dir.id,
      page: page.value,
      pageSize: pageSize.value
    });
    fileList.value = result.list || [];
    total.value = result.total || 0;
  } catch (error) {
    message.error('加载文件列表失败');
    console.error(error);
  } finally {
    endLoading();
  }
}

// 获取当前目录
const currentDirectory = computed(() => {
  return directories.value[activeTab.value as keyof typeof directories.value];
});

onMounted(() => {
  loadDirectories();
});
</script>

<template>
  <div class="local-file-management">
    <NCard title="文件管理" :bordered="false" class="mb-4">
      <template #header-extra>
        <NSpace>
          <NButton
            size="small"
            type="primary"
            @click="handleScan(activeTab)"
            :loading="loading"
          >
            扫描当前目录
          </NButton>
        </NSpace>
      </template>

      <NTabs v-model:value="activeTab" type="line" @update:value="loadFileList">
        <NTabPane name="multimedia" tab="多媒体">
          <template #description>
            <span v-if="directories.multimedia">{{ directories.multimedia.directoryName }}</span>
            <span v-else class="text-gray-400">未配置</span>
          </template>
        </NTabPane>
        <NTabPane name="ebook" tab="Ebook">
          <template #description>
            <span v-if="directories.ebook">{{ directories.ebook.directoryName }}</span>
            <span v-else class="text-gray-400">未配置</span>
          </template>
        </NTabPane>
        <NTabPane name="largeMedia" tab="大文件多媒体">
          <template #description>
            <span v-if="directories.largeMedia">{{ directories.largeMedia.directoryName }}</span>
            <span v-else class="text-gray-400">未配置</span>
          </template>
        </NTabPane>
      </NTabs>

      <div class="mt-4">
        <NSpin :show="loading">
          <!-- 多媒体：网格展示 -->
          <MediaGallery
            v-if="activeTab === 'multimedia' && currentDirectory"
            :directory-id="currentDirectory.id"
          />

          <!-- Ebook：列表展示 -->
          <EbookList
            v-else-if="activeTab === 'ebook' && currentDirectory"
            :directory-id="currentDirectory.id"
          />

          <!-- 大文件多媒体：列表展示 -->
          <LargeMediaList
            v-else-if="activeTab === 'largeMedia' && currentDirectory"
            :directory-id="currentDirectory.id"
          />

          <!-- 未配置目录 -->
          <div v-else class="empty-state">
            <n-empty description="该目录未配置">
              <template #extra>
                <p class="text-sm text-gray-500">
                  请在配置文件中添加目录配置
                </p>
              </template>
            </n-empty>
          </div>
        </NSpin>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.local-file-management {
  padding: 16px;
}

.mb-4 {
  margin-bottom: 16px;
}

.mt-4 {
  margin-top: 16px;
}

.text-gray-400 {
  color: var(--n-text-color-3);
}

.text-sm {
  font-size: 14px;
}

.text-gray-500 {
  color: var(--n-text-color-2);
}

.empty-state {
  padding: 60px 0;
  text-align: center;
}
</style>
