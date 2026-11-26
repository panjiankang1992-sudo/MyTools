<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NAlert, NButton, NCheckbox, NFormItem, NIcon, NInput, NModal, NTag, useMessage } from 'naive-ui';
import { AddOutline, ArrowBackOutline, PencilOutline, TrashOutline } from '@vicons/ionicons5';
import { text } from '../locales/common';
import { worksheetText as t } from '../locales/worksheet';
import type { WorksheetDraftState } from '../composables/useWorksheetDraft';
import type { CurriculumEntry, Subject } from '../domain/curriculum';

/**
 * 词条选择与编辑。
 * 教材条目与自定义条目分开标记，自定义内容只保存在当前页面或本机草稿中。
 */
const props = defineProps<{ draft: WorksheetDraftState; subject: Subject }>();
const emit = defineEmits<{ back: [] }>();
const message = useMessage();

const search = ref('');
const showImport = ref(false);
const importText = ref('');
const importError = ref('');
const reviewed = ref(false);
const editId = ref<string | null>(null);
const editText = ref('');
const editSecond = ref('');
const editError = ref('');

const isChinese = computed(() => props.subject === 'chinese');
const selectedSet = computed(() => new Set(props.draft.selectedIds));

const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  const list = props.draft.candidates;
  if (!keyword) return list;
  return list.filter(entry => `${entry.text} ${entry.pinyinDisplay ?? ''} ${entry.meaning ?? ''}`.toLowerCase().includes(keyword));
});

// 设置区的入口只会切换面板，这里负责真正把输入弹窗打开，避免多一次点击。
watch(() => props.draft.importRequested, value => {
  if (!value) return;
  showImport.value = true;
  importError.value = '';
  props.draft.importRequested = false;
});

/** 条目来源标签。 */
function sourceLabel(entry: CurriculumEntry): string | null {
  if (entry.editionId === 'custom') return text.entrySourceCustom;
  if (entry.blockingIssues.length) return text.blockingIssue;
  return null;
}

/** 打开自定义编辑弹窗。 */
function openEdit(entry: CurriculumEntry): void {
  editId.value = entry.entryId;
  editText.value = entry.text;
  editSecond.value = (isChinese.value ? entry.pinyinDisplay : entry.meaning) ?? '';
  editError.value = '';
}

/** 保存自定义条目。 */
function saveEdit(): void {
  if (!editId.value) return;
  const result = props.draft.updateCustom(editId.value, { text: editText.value, second: editSecond.value });
  if (result !== 'ok') { editError.value = t.missingFields; return; }
  editId.value = null;
}

/** 提交导入内容，或把「一键导入后移除待校对项」的错误反馈给用户。 */
function submitImport(): void {
  const result = props.draft.importCustom(importText.value);
  if (result === 'invalidImport') { importError.value = t.importParseError; return; }
  if (result === 'tooManyEntries') { importError.value = t.tooMany; return; }
  showImport.value = false;
  importText.value = '';
  reviewed.value = false;
  importError.value = '';
  search.value = '';
  message.success(t.added);
}

/** 保存或读取本机草稿并给出提示。 */
function saveDraft(): void {
  props.draft.saveDraft();
  message.success(t.draftSaved);
}

/** 读取本机草稿。 */
function loadDraft(): void {
  if (props.draft.loadDraft()) message.success(t.draftRestored);
}

/** 清除本机草稿。 */
function clearDraft(): void {
  props.draft.clearDraft();
  message.success(t.draftCleared);
}
</script>

<template>
  <section class="entries-pane pane">
    <div class="entries-heading">
      <h2>{{ t.selectedList }}</h2>
      <NButton text type="primary" @click="emit('back')"><template #icon><NIcon :component="ArrowBackOutline" /></template>{{ t.backSettings }}</NButton>
    </div>
    <NInput v-model:value="search" :placeholder="t.search" clearable :aria-label="t.search" />
    <div class="entry-batch">
      <NButton text type="primary" @click="draft.selectAll()">{{ t.selectAll }}</NButton>
      <NButton text @click="draft.clearSelection()">{{ t.clear }}</NButton>
      <span>{{ filtered.length }} {{ t.available }}</span>
    </div>
    <div class="entry-list">
      <div v-for="entry in filtered" :key="entry.entryId" class="entry-row" :class="{ selected: selectedSet.has(entry.entryId) }">
        <NCheckbox :checked="selectedSet.has(entry.entryId)" :aria-label="entry.text" @update:checked="value => draft.toggleEntry(entry.entryId, value)" />
        <div class="entry-content">
          <strong>{{ entry.text }}</strong>
          <span>{{ isChinese ? entry.pinyinDisplay : entry.meaning }}</span>
          <small v-if="sourceLabel(entry)"><NTag size="tiny" :bordered="false" :type="entry.editionId === 'custom' ? 'info' : 'warning'">{{ sourceLabel(entry) }}</NTag></small>
        </div>
        <NButton v-if="entry.editionId === 'custom'" quaternary circle size="small" :aria-label="`${t.edit} ${entry.text}`" class="entry-action" @click="openEdit(entry)"><template #icon><NIcon :component="PencilOutline" /></template></NButton>
        <NButton v-if="entry.editionId === 'custom'" quaternary circle size="small" :aria-label="`${t.delete} ${entry.text}`" class="entry-action" @click="draft.removeCustom(entry.entryId)"><template #icon><NIcon :component="TrashOutline" /></template></NButton>
      </div>
      <div v-if="!filtered.length" class="no-matches">{{ t.noMatches }}</div>
    </div>
    <NButton dashed block class="add-entry" @click="showImport = true; importError = ''"><template #icon><NIcon :component="AddOutline" /></template>{{ t.addCustom }}</NButton>
    <p class="field-note">{{ t.importHint }}</p>
    <div class="draft-actions">
      <NButton size="small" @click="saveDraft">{{ t.saveDraft }}</NButton>
      <NButton size="small" @click="loadDraft">{{ t.loadDraft }}</NButton>
      <NButton size="small" text @click="clearDraft">{{ t.clearDraft }}</NButton>
    </div>
    <div class="entry-footer"><span>{{ t.selected }} {{ draft.selectedEntries.length }} {{ t.items }} · {{ draft.questionCount }} {{ t.questions }}</span></div>

    <NModal v-model:show="showImport" preset="card" :title="t.importTitle" class="worksheet-modal" :style="{ width: 'min(560px, calc(100vw - 32px))' }">
      <p class="modal-help">{{ isChinese ? t.customHelpChinese : t.customHelpEnglish }}</p>
      <NInput v-model:value="importText" type="textarea" :rows="8" :maxlength="24000" :placeholder="isChinese ? t.customPlaceholderChinese : t.customPlaceholderEnglish" :aria-label="t.addCustom" />
      <p class="modal-help">{{ t.reviewNotice }}</p>
      <NCheckbox v-model:checked="reviewed">{{ t.customConfirm }}</NCheckbox>
      <NAlert v-if="importError" type="error" class="modal-error">{{ importError }}</NAlert>
      <template #footer>
        <div class="modal-actions"><NButton @click="showImport = false">{{ t.cancel }}</NButton><NButton type="primary" :disabled="!reviewed || !importText.trim()" @click="submitImport">{{ t.add }}</NButton></div>
      </template>
    </NModal>

    <NModal :show="!!editId" preset="card" :title="t.editTitle" :style="{ width: 'min(460px, calc(100vw - 32px))' }" @update:show="value => { if (!value) editId = null; }">
      <NFormItem :label="isChinese ? t.wordField : t.englishField"><NInput v-model:value="editText" :maxlength="100" :aria-label="isChinese ? t.wordField : t.englishField" /></NFormItem>
      <NFormItem :label="isChinese ? t.pinyinField : t.meaningField"><NInput v-model:value="editSecond" :maxlength="240" :aria-label="isChinese ? t.pinyinField : t.meaningField" /></NFormItem>
      <NAlert v-if="editError" type="error">{{ editError }}</NAlert>
      <template #footer><div class="modal-actions"><NButton @click="editId = null">{{ t.cancel }}</NButton><NButton type="primary" @click="saveEdit">{{ t.save }}</NButton></div></template>
    </NModal>
  </section>
</template>

<style scoped>
.entries-pane { min-width: 0; border: 1px solid var(--border); background: var(--surface); border-radius: 9px; padding: 21px 17px; }
.entries-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 17px; }
.entries-heading h2 { margin: 0; font-size: 16px; color: var(--ink); }
.entry-batch { display: flex; gap: 14px; align-items: center; margin: 14px 0; font-size: 12px; }
.entry-batch > span { margin-left: auto; color: var(--muted); }
.entry-list { max-height: 460px; overflow-y: auto; scrollbar-width: thin; }
.entry-row { display: flex; align-items: center; gap: 12px; min-height: 58px; padding: 9px 8px; border-bottom: 1px solid var(--border); border-radius: 5px; }
.entry-row.selected { background: #f8f9ff; }
.entry-content { min-width: 0; flex: 1; }
.entry-content strong { display: block; color: var(--ink); font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.entry-content > span { display: block; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.entry-action { min-width: 40px; min-height: 40px; }
.add-entry { margin-top: 16px; }
.field-note { font-size: 11px; color: var(--muted); margin: 10px 0; line-height: 1.7; }
.draft-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
.no-matches { padding: 30px 10px; text-align: center; color: var(--muted); font-size: 13px; }
.entry-footer { margin-top: 17px; border-top: 1px solid var(--border); padding-top: 15px; font-size: 11px; color: var(--muted); }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
.modal-help { font-size: 13px; color: var(--muted); line-height: 1.8; }
.modal-error { margin-top: 16px; }
@media (max-width: 767px) {
  .entries-pane { padding: 18px 13px; }
  .entry-row { min-height: 68px; }
  .entry-action { min-width: 44px; min-height: 44px; }
  .entry-row .n-button { height: 44px; width: 44px; }
  .add-entry { min-height: 44px; }
}
</style>
