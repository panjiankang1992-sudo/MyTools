<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { NModal, NButton, NSpace, NSpin, useMessage } from 'naive-ui';
import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

// Configure Monaco environment for workers
self.MonacoEnvironment = {
  getWorker(_: string, _label: string) {
    return new editorWorker();
  }
};

const props = defineProps<{
  show: boolean;
  file: { path: string; name: string; content: string } | null;
  loading: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:show', val: boolean): void;
  (e: 'saved'): void;
}>();

const message = useMessage();

const editorContainerRef = ref<HTMLDivElement | null>(null);
let editorInstance: monaco.editor.IStandaloneCodeEditor | null = null;

function detectLanguage(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  const map: Record<string, string> = {
    md: 'markdown',
    txt: 'plainText',
    json: 'json',
    xml: 'xml',
    html: 'html',
    css: 'css',
    js: 'javascript',
    ts: 'typescript',
    vue: 'html',
    py: 'python',
    java: 'java',
    c: 'c',
    cpp: 'cpp',
    h: 'c',
    sh: 'shell',
    yaml: 'yaml',
    yml: 'yaml',
    properties: 'plainText',
    jsx: 'javascript',
    tsx: 'typescript',
  };
  return map[ext] || 'plainText';
}

function initEditor() {
  if (!editorContainerRef.value || !props.file) return;

  // Destroy existing instance
  if (editorInstance) {
    editorInstance.dispose();
    editorInstance = null;
  }

  editorInstance = monaco.editor.create(editorContainerRef.value, {
    value: props.file.content || '',
    language: detectLanguage(props.file.name),
    theme: 'vs-light',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    lineNumbers: 'on',
    scrollBeyondLastLine: false,
  });
}

function destroyEditor() {
  if (editorInstance) {
    editorInstance.dispose();
    editorInstance = null;
  }
}

onMounted(() => {
  initEditor();
});

onBeforeUnmount(() => {
  destroyEditor();
});

watch(
  () => props.file,
  () => {
    if (props.show && props.file) {
      // Small delay to ensure container is rendered
      setTimeout(() => initEditor(), 50);
    }
  }
);

watch(
  () => props.show,
  (val) => {
    if (!val) {
      destroyEditor();
    } else if (props.file) {
      setTimeout(() => initEditor(), 50);
    }
  }
);

const saving = ref(false);

async function handleSave() {
  if (!props.file || !editorInstance) return;
  saving.value = true;
  try {
    const content = editorInstance.getValue();
    const resp = await fetch(
      `/api/cloud/text-file?path=${encodeURIComponent(props.file.path)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain' },
        body: content,
      }
    );
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`);
    }
    message.success('保存成功');
    emit('saved');
    emit('update:show', false);
  } catch {
    message.error('保存失败');
  } finally {
    saving.value = false;
  }
}

function handleCancel() {
  if (!editorInstance) {
    emit('update:show', false);
    return;
  }
  const current = editorInstance.getValue();
  const original = props.file?.content || '';
  if (current !== original) {
    const confirmed = window.confirm('内容已修改，关闭将丢失更改，确定关闭吗？');
    if (!confirmed) return;
  }
  emit('update:show', false);
}

// Format file size for display
function formatSize(bytes: number): string {
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`编辑: ${file?.name ?? ''}`"
    style="width: 900px; max-width: 95vw;"
    :mask-closable="false"
    @update:show="(val) => emit('update:show', val)"
  >
    <n-spin :show="loading" description="加载文件内容...">
      <!-- Monaco Editor container -->
      <div
        ref="editorContainerRef"
        style="height: 500px; border: 1px solid #ddd; border-radius: 4px; overflow: hidden;"
      />

      <!-- Status bar -->
      <div
        v-if="file"
        style="
          display: flex;
          align-items: center;
          gap: 16px;
          padding: 4px 8px;
          font-size: 12px;
          color: #888;
          border: 1px solid #ddd;
          border-top: none;
          border-radius: 0 0 4px 4px;
          background: #fafafa;
        "
      >
        <span>语言: {{ detectLanguage(file.name) }}</span>
      </div>
    </n-spin>

    <template #footer>
      <n-space justify="end">
        <n-button @click="handleCancel">取消</n-button>
        <n-button type="primary" :loading="saving" :disabled="!file" @click="handleSave">
          保存
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>
