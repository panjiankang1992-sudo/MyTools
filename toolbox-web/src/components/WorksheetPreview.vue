<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { renderPdfPage } from '../lib/pdf-runtime';
import { worksheetText as t } from '../locales/worksheet';
import type { WorksheetArtifact } from '../types';

const props = defineProps<{
  artifact: WorksheetArtifact | null;
  page: number;
  zoom: 'fit' | 'actual';
  busy: boolean;
  error: string;
}>();
const emit = defineEmits<{ retry: []; select: [] }>();
const canvas = ref<HTMLCanvasElement | null>(null);
const rendering = ref(false);
const renderError = ref(false);
let renderVersion = 0;

/** 将同一份导出文件渲染到纸张预览，缩放不参与分页。 */
async function refreshCanvas() {
  const version = ++renderVersion;
  const artifact = props.artifact;
  renderError.value = false;
  if (!artifact) { rendering.value = false; return; }
  rendering.value = true;
  try {
    // 离屏绘制避免过期任务覆盖最新页面或争用同一个画布。
    const buffer = document.createElement('canvas');
    await renderPdfPage(artifact.bytes, buffer, props.page, 1.6);
    await nextTick();
    if (version !== renderVersion || !canvas.value) return;
    canvas.value.width = buffer.width;
    canvas.value.height = buffer.height;
    canvas.value.getContext('2d')?.drawImage(buffer, 0, 0);
  } catch {
    if (version === renderVersion) renderError.value = true;
  } finally {
    if (version === renderVersion) rendering.value = false;
  }
}
watch(() => [props.artifact, props.page], refreshCanvas, { immediate: true });
onBeforeUnmount(() => { renderVersion += 1; });
</script>

<template>
  <div class="paper-viewport" :class="{ 'actual-size': zoom === 'actual' }" :aria-busy="busy || rendering">
    <div class="paper-sheet" :class="{ 'empty-sheet': !artifact }">
      <canvas v-if="artifact" ref="canvas" :aria-label="t.preview" />
      <div v-else-if="!busy && !error" class="empty-state">
        <div class="empty-sheet-icon"><span /><span /><span /></div>
        <strong>{{ t.emptyTitle }}</strong>
        <p>{{ t.emptyHint }}</p>
        <NButton secondary type="primary" @click="emit('select')">{{ t.chooseEntries }}</NButton>
      </div>
    </div>
    <div v-if="busy || rendering" class="paper-overlay" role="status">
      <NSpin size="small" />
      <strong>{{ t.updating }}</strong>
      <span>{{ t.loadingFonts }}</span>
    </div>
    <div v-else-if="error || renderError" class="paper-overlay error-state" role="alert">
      <strong>{{ t.failed }}</strong>
      <span>{{ error || t.previewFailure }}</span>
      <NButton type="primary" secondary @click="error ? emit('retry') : refreshCanvas()">{{ t.retry }}</NButton>
    </div>
  </div>
</template>

<style scoped>
.paper-viewport { position: relative; min-width: 0; overflow: auto; padding: 24px 30px; min-height: 470px; background: #eaf0f5; border-radius: 0 0 9px 9px; }
.paper-sheet { width: min(100%, 650px); margin: 0 auto; background: white; box-shadow: 0 3px 16px #25364a24; aspect-ratio: 210 / 297; }
.paper-sheet canvas { display: block; width: 100%; height: auto; }
.actual-size .paper-sheet { width: 794px; max-width: none; }
.empty-sheet { display: grid; place-items: center; }
.empty-state { padding: 28px; text-align: center; color: #718096; max-width: 340px; }
.empty-state strong { display: block; color: #42526b; margin-top: 24px; font-size: 18px; }
.empty-state p { font-size: 13px; line-height: 1.8; margin-bottom: 20px; }
.empty-sheet-icon { margin: 0 auto; width: 52px; height: 67px; border: 2px solid #d6dfeb; border-radius: 6px; padding: 15px 10px; }
.empty-sheet-icon span { height: 2px; margin-bottom: 9px; display: block; background: #d6dfeb; }
.paper-overlay { position: absolute; inset: 0; min-height: 300px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; background: #f0f4f9dc; padding: 24px; text-align: center; backdrop-filter: blur(2px); }
.paper-overlay strong { color: #354460; }
.paper-overlay span { color: #708098; font-size: 13px; }
.error-state span { max-width: 330px; }
@media (max-width: 767px) {
  .paper-viewport { padding: 16px 12px; min-height: 370px; }
  .paper-sheet { width: 100%; }
  .empty-state { padding: 18px; }
  .empty-state strong { font-size: 16px; }
}
</style>
