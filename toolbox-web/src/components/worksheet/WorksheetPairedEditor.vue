<script setup lang="ts">
import { computed, nextTick, ref, toRaw, watch } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { INPUT_LIMITS } from '../../domain/worksheet-input';
import { isValidPinyinSyllable, splitGraphemes } from '../../domain/worksheet-processing';
import type { EditableWorksheetPair } from '../../domain/worksheet-editable-pairs';
import { useWorksheetPairCompletion } from '../../composables/useWorksheetPairCompletion';

/** 同一行始终是一组对应内容；移动端也按整组展示，避免左右列错位。 */
const props = withDefaults(defineProps<{
  subject: 'chinese' | 'english';
  rows: readonly EditableWorksheetPair[];
  label?: string;
  busy?: boolean;
  autoComplete?: boolean;
  paginated?: boolean;
  autoAppend?: boolean;
}>(), { label: '成对编辑内容', busy: false, autoComplete: false, paginated: true, autoAppend: false });
const emit = defineEmits<{
  'update:rows': [rows: EditableWorksheetPair[]];
  commit: [];
}>();
const editableRows = computed({ get: () => [...props.rows], set: rows => emit('update:rows', rows) });
const completion = useWorksheetPairCompletion(() => props.subject, editableRows, { enabled: () => props.autoComplete });
const completing = computed(() => props.busy || completion.busy.value);

/** 失焦只尝试尚未初始化的新行，保存事件仍由父页面管理。 */
function commitCell(): void {
  if (props.autoComplete) void completion.completeNow();
  emit('commit');
}
defineExpose({ cancelCompletion: completion.cancel, flushCompletion: completion.flush });
const editor = ref<HTMLElement | null>(null);
const pasteIssue = ref('');
const page = ref(1);
const pageSize = 30;
// 界面身份独立于业务 tokenId；父页面首次补 tokenId 时不能重建正在输入的文本框。
const uiIdentity = Symbol('worksheet-editor-row');
type EditorRow = EditableWorksheetPair & { [uiIdentity]?: number };
const uiKeysByToken = new Map<string, number>();
let uiSequence = 0;
let trailingRow: EditorRow = {};

/** 内部身份随对象展开保留，自动补全和父页面回填业务身份都不会改变界面节点。 */
function rowKey(row: EditableWorksheetPair): number {
  const raw = toRaw(row) as EditorRow;
  raw[uiIdentity] ??= (row.tokenId ? uiKeysByToken.get(row.tokenId) : undefined) ?? ++uiSequence;
  if (row.tokenId) uiKeysByToken.set(row.tokenId, raw[uiIdentity]);
  return raw[uiIdentity];
}

// 末尾待输入行只用于展示，首次输入才写入缓冲，避免新增空行取消正在回填的补全任务。
const inputRows = computed(() => {
  const last = props.rows.at(-1);
  if (props.autoAppend && props.rows.length < INPUT_LIMITS.maxTokens && (!last || rowUsed(last))) {
    // 待输入行变为正式内容后，为下一行分配全新身份，不能与上一行共用节点。
    if (props.rows.some(row => rowKey(row) === rowKey(trailingRow))) trailingRow = {};
    return [...props.rows, trailingRow];
  }
  return props.rows;
});
const pageCount = computed(() => props.paginated ? Math.max(1, Math.ceil(inputRows.value.length / pageSize)) : 1);
const shownRows = computed(() => {
  const offset = props.paginated ? (page.value - 1) * pageSize : 0;
  const rows = props.paginated ? inputRows.value.slice(offset, offset + pageSize) : inputRows.value;
  return rows.map((row, index) => ({ row, index: offset + index, key: rowKey(row) }));
});
watch(pageCount, value => { page.value = Math.min(page.value, value); });
const chinese = computed(() => props.subject === 'chinese');
const leftField = computed(() => chinese.value ? 'text' : 'meaning');
const rightField = computed(() => chinese.value ? 'pinyin' : 'english');
const leftLabel = computed(() => chinese.value ? '汉字' : '中文');
const rightLabel = computed(() => chinese.value ? '拼音' : '英语');
const usedCount = computed(() => props.rows.filter(row => row[leftField.value]?.trim() || row[rightField.value]?.trim()).length);
const fields = ['text', 'pinyin', 'meaning', 'english'] as const;
type PairField = typeof fields[number];

/** 两栏都有内容即完成一组，自动补全和手动填写采用同一判定。 */
function pairComplete(row: EditableWorksheetPair): boolean {
  return props.subject === 'chinese' ? !!(row.text?.trim() && row.pinyin?.trim())
    : !!(row.meaning?.trim() && row.english?.trim());
}

/** 任意一栏有非空白内容即预留下一行，不影响自动补全的一次性初始化。 */
function rowUsed(row: EditableWorksheetPair): boolean {
  return props.subject === 'chinese' ? !!(row.text?.trim() || row.pinyin?.trim())
    : !!(row.meaning?.trim() || row.english?.trim());
}

/** 编辑只改变当前单元格，完整旧配对和已初始化行始终保留用户修改。 */
function changedRow(row: EditableWorksheetPair, field: PairField, value: string): EditableWorksheetPair {
  const next = { ...row, [field]: value };
  if (row.automaticInitialized || pairComplete(row) || pairComplete(next)) next.automaticInitialized = true;
  if (field === 'pinyin') next.pinyinState = value.trim() ? 'confirmed' : 'missing';
  if (field === 'meaning') next.meaningState = value.trim() ? 'confirmed' : 'missing';
  if (field === 'english') next.englishState = value.trim() ? 'confirmed' : 'missing';
  return next;
}

/** 逐键保留完整输入缓冲，首尾空格由提交时统一处理。 */
function updateCell(index: number, field: PairField, value: string): void {
  pasteIssue.value = '';
  if (/[\r\n]/u.test(value)) { applyLines(index, field, value); return; }
  const rows = index < props.rows.length ? props.rows : inputRows.value;
  emit('update:rows', rows.map((row, position) => position === index ? changedRow(row, field, value) : row));
}

/** 批量输入按原行号填入同一列，空白行保留，不移动另一列已有内容。 */
function applyLines(index: number, field: PairField, value: string): void {
  const lines = value.replace(/\r\n?/gu, '\n').split('\n');
  if (Math.max(props.rows.length, index + lines.length) > INPUT_LIMITS.maxTokens) {
    pasteIssue.value = `最多支持 ${INPUT_LIMITS.maxTokens} 行，请分批粘贴。`;
    return;
  }
  // 从待输入末行粘贴时也沿用其界面身份，不能在展开多行时换掉当前文本框。
  const sourceRows = index < props.rows.length ? props.rows : inputRows.value;
  const rows = sourceRows.map(row => ({ ...row }));
  while (rows.length < index + lines.length) rows.push({});
  lines.forEach((line, offset) => { rows[index + offset] = changedRow(rows[index + offset]!, field, line); });
  emit('update:rows', rows);
  // 批量粘贴属于一次完整编辑，等待父组件接收数据后再提交。
  void nextTick(() => emit('commit'));
}

/** 多行粘贴替换从当前行开始的一列；单行粘贴保留输入框原生光标行为。 */
function pasteCell(event: ClipboardEvent, index: number, field: PairField): void {
  const text = event.clipboardData?.getData('text/plain');
  if (!text || !/[\r\n]/u.test(text)) return;
  event.preventDefault();
  applyLines(index, field, text);
}

/** 新增整组空白输入，不从其它行复制中文、英文或读音。 */
function addRow(): void {
  if (props.rows.length >= INPUT_LIMITS.maxTokens) return;
  const length = props.rows.length + 1;
  // 已有自动展示的空行时直接定位，不再追加第二个空行。
  if (inputRows.value.length === props.rows.length) emit('update:rows', [...props.rows, {}]);
  if (props.paginated) page.value = Math.ceil(length / pageSize);
  void nextTick(() => {
    editor.value?.querySelector<HTMLTextAreaElement>('.pair-row:last-child textarea')?.focus();
  });
}

/** 删除时两列一起移除，最后一行删除后保留一个可输入的空行。 */
function removeRow(index: number): void {
  const rows = props.rows.filter((_, position) => position !== index);
  emit('update:rows', rows.length ? rows : [{}]);
  void nextTick(() => emit('commit'));
}

/** 拼音既校验音节数量也校验格式；错误原样留在输入框供修正。 */
function rowIssue(row: EditableWorksheetPair): string {
  if (!chinese.value) return '';
  if (!row.pinyin?.trim()) return '';
  const syllables = row.pinyin.trim().split(/\s+/u);
  if (syllables.some(syllable => !isValidPinyinSyllable(syllable))) return '请填写有效拼音，例如 chūn tiān。';
  if (row.text?.trim() && syllables.length !== splitGraphemes(row.text.trim()).length) return '拼音音节数量需要与汉字一致。';
  return '';
}
</script>

<template>
  <section ref="editor" class="paired-editor" :aria-label="label">
    <header class="pair-header">
      <h2>{{ label }}</h2><span class="pair-count">{{ usedCount }} 项</span>
    </header>
    <p class="pair-hint">每行一组对应内容。可向任一输入框粘贴多行，按换行分项，短语中的空格会保留。</p>
    <p v-if="autoAppend" class="pair-hint">最后一行任意一栏有内容时，自动新增一行备用。</p>
    <p class="pair-hint">{{ chinese ? '新内容首次优先使用教材库补齐拼音，之后的修改和清空会原样保留。' : '新内容首次优先使用教材库补齐中英文，之后的修改和清空会原样保留。' }}</p>
    <div class="column-headings" aria-hidden="true"><span>{{ leftLabel }}</span><span>{{ rightLabel }}</span></div>
    <ol class="pair-rows">
      <li v-for="{ row, index, key } in shownRows" :key="key" class="pair-row" :class="{ 'has-issue': rowIssue(row) }">
        <span class="pair-number">{{ index + 1 }}</span>
        <label class="pair-cell left-cell">
          <span class="mobile-label">{{ leftLabel }}</span>
          <NInput :value="row[leftField] ?? ''" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }"
            :aria-label="`第 ${index + 1} 行${leftLabel}`" :placeholder="chinese ? '如：春天' : '如：冰淇淋'"
            @update:value="value => updateCell(index, leftField, value)" @paste="(event: ClipboardEvent) => pasteCell(event, index, leftField)" @blur="commitCell" />
        </label>
        <label class="pair-cell right-cell">
          <span class="mobile-label">{{ rightLabel }}</span>
          <NInput :value="row[rightField] ?? ''" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }"
            :aria-label="`第 ${index + 1} 行${rightLabel}`" :placeholder="chinese ? '如：chūn tiān（可自动生成）' : '如：ice cream（可自动翻译）'"
            @update:value="value => updateCell(index, rightField, value)" @paste="(event: ClipboardEvent) => pasteCell(event, index, rightField)" @blur="commitCell" />
        </label>
        <NButton v-if="index < rows.length" class="remove-pair" quaternary :aria-label="`删除第 ${index + 1} 行`" @click="removeRow(index)">删除</NButton>
        <p v-if="rowIssue(row)" class="row-issue" role="alert">{{ rowIssue(row) }}</p>
      </li>
    </ol>
    <p v-if="pasteIssue" class="row-issue" role="alert">{{ pasteIssue }}</p>
    <div v-if="pageCount > 1" class="pair-pagination" aria-label="内容分页">
      <NButton :disabled="page === 1" @click="page--">上一页</NButton>
      <span>第 {{ page }} / {{ pageCount }} 页</span>
      <NButton :disabled="page === pageCount" @click="page++">下一页</NButton>
    </div>
    <div class="pair-actions"><NButton :disabled="rows.length >= INPUT_LIMITS.maxTokens" @click="addRow">＋ 添加一行</NButton></div>
    <p class="completion-status pair-hint" role="status">{{ completing ? (chinese ? '正在补全拼音…' : '正在补全中英文…') : autoComplete ? completion.error.value || '每项仅自动补全一次；需要再次补空时请点击按钮。' : '' }}<NButton v-if="autoComplete" text :disabled="completing" @click="completion.completeNow(true)">{{ completion.error.value ? '重试' : '补全空白' }}</NButton></p>
  </section>
</template>

<style scoped>
.paired-editor { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.pair-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.pair-header h2 { margin: 0; font-size: 16px; }
.pair-count, .pair-number, .pair-hint { color: var(--muted); font-size: 12px; }
.pair-hint { margin: 0; line-height: 1.7; }
.column-headings { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 2px 68px 0 30px; font-weight: 600; font-size: 13px; }
.pair-rows { display: flex; flex-direction: column; gap: 8px; list-style: none; margin: 0; padding: 0; max-height: 560px; overflow: auto; scrollbar-gutter: stable; }
.pair-row { display: grid; grid-template-columns: 22px minmax(0, 1fr) minmax(0, 1fr) 56px; align-items: start; gap: 8px; padding: 4px 0; min-width: 0; border-radius: 8px; }
.pair-number { padding-top: 12px; text-align: center; }
.pair-cell { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.mobile-label { display: none; }
.pair-cell :deep(.n-input) { min-height: 44px; }
.pair-cell :deep(textarea) { overflow-wrap: anywhere; }
.remove-pair { min-height: 44px; }
.row-issue { grid-column: 2 / 4; margin: 0; color: #b42318; font-size: 12px; line-height: 1.6; }
.pair-row.has-issue { background: #fff4e9; }
.pair-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; font-size: 12px; color: var(--muted); }
.pair-pagination :deep(.n-button) { min-height: 44px; }
.pair-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.pair-actions :deep(.n-button) { min-height: 44px; }
.completion-status { min-height: 1.7em; }
@media (max-width: 560px) {
  .column-headings { display: none; }
  .pair-rows { gap: 12px; max-height: 65vh; }
  .pair-row { grid-template-columns: 22px minmax(0, 1fr) 50px; padding: 10px 6px; border: 1px solid var(--border); }
  .pair-number { padding-top: 28px; }
  .left-cell, .right-cell { grid-column: 2; }
  .right-cell { grid-row: 2; }
  .remove-pair { grid-column: 3; grid-row: 1; margin-top: 18px; }
  .mobile-label { display: block; font-size: 12px; font-weight: 600; color: var(--muted); }
  .row-issue { grid-column: 2 / 4; }
}
</style>
