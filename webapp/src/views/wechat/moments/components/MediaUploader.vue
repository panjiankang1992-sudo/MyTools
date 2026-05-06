<script setup lang="ts">
import { ref } from 'vue';
import { NUpload, NButton, NIcon } from 'naive-ui';
import type { UploadFileInfo } from 'naive-ui';
import { fetchUploadMedia } from '@/service/api/moments';

interface Props {
  modelValue: string[];
}

const props = defineProps<Props>();
const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

const fileList = ref<UploadFileInfo[]>([]);

async function handleUpload(options: { file: UploadFileInfo; event?: Event }) {
  const file = options.file.file;
  if (!file) return;
  try {
    const result = await fetchUploadMedia(file as File);
    const newUrl = result.data?.url || result.data?.filename;
    if (newUrl) {
      const newUrls = [...props.modelValue, newUrl];
      emit('update:modelValue', newUrls);
    }
  } catch (error) {
    console.error('Upload failed:', error);
  }
}

function removeFile(index: number) {
  const newUrls = props.modelValue.filter((_, i) => i !== index);
  emit('update:modelValue', newUrls);
}
</script>

<template>
  <div class="media-upload">
    <div class="uploaded-list">
      <div v-for="(url, index) in modelValue" :key="index" class="uploaded-item">
        <img v-if="url.match(/\.(jpg|jpeg|png|gif|webp)$/i)" :src="url" alt="preview" />
        <video v-else-if="url.match(/\.(mp4|avi|mov)$/i)" :src="url" controls />
        <span class="file-name">{{ url.split('/').pop() }}</span>
        <NButton size="tiny" type="error" @click="removeFile(index)">删除</NButton>
      </div>
    </div>
    <NUpload
      :multiple="true"
      :max="9"
      accept="image/*,video/*"
      :file-list="fileList"
      @change="handleUpload"
      class="upload-btn"
    >
      <NButton>上传文件</NButton>
    </NUpload>
  </div>
</template>

<style scoped>
.media-upload {
  margin-top: 8px;
}

.uploaded-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.uploaded-item {
  position: relative;
  width: 100px;
  height: 100px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.uploaded-item img,
.uploaded-item video {
  width: 100%;
  height: 80px;
  object-fit: cover;
}

.uploaded-item .file-name {
  font-size: 10px;
  padding: 2px;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: 100%;
}

.upload-btn {
  margin-top: 8px;
}
</style>