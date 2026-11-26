<script setup lang="ts">
import { computed, ref } from 'vue';
import { NAlert, NButton, NInput, NSelect, NTag } from 'naive-ui';
import {
  cleanupBoundaryPunctuation, findBoundaryPunctuation, rawTextBytes,
  type ParsePolicy, type WorksheetInputDocument
} from '../../domain/worksheet-input';

/**
 * 统一原文编辑框。
 *
 * 契约 §2：`rawText` 是唯一真值，textarea 与它双向绑定；tokens 只是派生数据。
 * 标点清理必须由用户显式触发，并且可以撤销。
 */
const props = defineProps<{
  document: WorksheetInputDocument;
  /** 解析或导入产生的阻断信息。 */
  blockers: Array<{ code: string; detail?: string }>;
  /** 本次输入来源的说明，例如「教材：统编语文一年级上册 · 识字表」。 */
  sourceLabel: string;
  /** 追加导入的入口；由父组件决定加载哪一册。 */
  importing: boolean;
  /** 确认页只编辑统一原文，不再混入来源选择。 */
  showCurriculumShortcut?: boolean;
}>();

const emit = defineEmits<{
  'update:rawText': [value: string];
  'update:parsePolicy': [value: ParsePolicy];
  commitText: [];
  /** 请求把教材内容追加到当前原文。 */
  importTextbook: [];
  /** 请求清空原文。 */
  clear: [];
  /** 请求导入「文字 + 读音/释义」两列词条表。 */
  importPaired: [value: string];
}>();

const subjectLabel = computed(() => (props.document.subject === 'chinese' ? '语文' : '英语'));
const tokenCount = computed(() => props.document.tokens.length);
const byteCount = computed(() => rawTextBytes(props.document.rawText));
const hasBlockers = computed(() => props.blockers.length > 0);
/** 原文示例遵循当前分项规则，英语默认一行一个词或短语。 */
const rawPlaceholder = computed(() => (isChinese.value
  ? '春天 花朵 山水\n或者每行一项'
  : props.document.parsePolicy === 'one-per-line' ? 'apple\nbanana\nice cream' : 'apple banana\n"ice cream"'));

/** 高级入口：导入「文字 + 读音/释义」两列词条表。 */
const showPaired = ref(false);
const pairedText = ref('');
const isChinese = computed(() => props.document.subject === 'chinese');
/** 提交词条表；内容为空时不做任何事。 */
function submitPaired(): void {
  if (!pairedText.value.trim()) return;
  emit('importPaired', pairedText.value);
  // 父级可能拒绝有错误的输入，保留原文供修改或主动清空。
}

/**
 * 阻断码到可执行中文提示的映射。
 * 契约要求给用户可执行的说明，不能把内部错误码直接显示出来。
 */
const BLOCKER_MESSAGES: Record<string, string> = {
  WORKSHEET_MISSING_TEXT: '有条目是空的，无法生成题目。请删掉空行或补上内容。',
  WORKSHEET_MISSING_PINYIN: '部分字未能自动标注拼音，请在检查拼音中补齐。',
  WORKSHEET_INVALID_PINYIN: '有拼音音节无效，请修改为正确的拼音。',
  WORKSHEET_MISSING_ENGLISH: '部分内容未能自动翻译，请重试或在检查中英文中补齐英文。',
  WORKSHEET_INVALID_ENGLISH: '英文内容包含不支持的字符，请检查后修改。',
  WORKSHEET_MISSING_MEANING: '当前题型需要中文释义。请补充释义，或改选不需要释义的题型。',
  TOO_MANY_ITEMS: '选入的题目超过 200 项上限。请选择抽取数量后再生成，系统不会自动截取。',
  ITEM_TOO_LARGE: '有单个题面过长，超出纸面宽度。请缩短该条目。',
  FONT_UNAVAILABLE: '字体不可用，无法生成纸面。请刷新后重试。',
  GLYPH_MISSING: '有字不在内置字体里，无法保证字形正确。请删掉该字，或改用其它内容。',
  TITLE_TOO_LONG: '标题过长，超出纸面宽度。请缩短标题。',
  INCOMPLETE_ENTRY: '当前题型需要的内容还不完整（多半是缺读音或释义）。请补齐后再生成，或改选要求更少的题型。',
  WORKSHEET_IMPORT_NO_EDITION: '没有可用的已发布教材版本。可以改用手工输入或图片识别。',
  WORKSHEET_IMPORT_LOAD_FAILED: '教材内容加载失败。已保留当前原文，可重试。',
  WORKSHEET_MODE_SUBJECT_MISMATCH: '该题型不属于当前学科。请重新选择题型。',
  WORKSHEET_BALANCED_NEEDS_TEXTBOOK_UNITS: '均衡抽题需要全部条目都来自已发布教材。可改选其它抽取方式。'
};

/** 取阻断码对应的中文提示；未收录的码带上原始码便于排查，但不作为唯一信息。 */
function blockerText(blocker: { code: string; detail?: string }): string {
  const known = BLOCKER_MESSAGES[blocker.code];
  const base = known ?? `生成被阻断（${blocker.code}）。请检查当前内容与题型设置。`;
  return blocker.detail ? `${base}（${blocker.detail}）` : base;
}
const punctuationPositions = computed(() => findBoundaryPunctuation(props.document.rawText));
/** 上一次清理前的原文，用于撤销。 */
const undoText = ref<string | null>(null);
const showIssues = ref(true);

/** 按当前分项规则清理词边界标点；可撤销。 */
function convertPunctuation(): void {
  const cleaned = cleanupBoundaryPunctuation(props.document.rawText, props.document.parsePolicy === 'one-per-line' ? '\n' : ' ');
  if (!cleaned.replaced) return;
  undoText.value = props.document.rawText;
  emit('update:rawText', cleaned.text);
}

/** 撤销上一次标点清理。 */
function undo(): void {
  if (undoText.value === null) return;
  emit('update:rawText', undoText.value);
  undoText.value = null;
}
</script>

<template>
  <section class="text-editor" :aria-label="`${subjectLabel}原文`">
    <header class="editor-head">
      <h2>练习内容</h2>
      <div class="editor-counts">
        <NTag size="small" :bordered="false">{{ tokenCount }} 项</NTag>
      </div>
    </header>

    <p class="editor-hint">
      <template v-if="document.parsePolicy === 'one-per-line'">{{ isChinese ? '每行一项，可直接修改。' : '单词用换行隔开，每行一个单词或短语，可直接修改。' }}</template>
      <template v-else-if="isChinese">词语用空格分隔，可直接修改。</template>
      <template v-else>当前按旧方式分项；可在更多输入选项中切换为每行一项。</template>
    </p>

    <textarea
      class="editor-area"
      :value="document.rawText"
      spellcheck="false"
      rows="5"
      aria-label="原文"
      :placeholder="rawPlaceholder"
      @input="emit('update:rawText', ($event.target as HTMLTextAreaElement).value)"
      @blur="emit('commitText')"
    />

    <NAlert v-if="hasBlockers && showIssues" type="error" class="editor-issues" closable @close="showIssues = false">
      <ul class="issue-list">
        <li v-for="blocker in blockers" :key="`${blocker.code}-${blocker.detail ?? ''}`">
          {{ blockerText(blocker) }}
        </li>
      </ul>
    </NAlert>
    <details class="input-options">
      <summary>更多输入选项</summary>
      <div class="input-options-body">
        <div class="editor-toolbar">
          <label class="policy">
            <span>分项方式</span>
            <NSelect
              :value="document.parsePolicy"
              size="small"
              style="width: 150px"
              :options="isChinese ? [
                { label: '按空白分项', value: 'whitespace' },
                { label: '每行一项', value: 'one-per-line' }
              ] : [
                { label: '每行一项', value: 'one-per-line' },
                { label: '按空白分项（旧方式）', value: 'whitespace' }
              ]"
              @update:value="value => emit('update:parsePolicy', value as ParsePolicy)"
            />
          </label>
          <NButton v-if="showCurriculumShortcut !== false" size="small" :loading="importing" @click="emit('importTextbook')">追加教材内容</NButton>
          <NButton size="small" quaternary @click="showPaired = !showPaired">
            {{ showPaired ? '收起词条表' : '导入已有词条表' }}
          </NButton>
          <NButton v-if="punctuationPositions.length" size="small" @click="convertPunctuation">
            {{ document.parsePolicy === 'one-per-line' ? '转为换行分隔' : '转为空格分隔' }}（{{ punctuationPositions.length }} 处）
          </NButton>
          <NButton v-if="undoText !== null" size="small" quaternary @click="undo">撤销清理</NButton>
          <NButton size="small" quaternary :disabled="!document.rawText" @click="emit('clear')">清空</NButton>
        </div>

        <div v-if="showPaired" class="paired-entry">
          <p class="editor-hint">粘贴两列内容：{{ isChinese ? '汉字 + 拼音' : '单词 + 中文释义' }}。支持逗号、制表符或竖线分隔。</p>
          <NInput
            v-model:value="pairedText"
            type="textarea"
            :rows="5"
            :maxlength="24000"
            :placeholder="isChinese ? '山,shān' : 'apple,苹果'"
          />
          <div class="paired-actions">
            <NButton size="small" type="primary" :disabled="!pairedText.trim()" @click="submitPaired">导入词条表</NButton>
            <NButton size="small" quaternary @click="pairedText = ''">清空</NButton>
          </div>
        </div>
        <p class="editor-hint"><template v-if="sourceLabel">来源：{{ sourceLabel }} · </template>{{ byteCount }} / 65536 字节</p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.text-editor { display: flex; flex-direction: column; gap: 10px; }
.editor-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.editor-head h2 { margin: 0; font-size: 15px; }
.editor-counts { display: flex; gap: 6px; flex-wrap: wrap; }
.editor-hint { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.7; }
.editor-hint code { background: var(--surface-2, #f1f3f8); padding: 1px 4px; border-radius: 4px; }
.editor-area {
  width: 100%;
  min-height: 140px;
  padding: 12px;
  border: 1px solid var(--border, #d7dee8);
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.8;
  resize: vertical;
  font-family: inherit;
  /* textarea 默认是 UA 的白底黑字，不跟随主题；显式声明才能深浅一致。 */
  background: var(--surface, #fff);
  color: var(--ink, #192331);
}
.editor-area:focus { outline: 2px solid #646cff; outline-offset: 1px; }
.editor-toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.policy { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.input-options summary { cursor: pointer; color: var(--muted); font-size: 12px; width: fit-content; padding: 5px 0; }
.input-options-body { display: flex; flex-direction: column; gap: 12px; padding-top: 10px; }
.paired-entry { display: flex; flex-direction: column; gap: 8px; }
.paired-actions { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 720px) { .input-options summary { min-height: 44px; display: flex; align-items: center; } }
.editor-issues { font-size: 12px; }
.issue-list { margin: 0; padding-left: 18px; }
</style>
