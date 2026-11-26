<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import {
  NAlert, NButton, NCheckbox, NFormItem, NIcon, NInput, NRadio, NRadioGroup,
  NSelect, NTab, NTabs, NTag, useMessage
} from 'naive-ui';
import {
  AddOutline, CheckmarkCircleOutline, ChevronBackOutline, ChevronForwardOutline, DocumentTextOutline,
  PrintOutline, RefreshOutline, ShieldCheckmarkOutline
} from '@vicons/ionicons5';
import WorksheetPreview from '../components/WorksheetPreview.vue';
import CurriculumPicker from '../components/CurriculumPicker.vue';
import EntrySelector from '../components/EntrySelector.vue';
import { useWorksheetDraft } from '../composables/useWorksheetDraft';
import { text } from '../locales/common';
import { worksheetText as t } from '../locales/worksheet';
import { downloadBlob, releasePdf, renderPdfPage } from '../lib/pdf-runtime';
import type { Subject, WorksheetMode } from '../types';

const props = defineProps<{ subject: Subject }>();
const message = useMessage();
const subjectRef = computed(() => props.subject);
const draft = reactive(useWorksheetDraft(subjectRef));

const activeTab = ref('settings');
const previewAnswer = ref(false);
const page = ref(1);
const zoom = ref<'fit' | 'actual'>('fit');
const printing = ref(false);
let printRelease: Uint8Array | null = null;

const isChinese = computed(() => props.subject === 'chinese');
const modeOptions = computed(() => isChinese.value
  ? [
    { value: 'chinese-dictation', label: t.chineseDictation, hint: t.chineseDictationHint },
    { value: 'chinese-pinyin', label: t.chinesePinyin, hint: t.chinesePinyinHint }
  ]
  : [
    { value: 'english-dictation', label: t.englishDictation, hint: t.englishDictationHint },
    { value: 'english-meaning', label: t.englishMeaning, hint: t.englishMeaningHint }
  ]);
const modeName = computed(() => modeOptions.value.find(option => option.value === draft.mode)?.label ?? '');
const defaultTitle = computed(() => (({
  'chinese-dictation': t.defaultChineseDictation,
  'chinese-pinyin': t.defaultChinesePinyin,
  'english-dictation': t.defaultEnglishDictation,
  'english-meaning': t.defaultEnglishMeaning
}) as Record<WorksheetMode, string>)[draft.mode]);
const gridOptions = [10, 12, 14].map(value => ({ label: `${value} mm`, value }));
const repetitionOptions = [1, 2, 3, 4, 5].map(value => ({ label: `${t.timesPrefix} ${value} ${t.timesSuffix}`, value }));
const zoomOptions = [{ label: t.fitWidth, value: 'fit' }, { label: '100%', value: 'actual' }];
const sourceLabel = computed(() => draft.contentSource === 'textbook' ? t.textbookSource : draft.contentSource === 'custom' ? t.customSource : t.sampleSource);

const currentArtifact = computed(() => previewAnswer.value ? draft.answerArtifact : draft.studentArtifact);
const pageCount = computed(() => currentArtifact.value?.pageCount ?? 0);
const canOutput = computed(() => !draft.busy && !printing.value && !draft.error && !!draft.studentArtifact);
const errorMessage = computed(() => {
  if (!draft.error) return '';
  return (t as Record<string, string>)[draft.error] ?? t.generationError;
});
const progressText = computed(() => draft.busy ? t.updating
  : draft.error ? errorMessage.value
    : !draft.selectedEntries.length ? t.countWarning
      : `${draft.questionCount} ${t.questions} · ${draft.studentArtifact?.pageCount ?? 0} ${t.pages}`);

// 内容、模式、重复次数或纸面参数变化后重新排版；纯版式变化不会新增成功计数。
const renderSignature = computed(() => JSON.stringify({
  ids: draft.selectedEntries.map(entry => entry.entryId),
  mode: draft.mode,
  repetitions: draft.repetitions,
  gridMm: draft.layout.gridMm,
  title: draft.title,
  answer: previewAnswer.value
}));

watch(renderSignature, () => { draft.scheduleGenerate(); }, { immediate: true });
watch(pageCount, value => { if (page.value > Math.max(1, value)) page.value = 1; });

/** 打印文档仅接收选定文件的页面图像，不携带答案或原始词条。 */
async function printArtifact(answer = false): Promise<void> {
  const artifact = answer ? draft.answerArtifact : draft.studentArtifact;
  if (!canOutput.value || !artifact) return;
  const printWindow = window.open('', '_blank', 'width=900,height=950');
  if (!printWindow) { message.warning(t.printUnavailable); return; }
  printWindow.opener = null;
  const printBytes = artifact.bytes.slice();
  printRelease = printBytes;
  printing.value = true;
  const doc = printWindow.document;
  doc.title = answer ? t.answerSheet : t.printWindowTitle;
  const style = doc.createElement('style');
  style.textContent = '@page{size:A4 portrait;margin:0}*{box-sizing:border-box}body{margin:0;background:#edf1f7;font-family:sans-serif}.print-actions{padding:16px;position:sticky;top:0;background:white;display:flex;gap:12px;align-items:center;flex-wrap:wrap;box-shadow:0 1px 4px #0000001a}a,button{display:inline-flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;padding:10px 20px;cursor:pointer;border:1px solid #d7dee8;border-radius:6px;background:#fff;color:#1f2937;font-size:14px;text-decoration:none}button{background:#646cff;border-color:#646cff;color:#fff}.page-summary{margin-left:auto;color:#7a8597;font-size:13px}.page{display:block;width:210mm;height:297mm;margin:16px auto;break-after:page;background:white}.page:last-child{break-after:auto}@media print{body{background:white}.print-actions{display:none}.page{margin:0}}';
  doc.head.append(style);
  const toolbar = doc.createElement('div');
  toolbar.className = 'print-actions';
  const status = doc.createElement('span');
  status.textContent = t.printing;
  toolbar.append(status);
  doc.body.append(toolbar);
  try {
    // 打印使用较高分辨率，页面尺寸仍严格保持 A4。
    for (let index = 1; index <= artifact.pageCount; index += 1) {
      if (printWindow.closed) return;
      const canvas = doc.createElement('canvas');
      await renderPdfPage(printBytes, canvas, index, 2);
      const image = doc.createElement('img');
      image.className = 'page';
      image.alt = `${t.numberPrefix} ${index} ${t.numberSuffix}`;
      image.src = canvas.toDataURL('image/png');
      doc.body.append(image);
      await image.decode();
    }
    if (printWindow.closed) return;
    status.remove();
    // 预览区同时提供 PDF 下载：系统打印不可用或用户只想存档时，仍然拿得到同一份文件。
    const pdfUrl = URL.createObjectURL(new Blob([printBytes.slice().buffer as ArrayBuffer], { type: 'application/pdf' }));
    const downloadLink = doc.createElement('a');
    downloadLink.textContent = t.exportPdf;
    downloadLink.href = pdfUrl;
    downloadLink.download = artifact.filename;
    downloadLink.setAttribute('role', 'button');
    const printButton = doc.createElement('button');
    printButton.textContent = t.printWindowButton;
    const requestPrint = (): void => {
      printWindow.focus();
      printWindow.print();
      draft.notePrintRequest();
    };
    printButton.onclick = requestPrint;
    const closeButton = doc.createElement('button');
    closeButton.textContent = t.closePrintWindow;
    closeButton.onclick = () => printWindow.close();
    const pageSummary = doc.createElement('span');
    pageSummary.textContent = `${artifact.pageCount} ${t.pages}`;
    pageSummary.className = 'page-summary';
    toolbar.append(downloadLink, printButton, closeButton, pageSummary);
    // 释放预览页对象地址，避免打印窗口关闭后泄漏。
    printWindow.addEventListener('unload', () => URL.revokeObjectURL(pdfUrl));
    // 设计明确要求：只有用户点击「打印」才调起系统打印对话框，打开预览时不得自动弹出。
    message.info(t.printPreviewOpened);
  } catch {
    if (!printWindow.closed) printWindow.close();
    message.warning(t.printUnavailable);
  } finally {
    releasePdf(printBytes);
    printRelease = null;
    printing.value = false;
  }
}

/** 下载已经用于预览的同一份文件，不在导出时重新分页。 */
function exportPdf(answer = false): void {
  const artifact = answer ? draft.answerArtifact : draft.studentArtifact;
  if (!canOutput.value || !artifact) return;
  downloadBlob(new Blob([artifact.bytes.slice().buffer as ArrayBuffer], { type: 'application/pdf' }), artifact.filename);
  message.success(answer ? t.downloadAnswerStarted : t.downloadStarted);
}

/** 打开自定义词条输入：切到词条面板并直接弹出输入框。 */
function openCustomImport(): void {
  activeTab.value = 'entries';
  draft.importRequested = true;
}

/** 主操作进入学生卷预览，答案输出另设入口。 */
function showPreview(): void {
  if (!draft.selectedEntries.length) { message.warning(text.selectAtLeastOne); return; }
  previewAnswer.value = false;
  activeTab.value = 'preview';
}

/** 明确新建练习：形成新的操作身份。 */
function startNew(): void {
  draft.startNewWorksheet();
  draft.title = '';
  message.success(t.resetSample);
}

// 释放打印过程中可能残留的 PDF 句柄。
watch(printing, value => { if (!value && printRelease) { releasePdf(printRelease); printRelease = null; } });
</script>

<template>
  <div class="worksheet-page">
    <div class="page-heading">
      <div><h1>{{ isChinese ? t.chineseTitle : t.englishTitle }}</h1><p>{{ isChinese ? t.chineseSubtitle : t.englishSubtitle }}</p></div>
      <span class="privacy-badge"><NIcon :component="ShieldCheckmarkOutline" />{{ t.localOnly }}</span>
    </div>
    <div class="sample-notice">
      <NTag size="small" :bordered="false" :type="draft.contentSource === 'textbook' ? 'success' : 'warning'">{{ sourceLabel }}</NTag>
      <span>{{ draft.contentSource === 'textbook' ? text.publishedEdition : t.sampleNotice }}</span>
    </div>
    <NTabs :value="activeTab" type="line" justify-content="space-evenly" class="mobile-tabs" :aria-label="t.settings" @update:value="value => { activeTab = String(value); }">
      <NTab name="settings">{{ t.settings }}</NTab>
      <NTab name="entries">{{ t.entries }} <span class="tab-count">{{ draft.selectedEntries.length }}</span></NTab>
      <NTab name="preview">{{ t.preview }}</NTab>
    </NTabs>

    <div class="workbench">
      <aside class="settings-pane pane" :class="{ 'is-active': activeTab === 'settings', 'desktop-active': activeTab !== 'entries' }">
        <div class="settings-scroll" tabindex="0" :aria-label="t.settings">
          <CurriculumPicker :draft="draft" />

          <section class="settings-section">
            <h2><span class="step-number">C</span>{{ text.customEntries }}</h2>
            <p class="field-note">{{ t.customEntryHint }}</p>
            <NButton block dashed class="custom-entry-button" @click="openCustomImport">
              <template #icon><NIcon :component="AddOutline" /></template>
              {{ text.importEntries }}<template v-if="draft.customEntries.length">（{{ draft.customEntries.length }}）</template>
            </NButton>
          </section>

          <section class="settings-section">
            <h2><span class="step-number">2</span>{{ t.practiceMode }}</h2>
            <NRadioGroup :value="draft.mode" class="mode-options" :aria-label="t.modeLabel" @update:value="value => { draft.mode = String(value) as WorksheetMode; }">
              <NRadio v-for="option in modeOptions" :key="option.value" :value="option.value">
                <span class="mode-label">{{ option.label }}</span><span class="mode-hint">{{ option.hint }}</span>
              </NRadio>
            </NRadioGroup>
          </section>

          <section class="settings-section paper-settings">
            <h2><span class="step-number">3</span>{{ t.paper }}</h2>
            <div class="paper-format"><DocumentTextOutline /><span>{{ t.a4Portrait }}</span><span>210 × 297 mm</span></div>
            <div class="field-grid">
              <NFormItem v-if="isChinese" :label="t.gridSize" :show-feedback="false">
                <NSelect :value="draft.layout.gridMm" :options="gridOptions" :aria-label="t.gridSize" @update:value="value => draft.setGridMm(value as 10 | 12 | 14)" />
              </NFormItem>
              <NFormItem :label="t.repetitions" :show-feedback="false" :class="{ 'wide-field': !isChinese }">
                <NSelect v-model:value="draft.repetitions" :options="repetitionOptions" :aria-label="t.repetitions" />
              </NFormItem>
              <NFormItem :label="t.title" :show-feedback="false" class="wide-field">
                <NInput v-model:value="draft.title" :placeholder="defaultTitle" :maxlength="40" show-count :aria-label="t.title" />
              </NFormItem>
            </div>
            <NAlert v-if="draft.titleState !== 'ok'" type="error" :show-icon="false">{{ text.tooLongTitle }}</NAlert>
            <p v-if="!isChinese" class="field-note">{{ t.gridFixed }}</p>
            <div class="paper-actions">
              <NButton size="small" @click="startNew"><template #icon><NIcon :component="RefreshOutline" /></template>{{ t.resetSample }}</NButton>
              <span class="paper-meta">{{ t.selected }} {{ draft.selectedEntries.length }} {{ t.items }} · {{ draft.questionCount }} {{ t.questions }}</span>
            </div>
          </section>
        </div>
        <div class="desktop-primary-actions">
          <NButton type="primary" block size="large" :disabled="!draft.selectedEntries.length || draft.scaleState !== 'ok'" @click="showPreview"><template #icon><NIcon :component="PrintOutline" /></template>{{ t.printPreview }}</NButton>
          <NButton block size="large" :disabled="!canOutput" @click="exportPdf(false)"><template #icon><NIcon :component="DocumentTextOutline" /></template>{{ t.exportPdf }}</NButton>
        </div>
      </aside>

      <div class="entries-wrap" :class="{ 'is-active': activeTab === 'entries', 'desktop-active': activeTab === 'entries' }">
        <EntrySelector :draft="draft" :subject="props.subject" @back="activeTab = 'settings'" />
      </div>

      <section class="preview-pane pane" :class="{ 'is-active': activeTab === 'preview' }">
        <div class="mobile-preview-heading">
          <h2>{{ modeName }}</h2>
          <p>{{ draft.grade ?? '—' }} {{ t.gradeSuffix }}{{ draft.semester === 'first' ? t.upper : t.lower }} · {{ sourceLabel }} · {{ draft.selectedEntries.length }} {{ t.items }}</p>
        </div>
        <div class="preview-toolbar">
          <div class="preview-title"><span>{{ previewAnswer ? t.answerSheet : t.preview }}</span><span class="preview-count">{{ pageCount ? `${page} / ${pageCount}` : '—' }}</span></div>
          <NSelect v-model:value="zoom" :options="zoomOptions" class="zoom-select" :aria-label="t.fitWidth" />
          <div class="page-controls">
            <NButton quaternary circle :disabled="page <= 1 || draft.busy" :aria-label="t.previousPage" @click="page -= 1"><template #icon><NIcon :component="ChevronBackOutline" /></template></NButton>
            <span>{{ pageCount ? `${page} / ${pageCount}` : '—' }}</span>
            <NButton quaternary circle :disabled="page >= pageCount || draft.busy" :aria-label="t.nextPage" @click="page += 1"><template #icon><NIcon :component="ChevronForwardOutline" /></template></NButton>
          </div>
        </div>
        <div class="preview-options">
          <NCheckbox v-model:checked="previewAnswer" :disabled="!draft.answerArtifact">{{ t.viewAnswer }}</NCheckbox>
          <span>{{ previewAnswer ? t.answerSheet : t.studentSheet }}</span>
        </div>
        <NAlert v-if="previewAnswer" type="warning" :show-icon="false" class="answer-alert">{{ t.answerWarning }}</NAlert>
        <WorksheetPreview :artifact="currentArtifact" :page="page" :zoom="zoom" :busy="draft.busy" :error="errorMessage" @retry="draft.generate(!!draft.answerArtifact)" @select="activeTab = 'entries'" />
        <div class="preview-footer">
          <span class="ready-state" role="status"><NIcon v-if="canOutput" :component="CheckmarkCircleOutline" />{{ progressText }}</span>
          <span>{{ t.previewHint }}</span>
        </div>
        <div v-if="draft.studentArtifact" class="artifact-meta">
          <span>{{ draft.studentArtifact.filename }}</span>
          <span>{{ (draft.studentArtifact.bytes.byteLength / 1024).toFixed(0) }} KB</span>
          <span>{{ draft.studentArtifact.pageCount }} {{ t.pages }}</span>
          <span class="hash" :title="draft.studentArtifact.sha256">sha256 {{ draft.studentArtifact.sha256.slice(0, 12) }}…</span>
        </div>
        <div class="output-toolbar">
          <p>{{ t.paperHint }}</p>
          <div class="output-buttons">
            <NButton v-if="previewAnswer" :disabled="!canOutput || !draft.answerArtifact" @click="exportPdf(true)">{{ t.exportAnswer }}</NButton>
            <NButton v-else secondary :disabled="!canOutput" @click="draft.generate(true)">{{ t.viewAnswer }}</NButton>
            <NButton :type="previewAnswer ? 'warning' : 'primary'" secondary :loading="printing" :disabled="!canOutput || (previewAnswer && !draft.answerArtifact)" @click="printArtifact(previewAnswer)"><template #icon><NIcon :component="PrintOutline" /></template>{{ previewAnswer ? t.printAnswer : t.printStudent }}</NButton>
          </div>
        </div>
      </section>
    </div>
    <div class="page-privacy"><NIcon :component="ShieldCheckmarkOutline" />{{ t.localPrivacy }}</div>
    <div class="mobile-actions">
      <NButton size="large" :disabled="!canOutput" @click="exportPdf(false)"><template #icon><NIcon :component="DocumentTextOutline" /></template>{{ t.exportPdf }}</NButton>
      <NButton size="large" type="primary" :disabled="!draft.selectedEntries.length || draft.scaleState !== 'ok'" @click="showPreview"><template #icon><NIcon :component="PrintOutline" /></template>{{ t.printPreview }}</NButton>
    </div>
  </div>
</template>

<style scoped>
.worksheet-page { min-width: 0; }
.page-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 20px; }
.page-heading h1 { margin: 0 0 8px; font-size: 25px; font-weight: 650; letter-spacing: -.5px; color: var(--ink); }
.page-heading p { margin: 0; font-size: 13px; color: var(--muted); line-height: 1.7; }
.privacy-badge { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; font-size: 12px; color: var(--muted); }
.sample-notice { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; padding: 10px 14px; border-radius: 7px; background: var(--canvas); border: 1px solid var(--border); color: var(--muted); font-size: 12px; line-height: 1.8; }
.sample-notice .n-tag { flex-shrink: 0; }
.workbench { display: grid; grid-template-columns: 320px minmax(0, 1fr); align-items: start; gap: 18px; }
.settings-pane { min-width: 0; border: 1px solid var(--border); background: var(--surface); border-radius: 9px; padding: 0 20px; }
.entries-wrap { min-width: 0; display: none; }
.settings-pane:not(.desktop-active) { display: none; }
.custom-entry-button { min-height: 44px; }
.paper-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.paper-meta { font-size: 11px; color: var(--muted); }
.desktop-primary-actions { display: flex; flex-direction: column; gap: 10px; margin: 12px 0 21px; }
.preview-pane { min-width: 0; }
.preview-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 58px; border: 1px solid var(--border); border-radius: 9px 9px 0 0; padding: 9px 18px; background: var(--canvas); }
.preview-title { color: var(--muted); font-size: 13px; display: flex; align-items: center; gap: 12px; }
.preview-count { display: none; }
.zoom-select { width: 132px; }
.page-controls { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 12px; }
.page-controls > span { min-width: 38px; text-align: center; }
.preview-options { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 46px; padding: 8px 18px; background: var(--surface); border-inline: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.preview-options > span { color: var(--muted); font-size: 12px; }
.preview-footer { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px; color: var(--muted); font-size: 11px; padding: 13px 0; }
.ready-state { display: flex; align-items: center; gap: 5px; }
.artifact-meta { display: flex; flex-wrap: wrap; gap: 12px; font-size: 11px; color: var(--muted); padding-bottom: 8px; }
.artifact-meta .hash { font-family: ui-monospace, monospace; }
.output-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 4px; padding: 14px 0; border-top: 1px solid var(--border); }
.output-toolbar p { font-size: 11px; color: var(--muted); margin: 0; max-width: 240px; line-height: 1.8; }
.output-buttons { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.answer-alert { border-radius: 0; font-size: 12px; }
.page-privacy { display: flex; align-items: center; justify-content: center; gap: 6px; color: var(--muted); margin: 24px 0 5px; font-size: 11px; }
.mobile-tabs, .mobile-actions, .mobile-preview-heading { display: none; }
.field-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px 10px; }
.wide-field { grid-column: 1 / -1; }
:deep(.n-form-item-label) { font-size: 12px; color: var(--muted); padding-bottom: 5px; }
.settings-section { padding: 21px 0; border-bottom: 1px solid var(--border); }
.settings-section:last-child { border: 0; }
.settings-section h2 { margin: 0 0 20px; color: var(--ink); font-size: 16px; font-weight: 650; display: flex; align-items: center; gap: 10px; }
.step-number { width: 27px; height: 27px; display: grid; place-items: center; border-radius: 50%; color: #6366f1; background: #eef0ff; font-size: 16px; }
.mode-options { display: flex; flex-direction: column; gap: 17px; }
.mode-options :deep(.n-radio__label) { padding-left: 9px; }
.mode-label { display: block; font-size: 13px; color: var(--ink); }
.mode-hint { display: block; color: var(--muted); font-size: 11px; margin-top: 4px; }
.paper-format { display: flex; align-items: center; gap: 8px; color: var(--muted); padding: 11px 12px; background: var(--canvas); border-radius: 6px; font-size: 12px; margin-bottom: 15px; }
.paper-format svg { width: 17px; height: 17px; }
.paper-format span:last-child { margin-left: auto; font-size: 11px; }
.field-note { font-size: 11px; color: var(--muted); margin: 10px 0; line-height: 1.7; }
@media (min-width: 1280px) {
  .settings-pane.desktop-active { display: flex; flex-direction: column; max-height: calc(100dvh - 292px); position: sticky; top: 86px; padding: 0; overflow: hidden; }
  .settings-scroll { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; padding: 0 20px; }
  .settings-scroll:focus-visible { outline: 2px solid #646cff; outline-offset: -3px; border-radius: 8px 8px 0 0; }
  .desktop-primary-actions { flex-shrink: 0; margin: 0; padding: 14px 20px 16px; background: var(--surface); border-top: 1px solid var(--border); }
  .entries-wrap.desktop-active { display: block; position: sticky; top: 86px; }
}
@media (max-width: 1279px) {
  .workbench { display: block; }
  .pane:not(.is-active), .entries-wrap:not(.is-active) { display: none; }
  .pane.is-active, .entries-wrap.is-active { display: block; }
  .mobile-tabs { display: flex; margin: 4px 0 21px; }
  .tab-count { display: inline-grid; place-items: center; min-width: 20px; padding: 0 5px; height: 19px; margin-left: 5px; border-radius: 5px; font-size: 10px; background: #eef0fa; }
  .settings-pane { padding: 0 24px; }
  .settings-section { padding: 24px 0; }
  .desktop-primary-actions { flex-direction: row; }
  .desktop-primary-actions .n-button { flex: 1; }
  .privacy-badge { display: none; }
  .mobile-preview-heading { display: block; margin-bottom: 18px; }
  .mobile-preview-heading h2 { font-size: 20px; margin-bottom: 6px; color: var(--ink); }
  .mobile-preview-heading p { font-size: 12px; color: var(--muted); margin: 0; }
}
@media (max-width: 767px) {
  .worksheet-page { padding-bottom: 79px; }
  .page-heading { margin-bottom: 14px; }
  .page-heading h1 { font-size: 23px; margin-bottom: 7px; }
  .page-heading p { font-size: 12px; }
  .sample-notice { padding: 10px 11px; align-items: flex-start; flex-direction: column; gap: 6px; font-size: 11px; margin-bottom: 6px; }
  .mobile-tabs { margin-bottom: 16px; }
  .settings-pane { padding: 0 17px; }
  .settings-section { padding: 21px 0; }
  :deep(.n-base-selection-label) { min-height: 42px; }
  :deep(.n-input-wrapper) { min-height: 42px; align-items: center; }
  .mode-options { gap: 19px; }
  .mode-label { font-size: 14px; }
  .mode-hint { font-size: 12px; }
  .desktop-primary-actions { display: none; }
  .mobile-actions { display: grid; grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr); position: fixed; bottom: 0; left: 0; right: 0; z-index: 50; gap: 12px; background: var(--surface); border-top: 1px solid var(--border); padding: 12px 16px calc(12px + env(safe-area-inset-bottom)); }
  .mobile-actions .n-button { min-width: 0; height: 46px; font-size: 14px; }
  .preview-toolbar { padding: 9px 11px; gap: 7px; }
  .preview-title { gap: 7px; font-size: 12px; }
  .preview-options { padding: 0 12px; }
  .preview-options .n-checkbox { min-height: 44px; align-items: center; }
  .preview-count { display: block; font-size: 11px; }
  .zoom-select { width: 115px; }
  .page-controls { gap: 0; }
  .page-controls > span { display: none; }
  .page-controls .n-button { width: 44px; height: 44px; }
  .preview-footer { justify-content: center; text-align: center; font-size: 10px; }
  .output-toolbar { flex-direction: column; align-items: center; padding-top: 16px; }
  .output-toolbar p { max-width: none; text-align: center; }
  .output-buttons { justify-content: center; width: 100%; }
  .output-buttons .n-button { flex: 1; min-height: 44px; }
  .page-privacy { font-size: 10px; align-items: flex-start; padding: 0 10px; text-align: center; line-height: 1.7; }
}
@media (max-width: 359px) {
  .mobile-actions { gap: 8px; padding-inline: 12px; }
  .mobile-actions .n-button { font-size: 12px; padding-inline: 8px; }
  .preview-toolbar { flex-wrap: wrap; }
  .preview-title { margin-right: auto; }
  .zoom-select { width: 108px; }
  .page-controls { width: 100%; justify-content: center; }
  .page-controls > span { display: block; }
  .preview-count { display: none; }
  .field-grid { grid-template-columns: minmax(0, 1fr); }
  .wide-field { grid-column: auto; }
}
</style>
