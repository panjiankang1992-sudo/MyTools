<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NButton, NCheckbox, NIcon, NInput, NInputNumber, NSelect, NSpin, NTag } from 'naive-ui';
import { ArrowDownOutline, ArrowUpOutline, CheckmarkCircleOutline, CloseOutline, CloudUploadOutline, DocumentTextOutline, DownloadOutline, GridOutline, ImagesOutline, LayersOutline, LockClosedOutline, CutOutline, CodeSlashOutline, TextOutline } from '@vicons/ionicons5';
import { PDFDocument } from 'pdf-lib';
import { recordEntryClick } from '../lib/analytics';
import { downloadBlob, releasePdf, renderPdfPage } from '../lib/pdf-runtime';
import { createSamplePdf, PDF_LIMITS, PdfToolError, pdfToolIds, validateFiles, validateImageBytes, zipPdfOutputs, type PdfOutput, type PdfToolId, type PdfToolOptions, type PdfToolResult } from '../lib/pdf-tools';
import { pdfMessages as t } from '../locales/pdf';
import { appConfig } from '../config/env';
import { text as commonText } from '../locales/common';
import { createPdfAdapter, pdfToolId, type PdfExecutionProgress } from '../services/pdf-adapter';

const route = useRoute();
const router = useRouter();
// 路由参数优先，兼容旧的 /pdf?tool=xxx 入口并归一化为稳定子路由。
const routeTool = String(route.params.toolId ?? route.query.tool ?? '').replace(/^pdf\./u, '');
const initialTool: PdfToolId = pdfToolIds.includes(routeTool as PdfToolId) ? routeTool as PdfToolId : 'images';
const options = reactive<PdfToolOptions>({ tool: initialTool, files: [], content: '', pages: '', splitMode: 'chunks', groupRanges: '1\n2', chunkSize: 1, direction: 'vertical', ratio: 50, reverse: false, imageFormat: 'png', dpi: 144, paper: 'a4', landscape: false, margin: 15 });
const icons = { images: ImagesOutline, text: TextOutline, markdown: CodeSlashOutline, merge: LayersOutline, split: DocumentTextOutline, crop: CutOutline, render: GridOutline, extract: DocumentTextOutline };
const groups = t.groups.map((title, group) => ({ title, tools: pdfToolIds.filter(tool => t.tools[tool].group === group) }));
const metadata = computed(() => t.tools[options.tool]);
// 处理位置必须在开始前明确展示，本机处理不会上传文件。
const executionNotice = computed(() => appConfig.pdfExecution === 'server' ? commonText.execServer : `${commonText.execLocal} · ${t.privacy}`);
const executionTag = computed(() => appConfig.pdfExecution === 'server' ? commonText.execServer : t.local);
const isText = computed(() => ['text', 'markdown'].includes(options.tool));
const isMultiple = computed(() => ['images', 'merge'].includes(options.tool));
const showPaper = computed(() => ['images', 'text', 'markdown'].includes(options.tool));
const showRange = computed(() => ['crop', 'render', 'extract'].includes(options.tool));
const accepted = computed(() => options.tool === 'images' ? '.png,.jpg,.jpeg' : isText.value ? '.txt,.md' : '.pdf');
const fileInput = ref<HTMLInputElement>();
const dragActive = ref(false);
const busy = ref(false);
const checking = ref(false);
// 执行位置由适配器决定，本机处理不会上传文件。
const adapter = createPdfAdapter();
const phase = ref<'idle' | PdfExecutionProgress['phase']>('idle');
const error = ref('');
const result = ref<PdfToolResult>();
const stale = ref(false);
const selectedResult = ref(0);
const canvas = ref<HTMLCanvasElement>();
const previewPage = ref(1);
const previewCount = ref(1);
const previewBusy = ref(false);
const previewError = ref(false);
const previewBytes = ref<Uint8Array>();
const imageUrl = ref('');
const copied = ref(false);
const zipBusy = ref(false);
const pageCounts = new WeakMap<File, number>();
const imagePixels = new WeakMap<File, number>();
const currentOutput = computed(() => result.value?.files[selectedResult.value]);
const currentIsPdf = computed(() => currentOutput.value?.blob.type === 'application/pdf');
const currentIsImage = computed(() => currentOutput.value?.blob.type.startsWith('image/'));
const paperOptions = ['A4', 'A5', 'Letter'].map(label => ({ label, value: label.toLowerCase() }));
const formatOptions = ['PNG', 'JPG'].map(label => ({ label, value: label.toLowerCase() }));
const splitOptions = [{ label: t.chunks, value: 'chunks' }, { label: t.ranges, value: 'ranges' }];
const directionOptions = [{ label: t.vertical, value: 'vertical' }, { label: t.horizontal, value: 'horizontal' }];
let previewVersion = 0;

function formatSize(size: number): string { return size < 1024 * 1024 ? `${Math.max(1, Math.round(size / 1024))} KiB` : `${(size / 1024 / 1024).toFixed(1)} MiB`; }
function errorText(value: unknown): string { return value instanceof PdfToolError ? t.errors[value.code] : t.errors.generic; }
function clearResult(): void {
  previewVersion++;
  if (previewBytes.value) releasePdf(previewBytes.value);
  result.value = undefined; stale.value = false; previewBytes.value = undefined; previewBusy.value = false;
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = '';
}
function switchTool(tool: PdfToolId): void {
  if (busy.value || checking.value || tool === options.tool) return;
  const previousIsImage = options.tool === 'images';
  options.tool = tool;
  // 相同类型的文件可继续使用；图片与 PDF 切换时清除不兼容输入。
  if (previousIsImage !== (tool === 'images') || isText.value) options.files = [];
  error.value = ''; clearResult();
  recordEntryClick('pdf', pdfToolId(tool));
  if (route.params.toolId) void router.replace({ path: `/pdf/${tool}` });
  else void router.replace({ query: { ...route.query, tool } });
}
watch([() => route.params.toolId, () => route.query.tool], ([param, query]) => {
  const value = String(param ?? query ?? '').replace(/^pdf\./u, '');
  if (pdfToolIds.includes(value as PdfToolId)) switchTool(value as PdfToolId);
});
watch(options, () => { if (result.value) stale.value = true; copied.value = false; }, { deep: true });

async function acceptFiles(incoming: File[]): Promise<void> {
  if (busy.value || checking.value || !incoming.length) return;
  error.value = ''; checking.value = true;
  try {
    if (isText.value) {
      const file = incoming[0]!;
      if (file.size > PDF_LIMITS.textBytes) throw new PdfToolError('contentLimit');
      if (!/\.(txt|md)$/i.test(file.name)) throw new PdfToolError('fileType');
      options.content = await file.text();
      return;
    }
    const files = isMultiple.value ? [...options.files, ...incoming] : incoming;
    if (!isMultiple.value && files.length !== 1) throw new PdfToolError('fileRequired');
    validateFiles(files, options.tool === 'images');
    if (options.tool === 'images') {
      for (const file of incoming) imagePixels.set(file, validateImageBytes(new Uint8Array(await file.arrayBuffer())));
      if (files.reduce((total, file) => total + (imagePixels.get(file) ?? 0), 0) > PDF_LIMITS.totalImagePixels) throw new PdfToolError('imageLimit');
    } else {
      for (const file of incoming) {
        let document: PDFDocument;
        try { document = await PDFDocument.load(await file.arrayBuffer()); } catch { throw new PdfToolError('unsupportedPdf'); }
        if (document.getPageCount() > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
        if (document.getForm().getFields().length) throw new PdfToolError('unsupportedPdf');
        pageCounts.set(file, document.getPageCount());
      }
      if (options.tool === 'merge' && files.reduce((total, file) => total + (pageCounts.get(file) ?? 0), 0) > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
    }
    options.files = files;
  } catch (value) { error.value = errorText(value); }
  finally { checking.value = false; }
}
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  void acceptFiles(Array.from(input.files ?? []));
  input.value = '';
}
function onDrop(event: DragEvent): void { dragActive.value = false; void acceptFiles(Array.from(event.dataTransfer?.files ?? [])); }
function moveFile(index: number, offset: number): void {
  const files = [...options.files];
  const moved = files.splice(index, 1)[0];
  if (moved) { files.splice(index + offset, 0, moved); options.files = files; }
}
async function useSample(): Promise<void> {
  if (isText.value) { options.content = options.tool === 'markdown' ? t.sampleMarkdown : t.sampleText; return; }
  if (busy.value || checking.value) return;
  try {
    let files: File[];
    if (options.tool === 'images') {
      const sample = document.createElement('canvas'); sample.width = 800; sample.height = 560;
      const context = sample.getContext('2d')!;
      context.fillStyle = '#e5f1ed'; context.fillRect(0, 0, 800, 560);
      context.fillStyle = '#2e7771'; context.beginPath(); context.arc(600, 175, 90, 0, Math.PI * 2); context.fill();
      context.fillStyle = '#134743'; context.font = 'bold 56px sans-serif'; context.fillText('A little simpler.', 65, 360);
      context.font = '24px sans-serif'; context.fillText('YOUR EVERYDAY TOOLBOX', 68, 420);
      const blob = await new Promise<Blob>((resolve, reject) => sample.toBlob(value => value ? resolve(value) : reject(), 'image/png'));
      files = [new File([blob], 'sample.png', { type: 'image/png' })];
    } else {
      const bytes = await createSamplePdf();
      files = [new File([Uint8Array.from(bytes)], 'sample-a.pdf', { type: 'application/pdf' })];
      if (options.tool === 'merge') files.push(new File([Uint8Array.from(bytes)], 'sample-b.pdf', { type: 'application/pdf' }));
    }
    options.files = [];
    await acceptFiles(files);
  } catch (value) { error.value = errorText(value); }
}
async function execute(): Promise<void> {
  error.value = ''; busy.value = true; clearResult();
  try {
    // 本次任务使用独立参数快照，后续编辑不改变执行中的任务。
    const output = await adapter.execute(
      { toolId: pdfToolId(options.tool), options: { ...options, files: [...options.files] } },
      progress => { phase.value = progress.phase; }
    );
    result.value = { files: output.files, text: output.text };
    stale.value = false; selectedResult.value = 0; previewPage.value = 1;
    // PDF 成功使用只由服务端最终任务确认，本机处理不向全站统计上报成功事件。
    await preparePreview();
  } catch (value) {
    error.value = value instanceof Error && value.message === 'PDF_CANCELLED' ? t.processing : errorText(value);
  }
  finally { busy.value = false; phase.value = 'idle'; }
}

/** 取消尚未完成的本机处理；已完成的结果不受影响。 */
function cancel(): void {
  adapter.cancel();
}
async function preparePreview(): Promise<void> {
  const version = ++previewVersion;
  if (previewBytes.value) releasePdf(previewBytes.value);
  previewBytes.value = undefined; previewPage.value = 1; previewCount.value = 1; previewError.value = false;
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = '';
  const output = currentOutput.value;
  if (!output) return;
  if (output.blob.type.startsWith('image/')) imageUrl.value = URL.createObjectURL(output.blob);
  if (output.blob.type === 'application/pdf') {
    previewBusy.value = true;
    const bytes = new Uint8Array(await output.blob.arrayBuffer());
    if (version !== previewVersion) return;
    previewBytes.value = bytes;
    await drawPreview();
  }
}
async function drawPreview(): Promise<void> {
  if (!previewBytes.value) return;
  const version = previewVersion;
  previewBusy.value = true; previewError.value = false;
  await nextTick();
  try {
    if (!canvas.value) return;
    const rendered = await renderPdfPage(previewBytes.value, canvas.value, previewPage.value, 0.85);
    if (version === previewVersion) previewCount.value = rendered.pageCount;
  } catch { if (version === previewVersion) previewError.value = true; }
  finally { if (version === previewVersion) previewBusy.value = false; }
}
function showResult(index: number): void { selectedResult.value = index; void preparePreview(); }
function changePage(offset: number): void { previewPage.value += offset; void drawPreview(); }
function download(output: PdfOutput): void { if (!stale.value) downloadBlob(output.blob, output.name); }
async function downloadZip(): Promise<void> {
  if (!result.value || stale.value) return;
  zipBusy.value = true;
  try { downloadBlob(await zipPdfOutputs(result.value.files), 'toolbox-results.zip'); }
  catch (value) { error.value = errorText(value); }
  finally { zipBusy.value = false; }
}
async function copyText(): Promise<void> {
  try { await navigator.clipboard.writeText(result.value?.text ?? ''); copied.value = true; }
  catch { error.value = t.copyFailed; }
}
onBeforeUnmount(clearResult);
</script>

<template>
  <div class="pdf-page">
    <header class="pdf-heading">
      <div><p class="eyebrow">DOCUMENT STUDIO</p><h1>{{ t.title }}</h1><p class="page-subtitle">{{ t.subtitle }}</p></div>
      <NTag :bordered="false" round class="local-tag"><NIcon :component="LockClosedOutline" /> {{ executionTag }}</NTag>
    </header>
    <section class="tool-groups" :aria-label="t.title">
      <div v-for="group in groups" :key="group.title" class="tool-group">
        <p class="group-title">{{ group.title }}</p>
        <div class="tool-grid">
          <button v-for="tool in group.tools" :key="tool" class="tool-card" :class="{ active: options.tool === tool }" :aria-pressed="options.tool === tool" :disabled="busy || checking" @click="switchTool(tool)">
            <span class="tool-icon"><NIcon :component="icons[tool]" :size="22" /></span>
            <span class="tool-copy"><strong>{{ t.tools[tool].title }}</strong><span>{{ t.tools[tool].description }}</span><small>{{ t.tools[tool].format }}</small></span>
            <span class="active-dot" aria-hidden="true" />
          </button>
        </div>
      </div>
    </section>

    <div class="workbench-title"><div><h2>{{ metadata.title }}</h2><p>{{ executionNotice }}</p></div><NButton quaternary :disabled="busy || checking" @click="useSample">{{ t.sample }} <span aria-hidden="true">↗</span></NButton></div>
    <div class="workbench">
      <div class="editor-column">
        <section class="studio-panel">
          <h3><span class="step">01</span>{{ t.input }}</h3>
          <input ref="fileInput" class="file-input" type="file" :accept="accepted" :multiple="isMultiple" :disabled="busy || checking" :aria-label="t.select" @change="onFileChange">
          <template v-if="isText">
            <NInput v-model:value="options.content" type="textarea" :disabled="busy || checking" :autosize="{ minRows: 8, maxRows: 16 }" :placeholder="options.tool === 'markdown' ? t.markdownPlaceholder : t.textPlaceholder" :aria-label="metadata.title" />
            <div class="text-import"><span class="helper">{{ t.textLimit }}</span><NButton size="small" secondary :disabled="busy || checking" @click="fileInput?.click()">{{ t.select }}</NButton></div>
            <p v-if="options.tool === 'markdown'" class="helper note">{{ t.markdownNote }}</p>
          </template>
          <template v-else>
            <div class="drop-zone" :class="{ dragging: dragActive }" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="onDrop">
              <NIcon :component="CloudUploadOutline" :size="34" /><strong>{{ t.drop }}</strong><span>{{ t.or }}</span>
              <NButton type="primary" secondary :loading="checking" :disabled="busy" @click="fileInput?.click()">{{ options.files.length ? isMultiple ? t.add : t.replace : t.select }}</NButton>
            </div>
            <p class="helper">{{ t.limit }}</p>
            <ol v-if="options.files.length" class="file-list">
              <li v-for="(file, index) in options.files" :key="`${file.name}-${index}`">
                <span class="file-order">{{ String(index + 1).padStart(2, '0') }}</span>
                <div class="file-detail"><strong :title="file.name">{{ file.name }}</strong><small>{{ formatSize(file.size) }}<template v-if="pageCounts.get(file)"> · {{ pageCounts.get(file) }} {{ t.pageUnit }}</template></small></div>
                <div class="file-actions">
                  <NButton v-if="isMultiple" quaternary circle :disabled="busy || checking || index === 0" :aria-label="t.moveUp" @click="moveFile(index, -1)"><NIcon :component="ArrowUpOutline" /></NButton>
                  <NButton v-if="isMultiple" quaternary circle :disabled="busy || checking || index === options.files.length - 1" :aria-label="t.moveDown" @click="moveFile(index, 1)"><NIcon :component="ArrowDownOutline" /></NButton>
                  <NButton quaternary circle :disabled="busy || checking" :aria-label="t.remove" @click="options.files.splice(index, 1)"><NIcon :component="CloseOutline" /></NButton>
                </div>
              </li>
            </ol>
          </template>
        </section>
        <section class="studio-panel settings-panel">
          <h3><span class="step">02</span>{{ t.settings }}</h3>
          <div v-if="showPaper" class="settings-grid">
            <label class="field"><span>{{ t.paper }}</span><NSelect v-model:value="options.paper" :options="paperOptions" :disabled="busy" :aria-label="t.paper" /></label>
            <label class="field"><span>{{ t.margin }}</span><NInputNumber :value="options.margin" :min="5" :max="35" :disabled="busy" :aria-label="t.margin" @update:value="options.margin = $event ?? 15" /></label>
            <NCheckbox v-model:checked="options.landscape" :disabled="busy">{{ t.landscape }}</NCheckbox>
          </div>
          <template v-if="options.tool === 'split'">
            <label class="field"><span>{{ t.splitMode }}</span><NSelect v-model:value="options.splitMode" :options="splitOptions" :disabled="busy" :aria-label="t.splitMode" /></label>
            <label v-if="options.splitMode === 'chunks'" class="field"><span>{{ t.chunkSize }}</span><NInputNumber :value="options.chunkSize" :min="1" :max="100" :precision="0" :disabled="busy" :aria-label="t.chunkSize" @update:value="options.chunkSize = $event ?? 1" /></label>
            <label v-else class="field"><span>{{ t.groupRanges }}</span><NInput v-model:value="options.groupRanges" type="textarea" :disabled="busy" placeholder="1-2&#10;3, 5" :aria-label="t.groupRanges" /><small>{{ t.groupHint }}</small></label>
          </template>
          <label v-if="showRange" class="field"><span>{{ t.pages }}</span><NInput v-model:value="options.pages" :disabled="busy" placeholder="1-3, 5" :aria-label="t.pages" /><small>{{ t.pagesHint }}</small></label>
          <template v-if="options.tool === 'crop'">
            <div class="settings-grid"><label class="field"><span>{{ t.direction }}</span><NSelect v-model:value="options.direction" :options="directionOptions" :disabled="busy" :aria-label="t.direction" /></label><label class="field"><span>{{ t.ratio }}</span><NInputNumber :value="options.ratio" :min="5" :max="95" :disabled="busy" :aria-label="t.ratio" @update:value="options.ratio = $event ?? 50" /></label></div>
            <div class="cut-diagram" :class="options.direction" :style="{ '--ratio': `${options.ratio}%` }"><span>{{ options.reverse ? t.second : t.first }}</span><span>{{ options.reverse ? t.first : t.second }}</span></div>
            <NCheckbox v-model:checked="options.reverse" :disabled="busy">{{ t.reverse }}</NCheckbox><p class="helper">{{ t.cropNote }}</p>
          </template>
          <template v-if="options.tool === 'render'">
            <div class="settings-grid"><label class="field"><span>{{ t.format }}</span><NSelect v-model:value="options.imageFormat" :options="formatOptions" :disabled="busy" :aria-label="t.format" /></label><label class="field"><span>{{ t.dpi }}</span><NInputNumber :value="options.dpi" :min="72" :max="300" :disabled="busy" :aria-label="t.dpi" @update:value="options.dpi = $event ?? 144" /></label></div><p class="helper">{{ t.renderLimit }}</p>
          </template>
          <p v-if="options.tool === 'merge'" class="helper">{{ t.mergeHint }}</p><p v-if="options.tool === 'extract'" class="helper">{{ t.extractHint }}</p>
          <p v-if="!showPaper" class="helper">{{ t.unsupported }}</p>
          <NAlert v-if="error" type="error" class="task-alert" role="alert">{{ error }}</NAlert>
          <div class="process-row">
            <NButton type="primary" size="large" block :loading="busy" :disabled="checking || (!isText && options.files.length === 0) || (isText && !options.content.trim())" class="process-button" @click="execute">{{ busy ? t.processing : t.start }}<template #icon><NIcon :component="metadata.group === 0 ? DocumentTextOutline : LayersOutline" /></template></NButton>
            <NButton v-if="busy" size="large" :aria-label="t.cancel" @click="cancel">{{ t.cancel }}</NButton>
          </div>
        </section>
      </div>
      <section class="studio-panel result-panel" aria-live="polite">
        <div class="result-heading"><h3><span class="step">03</span>{{ t.result }}</h3><NTag v-if="result && !stale" :bordered="false" type="success" size="small">{{ t.resultReady }}</NTag></div>
        <div v-if="!result" class="empty-result"><div class="document-illustration"><div /><div /><div /><span>PDF</span></div><strong>{{ busy ? t.processing : t.emptyTitle }}</strong><p>{{ busy ? executionNotice : t.emptyDescription }}</p><NSpin v-if="busy" size="small" /></div>
        <template v-else>
          <NAlert v-if="stale" type="warning" class="task-alert">{{ t.stale }}</NAlert>
          <p class="helper">{{ result.files.length }} {{ t.fileUnit }} · {{ t.resultHint }}</p>
          <div class="output-list"><div v-for="(output, index) in result.files" :key="output.name" class="output-row" :class="{ selected: index === selectedResult }"><NIcon :component="CheckmarkCircleOutline" :size="19" /><button class="output-name" :disabled="previewBusy" @click="showResult(index)"><strong>{{ output.name }}</strong><small>{{ formatSize(output.blob.size) }}<template v-if="output.pages"> · {{ output.pages }} {{ t.pageUnit }}</template></small></button><NButton secondary :disabled="stale" :aria-label="`${t.download} ${output.name}`" @click="download(output)"><NIcon :component="DownloadOutline" /><span class="download-label">{{ t.download }}</span></NButton></div></div>
          <NButton v-if="result.files.length > 1" block secondary type="primary" :disabled="stale" :loading="zipBusy" class="zip-button" @click="downloadZip">{{ t.downloadAll }}</NButton>
          <div v-if="result.text" class="text-result"><div><strong>{{ t.preview }}</strong><NButton secondary :disabled="stale" @click="copyText">{{ copied ? t.copied : t.copy }}</NButton></div><NInput :value="result.text" type="textarea" readonly :autosize="{ minRows: 12, maxRows: 26 }" :aria-label="t.preview" /></div>
          <div v-if="currentIsPdf || currentIsImage" class="preview-box"><div v-if="previewBusy" class="preview-loading"><NSpin size="small" /></div><p v-if="previewError" class="helper preview-error">{{ t.previewFailed }}</p><canvas v-if="currentIsPdf" ref="canvas" :aria-label="t.preview" /><img v-if="currentIsImage && imageUrl" :src="imageUrl" :alt="currentOutput?.name"></div>
          <div v-if="currentIsPdf" class="page-controls"><NButton secondary :disabled="previewBusy || previewPage <= 1" @click="changePage(-1)">{{ t.previous }}</NButton><span>{{ previewPage }} / {{ previewCount }}</span><NButton secondary :disabled="previewBusy || previewPage >= previewCount" @click="changePage(1)">{{ t.next }}</NButton></div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.pdf-page { color: var(--ink); min-width: 0; }
.pdf-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 30px; }
.eyebrow { color: var(--muted); font-size: 10px; font-weight: 700; letter-spacing: .19em; margin: 0 0 8px; }
.pdf-heading h1 { margin: 0; font-size: clamp(25px, 3vw, 32px); letter-spacing: -.7px; font-weight: 700; }
.page-subtitle { color: var(--muted); margin: 9px 0 0; font-size: 13px; }
.local-tag { background: var(--soft-primary); color: #646cff; white-space: nowrap; padding: 6px 12px; }
.local-tag :deep(.n-tag__content) { gap: 5px; }
.tool-groups { display: grid; grid-template-columns: 1.13fr 1.13fr 1fr; gap: 18px; margin-bottom: 32px; }
.group-title { margin: 0 0 10px; font-size: 11px; color: var(--muted); letter-spacing: 2px; }
.tool-grid { display: grid; gap: 9px; }
.tool-card { position: relative; display: flex; align-items: center; gap: 11px; width: 100%; min-width: 0; min-height: 99px; padding: 15px 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; text-align: left; cursor: pointer; color: inherit; transition: background .16s, border-color .16s; font: inherit; }
.tool-card:hover { border-color: #aeb2ff; background: var(--canvas); }
.tool-card:focus-visible { outline: 3px solid #aeb2ff; outline-offset: 2px; }
.tool-card.active { border-color: #9399ff; background: var(--soft-primary); box-shadow: 0 0 0 1px #9399ff; }
.tool-card:disabled { cursor: wait; opacity: .65; }
.tool-icon { display: grid; place-items: center; flex: 0 0 34px; height: 38px; border-radius: 9px; background: var(--soft-primary); color: #777fe2; }
.tool-card.active .tool-icon { background: var(--soft-primary); color: #646cff; }
.tool-copy { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.tool-copy strong { font-size: 14px; font-weight: 650; }
.tool-copy > span { font-size: 12px; color: var(--muted); line-height: 1.5; }
.tool-copy small { font-size: 10px; font-weight: 650; color: var(--muted); letter-spacing: .4px; }
.active-dot { position: absolute; width: 5px; height: 5px; border-radius: 50%; right: 10px; top: 11px; background: transparent; }
.tool-card.active .active-dot { background: #646cff; }
.workbench-title { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin: 4px 0 17px; }
.workbench-title h2 { margin: 0; font-size: 20px; font-weight: 650; }
.workbench-title p { margin: 7px 0 0; font-size: 11px; color: var(--muted); }
.workbench { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr); gap: 20px; align-items: start; }
.editor-column { display: grid; gap: 16px; min-width: 0; }
.studio-panel { padding: 22px; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; min-width: 0; }
.studio-panel h3 { font-size: 13px; font-weight: 650; display: flex; align-items: center; gap: 9px; margin: 0 0 20px; }
.step { font-size: 10px; color: #959bea; background: var(--soft-primary); padding: 3px 6px; border-radius: 5px; letter-spacing: 1px; }
.file-input { display: none; }
.drop-zone { display: flex; align-items: center; flex-direction: column; gap: 9px; border: 1px dashed #c8cdf0; border-radius: 11px; background: var(--canvas); padding: 24px 12px; color: #969dde; }
.drop-zone.dragging { background: var(--soft-primary); border-color: #646cff; }
.drop-zone strong { margin-top: 6px; color: var(--ink); font-size: 13px; font-weight: 500; }
.drop-zone > span { font-size: 11px; color: var(--muted); }
.drop-zone :deep(.n-button) { margin-top: 5px; padding-inline: 23px; }
.helper, .field small { font-size: 12px; color: var(--muted); line-height: 1.8; margin: 11px 0; }
.text-import { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.note { padding: 10px 12px; background: var(--canvas); border-radius: 7px; }
.file-list { padding: 0; margin: 17px 0 0; list-style: none; display: grid; gap: 8px; }
.file-list li { display: flex; align-items: center; gap: 8px; background: var(--canvas); padding: 9px; border-radius: 8px; min-width: 0; }
.file-order { color: var(--muted); font-size: 10px; }
.file-detail { flex: 1; min-width: 0; }
.file-detail strong, .file-detail small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-detail strong { font-size: 11px; font-weight: 500; }
.file-detail small { font-size: 10px; color: var(--muted); margin-top: 4px; }
.file-actions { display: flex; gap: 0; flex-shrink: 0; }
.file-actions :deep(.n-button) { width: 32px; height: 40px; }
.settings-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
.field { display: flex; flex-direction: column; min-width: 0; gap: 8px; margin-bottom: 17px; }
.field > span { font-size: 12px; color: var(--muted); }
.field small { margin: 0; }
.process-row { display: flex; gap: 10px; align-items: stretch; }
.process-row .process-button { flex: 1; }
.process-button { margin-top: 22px; }
.task-alert { margin: 16px 0; }
.cut-diagram { width: 130px; height: 160px; margin: 4px auto 18px; display: grid; background: var(--canvas); border: 1px solid #c8cdf0; border-radius: 4px; color: #8c93d9; font-size: 10px; overflow: hidden; }
.cut-diagram.vertical { grid-template-columns: var(--ratio) 1fr; }
.cut-diagram.horizontal { grid-template-rows: var(--ratio) 1fr; }
.cut-diagram span { display: grid; place-items: center; overflow: hidden; white-space: nowrap; }
.cut-diagram.vertical span + span { border-left: 1px dashed #868cff; background: var(--soft-primary); }
.cut-diagram.horizontal span + span { border-top: 1px dashed #868cff; background: var(--soft-primary); }
.result-panel { min-height: 507px; }
.result-heading { display: flex; gap: 8px; align-items: start; justify-content: space-between; }
.result-heading h3 { margin-bottom: 12px; }
.empty-result { display: flex; align-items: center; justify-content: center; flex-direction: column; min-height: 420px; gap: 16px; color: var(--muted); text-align: center; }
.empty-result > strong { font-size: 13px; font-weight: 500; }
.empty-result > p { max-width: 210px; line-height: 1.8; font-size: 11px; color: var(--muted); margin: 0; }
.document-illustration { position: relative; width: 81px; height: 105px; background: var(--canvas); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 13px; transform: rotate(-8deg); box-shadow: 7px 7px 0 var(--soft-primary); padding: 22px 14px; box-sizing: border-box; }
.document-illustration div { height: 3px; border-radius: 2px; margin-bottom: 8px; background: #dde1f6; }
.document-illustration div:nth-child(2) { width: 70%; }
.document-illustration span { display: block; margin-top: 13px; color: #959bd5; font-size: 13px; font-weight: 700; letter-spacing: 1px; }
.output-list { display: grid; gap: 7px; max-height: 330px; overflow-y: auto; margin: 14px 0; }
.output-row { display: flex; align-items: center; gap: 9px; padding: 10px; border: 1px solid var(--border); border-radius: 9px; color: #6f77cc; }
.output-row.selected { background: var(--canvas); }
.output-name { flex: 1; min-width: 0; text-align: left; border: 0; background: none; cursor: pointer; font: inherit; color: var(--ink); }
.output-name strong, .output-name small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.output-name strong { font-size: 11px; font-weight: 500; }
.output-name small { font-size: 10px; color: var(--muted); margin-top: 4px; }
.download-label { margin-left: 4px; font-size: 11px; }
.zip-button { margin-bottom: 15px; }
.preview-box { position: relative; background: var(--canvas); padding: 16px; border-radius: 9px; min-height: 130px; overflow: hidden; display: flex; justify-content: center; align-items: start; margin-top: 18px; }
.preview-box canvas { display: block; max-width: 100%; height: auto !important; box-shadow: 0 2px 9px #23372015; }
.preview-box img { display: block; max-width: 100%; height: auto; }
.preview-loading { position: absolute; top: 15px; right: 15px; background: #ffffffdc; border-radius: 7px; padding: 7px; z-index: 1; }
.preview-error { position: absolute; inset: 8px; background: var(--surface); padding: 10px; z-index: 2; }
.page-controls { display: flex; align-items: center; justify-content: center; gap: 14px; margin-top: 16px; font-size: 11px; color: var(--muted); }
.text-result { margin-top: 20px; }
.text-result > div:first-child { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 12px; font-size: 12px; }
@media (max-width: 1100px) { .tool-groups { gap: 11px; } .tool-card { padding: 12px 10px; gap: 8px; } .tool-copy > span { display: none; } .tool-card { min-height: 80px; } .studio-panel { padding: 18px; } }
@media (max-width: 900px) { .workbench { grid-template-columns: 1fr; } .editor-column { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; } .result-panel { min-height: 250px; } .empty-result { min-height: 250px; } }
@media (max-width: 650px) { .pdf-heading { margin-bottom: 23px; } .local-tag { padding: 4px 8px; font-size: 10px; } .tool-groups { display: block; margin-bottom: 24px; } .tool-group + .tool-group { margin-top: 17px; } .tool-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; } .tool-card { min-height: 77px; gap: 7px; padding: 12px 9px; } .tool-icon { flex-basis: 28px; height: 32px; } .tool-copy strong { font-size: 11px; } .tool-copy small { font-size: 9px; letter-spacing: 0; } .group-title { margin-bottom: 8px; } .editor-column { grid-template-columns: 1fr; } .workbench-title h2 { font-size: 18px; } .studio-panel { padding: 18px; } .workbench { gap: 14px; } .file-actions :deep(.n-button) { width: 44px; height: 44px; } .pdf-page :deep(.n-button) { min-height: 44px; } .settings-panel :deep(.n-base-selection), .settings-panel :deep(.n-input) { min-height: 44px; } .preview-box { padding: 10px; } .download-label { display: none; } .page-controls { gap: 10px; } .workbench-title p { max-width: 210px; } }
@media (max-width: 359px) { .tool-grid { grid-template-columns: 1fr; } .tool-copy > span { display: block; font-size: 10px; } .tool-card { min-height: 80px; } .tool-copy strong { font-size: 12px; } .tool-copy small { font-size: 10px; } .tool-icon { flex-basis: 34px; } .file-list li { flex-wrap: wrap; } .file-detail { width: calc(100% - 30px); flex: unset; } .file-actions { margin-left: auto; } .settings-grid { grid-template-columns: 1fr; gap: 0; } .pdf-heading { gap: 8px; } .local-tag { padding: 3px 6px; } }
</style>
