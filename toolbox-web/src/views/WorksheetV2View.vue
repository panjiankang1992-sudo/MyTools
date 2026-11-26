<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, ref, watch } from 'vue';
import { NAlert, NButton, NFormItem, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, NTag } from 'naive-ui';
import WorksheetPairedEditor from '../components/worksheet/WorksheetPairedEditor.vue';
import WorksheetCurriculumPicker from '../components/worksheet/WorksheetCurriculumPicker.vue';
import type { WorksheetCurriculumImport } from '../composables/useWorksheetCurriculum';
import WorksheetImageInput from '../components/worksheet/WorksheetImageInput.vue';
import WorksheetPreview from '../components/WorksheetPreview.vue';
import { checkGeneratable, requirementFor } from '../domain/worksheet-processing';
import { processSession } from '../domain/worksheet-session';
import type { EditableWorksheetPair } from '../domain/worksheet-editable-pairs';
import type { WorksheetMode } from '../types';
import { useWorksheetV2Session } from '../composables/useWorksheetV2Session';
import { prepareWorksheetSourceText, useWorksheetWizard, type WorksheetSourceChoice } from '../composables/useWorksheetWizard';
import { candidateReadings } from '../lib/pinyin-adapter';

/** 三个独立步骤共用同一份原文与会话；返回只切换界面，不清数据。 */
const props = defineProps<{ subject: 'chinese' | 'english' }>();
const flow = useWorksheetV2Session(props.subject);
// 英语新建练习统一按行分项，教材和图片来源也保留短语内部空格；恢复草稿仍遵循其保存的规则。
if (props.subject === 'english') flow.applyParsePolicy('one-per-line');
const wizard = useWorksheetWizard();
const { stage, source } = wizard;
const pageActive = ref(true);
const customCompletionActive = computed(() => pageActive.value && stage.value === 'source' && source.value === 'custom');

/**
 * 待确认的读音条目，以及其中含多音字的条目。
 *
 * 契约 §3.1：自动生成的读音在被用户确认前都是"待确认"状态；
 * 含多音字的条目**不得**被批量确认替用户决定，必须逐项确认。
 * 这里把这两类分开计数，正是为了让界面如实呈现，而不是含糊地说"已补全"。
 */
const pendingReadings = computed(() => flow.session.value.document.tokens
  .filter(token => token.pinyinState === 'suggested'));
const pendingAmbiguous = computed(() => pendingReadings.value
  .filter(token => token.pinyinSegments.some(segment => candidateReadings(segment.grapheme).length > 1)));

/** 批量确认无歧义读音；含多音字的条目会保留待确认。 */
function confirmUnambiguous(): void {
  flow.confirmAll();
}
const imageSourceActive = computed(() => pageActive.value && stage.value === 'source' && source.value === 'image');
const customPairs = ref<EditableWorksheetPair[]>([{}]);
const confirmationPairs = ref<EditableWorksheetPair[]>([]);
const confirmationBaseline = ref<EditableWorksheetPair[]>([]);
const customDirty = ref(false);
const sourceIssue = ref('');
const confirmationIssue = ref('');
const textbookOpened = ref(false);
let committingPairs = false;
const settingsOpen = ref(false);
const imageInput = ref<{ cancel: () => void } | null>(null);
const customEditor = ref<{ cancelCompletion: () => void; flushCompletion: () => Promise<boolean> } | null>(null);
const submittingCustom = ref(false);
const {
  session, blockers, notice, artifact, page, zoom, generating, editablePairs, importEditablePairs,
  modeOptions, applyRawText, applyParsePolicy, applyMode,
  generateNow, canOutput, selectedMode, processSummary,
  needsCountChoice,
  automaticPreparing, automaticError, prepareAutomaticFields,
  saveDraft, dropDraft, hasStoredDraft, artifactStale,
  applyLayout, applyProcessing, answerArtifact, canGenerateAnswer, canOutputAnswer, answerGenerating, generateAnswerNow, downloadAnswerPdf
} = flow;
const selection = computed(() => session.value.processing.selection);
const requestedCount = computed(() => session.value.processing.requestedCount ?? 200);
const validInput = computed(() => session.value.document.tokens.length > 0 && !session.value.document.parseIssues.length);
const hasExistingText = computed(() => session.value.document.rawText.trim().length > 0);
const errorText = flow.errorMessage;
const modeChosen = computed(() => selectedMode.value !== null);
const reviewLabel = computed(() => props.subject === 'chinese' ? '检查拼音' : '检查中英文');
const requiredReviewCount = computed(() => {
  const mode = selectedMode.value;
  if (!mode) return 0;
  return processSession(session.value).items.filter(token => !checkGeneratable([token], mode, 'student').ok).length;
});
const pairedInputInvalid = computed(() => !!confirmationIssue.value);
const customHasContent = computed(() => customPairs.value.some(row => props.subject === 'chinese' ? row.text?.trim() || row.pinyin?.trim() : row.meaning?.trim() || row.english?.trim()));
const answerHint = computed(() => {
  if (!canGenerateAnswer.value) return '当前只有一列内容，可以生成练习；填写完整的两列内容后可生成参考答案。';
  const required = requirementFor(selectedMode.value ?? '', 'answer');
  if (required.needsConfirmedPinyin) return '使用自动生成的拼音，也可返回上一步修改。';
  if (required.needsMeaning) return '使用自动补全的中文释义，也可返回上一步修改。';
  return '可另存一份参考答案。';
});
const sourceOptions: Array<{ value: WorksheetSourceChoice; label: string; hint: string }> = [
  { value: 'textbook', label: '选教材', hint: '按年级、单元选择' },
  { value: 'custom', label: '自己输入', hint: '输入或粘贴字词' },
  { value: 'image', label: '上传图片', hint: '识别图片里的文字' }
];

/** 自动补全回写未编辑字段；正在输入的内容始终以本地缓冲为准。 */
watch(editablePairs, rows => {
  if (committingPairs) return;
  const previous = new Map(confirmationBaseline.value.map(row => [row.tokenId, row]));
  const current = new Map(rows.map(row => [row.tokenId, row]));
  const knownIds = new Set(confirmationPairs.value.map(row => row.tokenId).filter(Boolean));
  const merged = confirmationPairs.value.flatMap(row => {
    if (!row.tokenId) return [{ ...row }];
    const latest = current.get(row.tokenId);
    if (!latest) return [];
    const baseline = previous.get(row.tokenId);
    const result = { ...latest };
    for (const field of ['text', 'pinyin', 'meaning', 'english'] as const) {
      if (row[field] !== baseline?.[field]) {
        result[field] = row[field];
        if (field === 'pinyin') result.pinyinState = row.pinyinState;
        if (field === 'meaning') result.meaningState = row.meaningState;
        if (field === 'english') result.englishState = row.englishState;
      }
    }
    return [result];
  });
  merged.push(...rows.filter(row => !knownIds.has(row.tokenId)).map(row => ({ ...row })));
  confirmationPairs.value = merged.length ? merged : [{}];
  confirmationBaseline.value = rows.map(row => ({ ...row }));
}, { immediate: true, flush: 'sync' });
watch(needsCountChoice, needed => { if (needed) settingsOpen.value = true; });

/** 返回自定义输入时沿用对应表中的内容，不要求重新粘贴。 */
function syncCustomPairs(): void {
  customPairs.value = confirmationPairs.value.length ? confirmationPairs.value.map(row => ({ ...row })) : [{}];
  customDirty.value = false;
}

/** 每次修改先保留缓冲，失焦或下一步时再更新会话。 */
function updateConfirmationPairs(rows: EditableWorksheetPair[]): void {
  // 缓冲尚未失焦提交时，也必须立刻停止旧输入的补全，不能拼上旧原文的译文。
  flow.cancelAutomaticFields();
  confirmationPairs.value = rows;
  confirmationIssue.value = '';
}

/** 原子提交配对表，空行不参与导出，但继续留在编辑位置。 */
function commitConfirmation(complete = true): boolean {
  const rows = confirmationPairs.value;
  const unchanged = JSON.stringify(rows.filter(row => props.subject === 'chinese' ? row.text?.trim() || row.pinyin?.trim() : row.meaning?.trim() || row.english?.trim())) === JSON.stringify(editablePairs.value);
  if (unchanged && !blockers.value.length) { confirmationIssue.value = ''; return true; }
  committingPairs = true;
  const ok = importEditablePairs(rows, 'replace', 'custom');
  committingPairs = false;
  const applied = editablePairs.value;
  // 结构错误保持会话不变，缓冲中的错误行必须保留供用户修正。
  if (!ok) {
    confirmationIssue.value = errorText.value || '请检查每行的对应内容和拼音。';
    return false;
  }
  let index = 0;
  confirmationPairs.value = rows.map(row => {
    const active = props.subject === 'chinese' ? row.text?.trim() || row.pinyin?.trim() : row.meaning?.trim() || row.english?.trim();
    return active ? { ...applied[index++]! } : { ...row, tokenId: undefined };
  });
  confirmationBaseline.value = applied.map(row => ({ ...row }));
  confirmationIssue.value = '';
  if (complete) void prepareAutomaticFields();
  return true;
}

/** 自定义来源一次导入两列，返回修改时按行身份保留对应关系。 */
async function applyCustomPairs(): Promise<void> {
  if (submittingCustom.value) return;
  if (!customHasContent.value) { sourceIssue.value = '请至少填写一组内容。'; return; }
  // 等待当前首次处理完成后连同初始化标记导入，不能取消后再向确认页重复请求。
  submittingCustom.value = true;
  let unchanged = true;
  try { unchanged = await (customEditor.value?.flushCompletion() ?? Promise.resolve(true)); }
  finally { submittingCustom.value = false; }
  if (!unchanged || !customCompletionActive.value) return;
  if (!importEditablePairs(customPairs.value, 'replace', 'custom')) {
    sourceIssue.value = errorText.value || '请检查每行的对应内容和拼音。';
    return;
  }
  confirmationIssue.value = '';
  customDirty.value = false;
  customPairs.value = editablePairs.value.map(row => ({ ...row }));
  confirmationPairs.value = editablePairs.value.map(row => ({ ...row }));
  confirmationBaseline.value = editablePairs.value.map(row => ({ ...row }));
  wizard.resetApplied();
  continueToConfirm();
}

/** 题型变化后准备所需字段，已有人工输入由会话层保护。 */
function chooseMode(value: string | number): void {
  if (modeOptions.value.find(option => option.value === value)?.disabled) return;
  applyMode(String(value) as WorksheetMode);
  void prepareAutomaticFields();
}

/** 离开图片来源时取消识别，保留图片、候选和选区供返回编辑。 */
watch([stage, source], () => {
  if (stage.value !== 'source' || source.value !== 'image') imageInput.value?.cancel();
});

// KeepAlive 离开路由不会卸载组件，必须立即取消请求，并关闭隐藏来源的防抖补全。
onDeactivated(() => {
  pageActive.value = false;
  customEditor.value?.cancelCompletion();
  imageInput.value?.cancel();
});
onActivated(() => {
  pageActive.value = true;
  // 只对新增未初始化行补全，已处理或取消的内容保留原样。
  if (stage.value === 'confirm') void prepareAutomaticFields();
});

/** 选择来源仅切换界面，已有原文等待显式追加或替换。 */
function chooseSource(value: WorksheetSourceChoice): void {
  sourceIssue.value = '';
  if (value === 'textbook') textbookOpened.value = true;
  if (value === 'custom' && !customDirty.value) syncCustomPairs();
  wizard.chooseSource(value);
}
/** 返回数据来源，不重新导入上次的内容。 */
function returnToSource(): void {
  if (stage.value !== 'source') {
    commitConfirmation(false);
    if (source.value === 'custom') syncCustomPairs();
  }
  flow.goBackToEdit(); wizard.backTo('source');
}
/** 返回统一确认页，保留全部文本、校对和版式。 */
function returnToConfirm(): void { flow.goBackToEdit(); wizard.backTo('confirm'); }
/** 清空统一原文时同时作废候选应用记录。 */
function clearAll(): void {
  flow.clearAll(); wizard.resetApplied(); customPairs.value = [{}];
  confirmationPairs.value = [{}]; confirmationBaseline.value = [];
  sourceIssue.value = ''; confirmationIssue.value = ''; customDirty.value = false;
}
/** 输入有效才进入确认；解析错误保持可编辑并显示提示。 */
function continueToConfirm(): void {
  if (wizard.toConfirm(validInput.value)) { sourceIssue.value = ''; flow.prepareConfirmation(); }
  else sourceIssue.value = '请先准备有效内容；检查是否为空或引号未闭合。';
}
/** 已经导入的同一候选再次继续时只导航，不重复追加。 */
function applySourceText(choice: 'image', mode: 'append' | 'replace', text: string, pairs?: EditableWorksheetPair[]): void {
  const incomingPolicy = 'one-per-line';
  const signature = JSON.stringify([incomingPolicy, text, pairs]);
  if (!wizard.wasApplied(choice, signature) || mode === 'replace') {
    if (pairs) {
      if (!importEditablePairs(pairs, mode, 'image')) { sourceIssue.value = errorText.value || '请检查识别结果的对应内容。'; return; }
    } else {
      const prepared = prepareWorksheetSourceText(session.value.document.rawText, session.value.document.parsePolicy, text, incomingPolicy, mode);
      if (!prepared.ok) { sourceIssue.value = '请检查输入内容，当前内容保持不变。'; return; }
      if (prepared.parsePolicy !== session.value.document.parsePolicy) applyParsePolicy(prepared.parsePolicy);
      applyRawText(prepared.rawText);
    }
    if (!validInput.value) { sourceIssue.value = errorText.value || '请修正输入内容后继续。'; return; }
    if (mode === 'replace') wizard.resetApplied();
    wizard.markApplied(choice, signature);
  }
  confirmationPairs.value = editablePairs.value.map(row => ({ ...row }));
  confirmationBaseline.value = editablePairs.value.map(row => ({ ...row }));
  confirmationIssue.value = '';
  continueToConfirm();
}
/** 所选教材范围以完整版本身份导入，切换题型不影响本次选择。 */
function importCurriculum(selected: WorksheetCurriculumImport, mode: 'append' | 'replace'): void {
  const signature = JSON.stringify([selected.editionId, selected.contentVersion, selected.entries.map(entry => entry.entryId)]);
  if (!wizard.wasApplied('textbook', signature) || mode === 'replace') {
    flow.importCurriculumEntries(selected.entries, selected.editionId, selected.contentVersion, selected.label,
      { grade: selected.grade, semester: selected.semester }, mode);
    if (!validInput.value || blockers.value.length) { sourceIssue.value = errorText.value || '教材内容导入失败，请检查所选范围。'; return; }
    if (mode === 'replace') wizard.resetApplied();
    wizard.markApplied('textbook', signature);
  }
  confirmationPairs.value = editablePairs.value.map(row => ({ ...row }));
  confirmationBaseline.value = editablePairs.value.map(row => ({ ...row }));
  confirmationIssue.value = '';
  continueToConfirm();
}
/** 恢复草稿后直接进入统一确认页，不把恢复内容再次作为来源追加。 */
function restoreDraft(): void {
  flow.loadDraft(); wizard.resetApplied();
  confirmationPairs.value = editablePairs.value.map(row => ({ ...row }));
  confirmationBaseline.value = editablePairs.value.map(row => ({ ...row }));
  confirmationIssue.value = '';
  if (validInput.value) { wizard.toConfirm(true); flow.prepareConfirmation(); }
}
/** 数量策略变化直接更新会话，草稿恢复后控件也能同步。 */
function onSelectionChange(value: string): void {
  applyProcessing({ selection: value as 'all' | 'random-count' | 'balanced-unit', requestedCount: requestedCount.value });
}
/** 设置选题数量。 */
function onCountChange(value: number | null): void { applyProcessing({ requestedCount: value }); }
/** 只有真实生成成功并且产物仍有效才进入预览，失败停留确认页。 */
async function generatePreview(): Promise<void> {
  if (automaticPreparing.value || generating.value) return;
  if (stage.value === 'preview') { wizard.backTo('confirm'); }
  if (!commitConfirmation(false)) { focusEntries(); return; }
  await generateNow();
  if (wizard.toPreview(canOutput.value)) flow.goOutput();
  else if (requiredReviewCount.value) {
    void nextTick(() => document.querySelector('.confirm-step .paired-editor')?.scrollIntoView({ block: 'center' }));
  } else if (errorText.value) settingsOpen.value = true;
}
/** 未提交的拼音不能被旧已确认值替代用于答案输出。 */
async function generateAnswer(): Promise<void> {
  if (automaticPreparing.value || generating.value || answerGenerating.value || pairedInputInvalid.value || !canGenerateAnswer.value) return;
  await generateAnswerNow();
}
/** 答案下载也检查编辑缓冲，避免旧答案被误认为当前答案。 */
function downloadAnswer(): void {
  if (!pairedInputInvalid.value) downloadAnswerPdf();
}
/** 预览空态回到确认并定位内容，避免显示不可操作的选择按钮。 */
function focusEntries(): void {
  returnToConfirm();
  void nextTick(() => document.querySelector('.confirm-step .paired-editor')?.scrollIntoView({ block: 'center' }));
}
</script>

<template>
  <div class="worksheet-v2">
    <h1>{{ subject === 'chinese' ? '语文字帖' : '单词练习' }}</h1>
    <ol class="steps" aria-label="字帖制作步骤">
      <li :class="{ active: stage === 'source' }"><button type="button" :aria-current="stage === 'source' ? 'step' : undefined" @click="returnToSource">1 选内容</button></li>
      <li :class="{ active: stage === 'confirm' }"><button type="button" :disabled="stage !== 'preview'" :aria-current="stage === 'confirm' ? 'step' : undefined" @click="returnToConfirm">2 确认内容</button></li>
      <li :class="{ active: stage === 'preview' }"><span :aria-current="stage === 'preview' ? 'step' : undefined">3 预览打印</span></li>
    </ol>

    <section v-show="stage === 'source'" class="source-step" aria-label="第一步：选择数据来源">
      <div class="source-cards">
        <button v-for="option in sourceOptions" :key="option.value" type="button" class="source-card" :class="{ selected: source === option.value }" :aria-pressed="source === option.value" @click="chooseSource(option.value)">
          <strong>{{ option.label }}</strong><span>{{ option.hint }}</span>
        </button>
      </div>
      <p v-if="subject === 'english'" class="process-hint">自动翻译会将待翻译字词发送至翻译服务。</p>
      <div v-if="hasExistingText && !(source === 'custom' && customDirty)" class="saved-content">
        <span>已保留 {{ session.document.tokens.length }} 项内容</span>
        <NButton text @click="continueToConfirm">继续编辑 →</NButton>
      </div>
      <WorksheetCurriculumPicker v-if="textbookOpened" v-show="source === 'textbook'" :subject="subject" :has-existing-text="hasExistingText" @import="importCurriculum" />
      <section v-show="source === 'custom'" class="custom-source" aria-label="输入自定义内容">
        <WorksheetPairedEditor ref="customEditor" :subject="subject" :rows="customPairs" :auto-complete="customCompletionActive" :paginated="false" auto-append label="输入内容" @update:rows="rows => { customPairs = rows; customDirty = true; }" />
        <div class="step-actions">
          <NButton type="primary" :loading="submittingCustom" :disabled="!customHasContent || submittingCustom" @click="applyCustomPairs">下一步</NButton>
        </div>
      </section>
      <WorksheetImageInput v-show="source === 'image'" ref="imageInput" :active="imageSourceActive" :input-revision="session.document.revision" :subject="subject" :has-existing-text="hasExistingText" @apply="(mode, text, pairs) => applySourceText('image', mode, text, pairs)" />
      <NAlert v-if="sourceIssue" type="error" :show-icon="false">{{ sourceIssue }}</NAlert>
      <NButton v-if="hasStoredDraft" text class="restore-draft" @click="restoreDraft">恢复上次保存的草稿</NButton>
    </section>

    <section v-show="stage === 'confirm'" class="confirm-step" aria-label="第二步：数据确认">
      <WorksheetPairedEditor :subject="subject" :rows="confirmationPairs" label="确认内容" :busy="automaticPreparing"
        @update:rows="updateConfirmationPairs" @commit="commitConfirmation()" />
      <div class="confirmation-actions">
        <NButton :loading="automaticPreparing" :disabled="automaticPreparing || !validInput" @click="commitConfirmation(false) && prepareAutomaticFields(true)">{{ subject === 'chinese' ? '补全拼音' : '补全中英文' }}</NButton>
        <!-- 批量确认只处理无歧义读音；含多音字的条目必须由用户逐项决定（契约 §3.1）。 -->
        <NButton v-if="subject === 'chinese'" :disabled="!pendingReadings.length" @click="confirmUnambiguous">批量确认</NButton>
        <NTag v-if="pendingAmbiguous.length" size="small" type="warning" :bordered="false">
          {{ pendingAmbiguous.length }} 项待确认（含多音字，需逐项核对）
        </NTag>
        <NTag v-else-if="pendingReadings.length" size="small" :bordered="false">
          {{ pendingReadings.length }} 项待确认
        </NTag>
        <NButton quaternary :disabled="!hasExistingText" @click="clearAll">清空内容</NButton>
        <span v-if="requiredReviewCount && !automaticPreparing" class="required-hint">{{ requiredReviewCount }} 项待补全</span>
      </div>
      <NAlert v-if="confirmationIssue" type="warning" :show-icon="false">{{ confirmationIssue }}</NAlert>
      <section class="mode-section" aria-label="选择字帖样式">
        <h2>选择字帖样式</h2>
        <p class="process-hint">可以只填一列，按已填写的内容选择字帖样式。</p>
        <NRadioGroup :value="selectedMode" :disabled="!session.document.tokens.length" class="mode-options" @update:value="chooseMode">
          <NRadio v-for="option in modeOptions" :key="option.value" :value="option.value" :disabled="option.disabled" class="mode-option" :class="{ chosen: selectedMode === option.value }">
            <strong>{{ option.label }}</strong><span class="mode-hint">{{ option.hint }}</span>
            <span v-if="option.disabled" class="mode-hint">{{ option.unavailableHint }}</span>
          </NRadio>
        </NRadioGroup>
        <p v-if="validInput && modeOptions.every(option => option.disabled)" class="required-hint">每行需提供同一侧的内容，才能使用同一种字帖样式。</p>
      </section>
      <NAlert v-if="automaticError" type="warning" :show-icon="false">
        {{ automaticError }} <NButton text @click="prepareAutomaticFields(true)">重试自动补全</NButton>
      </NAlert>
      <details class="optional-section" :open="settingsOpen" @toggle="settingsOpen = ($event.target as HTMLDetailsElement).open">
        <summary>更多设置<span class="optional-hint">排版、数量、草稿</span></summary>
        <div class="optional-content">
          <div class="count-row">
            <span class="count-label">题目数量</span>
            <NSelect
              :value="selection"
              size="small"
              style="width: 140px"
              :options="[
                { label: '全部选入', value: 'all' },
                { label: '随机抽取', value: 'random-count' },
                { label: '按教材单元均衡抽取', value: 'balanced-unit' }
              ]"
              @update:value="onSelectionChange"
            />
            <NInputNumber
              v-if="selection !== 'all'"
              :value="requestedCount"
              size="small"
              style="width: 120px"
              :min="1"
              :max="200"
              @update:value="onCountChange"
            />
            <span v-if="needsCountChoice && selection === 'all'" class="count-warn">
              超过 200 项上限，请改为随机抽取，系统不会自动截取
            </span>
          </div>

          <div class="layout-settings" aria-label="字帖排版设置">
            <NFormItem label="字帖标题" :show-feedback="false" class="wide-field">
              <NInput :value="session.layout.title" placeholder="可选，留空使用默认标题" aria-label="字帖标题" @update:value="value => applyLayout({ title: value })" />
            </NFormItem>
            <NFormItem label="格距" :show-feedback="false">
              <NSelect :value="session.layout.gridMm" :options="[10, 12, 14].map(value => ({ label: `${value} mm`, value }))" aria-label="格距" @update:value="value => applyLayout({ gridMm: value })" />
            </NFormItem>
            <NFormItem label="每项重复次数" :show-feedback="false">
              <NSelect :value="session.processing.repetitions" :options="[1, 2, 3, 4, 5].map(value => ({ label: `${value} 次`, value }))" aria-label="每项重复次数" @update:value="value => applyProcessing({ repetitions: value as 1 | 2 | 3 | 4 | 5 })" />
            </NFormItem>
            <NFormItem v-if="subject === 'chinese'" label="练习单位" :show-feedback="false">
              <NSelect :value="session.processing.chineseUnit" :options="[{ label: '整词练习', value: 'word' }, { label: '逐字练习', value: 'character' }]" aria-label="练习单位" @update:value="value => applyProcessing({ chineseUnit: value })" />
            </NFormItem>
            <NFormItem label="重复词条" :show-feedback="false">
              <NSelect :value="session.processing.duplicatePolicy" :options="[{ label: '合并相同内容', value: 'merge-equivalent' }, { label: '保留每次出现', value: 'keep-occurrences' }]" aria-label="重复词条" @update:value="value => applyProcessing({ duplicatePolicy: value })" />
            </NFormItem>
          </div>


          <div class="draft-row">
            <NButton size="small" @click="saveDraft">保存草稿</NButton>
            <NButton v-if="hasStoredDraft" size="small" @click="restoreDraft">恢复草稿</NButton>
            <NButton v-if="hasStoredDraft" size="small" quaternary @click="dropDraft">删除草稿</NButton>
            <span class="process-hint">仅保存在本机，不含图片。</span>
          </div>
        </div>
      </details>
      <NAlert v-if="errorText" type="error" :show-icon="false">{{ errorText }}</NAlert>
      <div class="step-actions">
        <NButton @click="returnToSource">上一步</NButton>
        <NButton type="primary" :disabled="!modeChosen || !validInput || automaticPreparing" :loading="generating || automaticPreparing" @click="generatePreview">生成预览</NButton>
        <span class="process-hint">{{ processSummary }}</span>
      </div>
      <!-- 失焦保存会更新提示，放在按钮之后避免按下与抬起期间按钮发生位移。 -->
      <p v-if="notice" class="process-hint" role="status">{{ notice }}</p>
    </section>

    <section v-if="stage === 'preview'" class="preview-pane" aria-label="第三步：数据预览">
      <div class="step-actions preview-actions">
        <NButton @click="returnToConfirm">上一步</NButton>
        <NButton type="primary" :disabled="!canOutput || automaticPreparing" @click="flow.printPreview">打印</NButton>
        <NButton :disabled="!canOutput || automaticPreparing" @click="flow.downloadPdf">下载 PDF</NButton>
      </div>
      <NAlert v-if="errorText" type="error" :show-icon="false">{{ errorText }} <NButton text @click="focusEntries">返回检查</NButton></NAlert>
      <NAlert v-if="artifactStale" type="warning" :show-icon="false">内容已修改，请返回上一步重新生成。</NAlert>
      <WorksheetPreview :artifact="artifact" :page="page" :zoom="zoom" :busy="generating" :error="''" @retry="generatePreview" @select="focusEntries" />
      <div v-if="artifact" class="preview-controls">
        <div v-if="artifact.pageCount > 1" class="page-controls">
          <NButton size="small" :disabled="page <= 1" @click="page--">上一页</NButton>
          <span>第 {{ page }} / {{ artifact.pageCount }} 页</span>
          <NButton size="small" :disabled="page >= artifact.pageCount" @click="page++">下一页</NButton>
        </div>
        <span v-else>共 1 页</span>
        <NButton text :disabled="!canOutput" @click="zoom = zoom === 'fit' ? 'actual' : 'fit'">{{ zoom === 'fit' ? '放大查看' : '适应窗口' }}</NButton>
      </div>
      <p v-if="notice" class="process-hint" role="status">{{ notice }}</p>
      <details class="optional-section">
        <summary>需要参考答案？</summary>
        <div class="optional-content">
          <p class="process-hint">{{ answerHint }}</p>
          <NAlert v-if="pairedInputInvalid" type="warning" :show-icon="false">请先完成正在编辑的拼音。<NButton text @click="focusEntries">返回检查</NButton></NAlert>
          <div class="output-actions">
            <NButton v-if="!canOutputAnswer || pairedInputInvalid" :disabled="!modeChosen || !canGenerateAnswer || generating || automaticPreparing || pairedInputInvalid" :loading="answerGenerating || automaticPreparing" @click="generateAnswer">生成答案 PDF</NButton>
            <NButton v-else :disabled="automaticPreparing" @click="downloadAnswer">下载答案 PDF</NButton>
            <span v-if="answerArtifact && canOutputAnswer && !pairedInputInvalid" class="process-hint">{{ answerArtifact.pageCount }} 页</span>
            <NButton text @click="focusEntries">{{ reviewLabel }}</NButton>
          </div>
        </div>
      </details>
    </section>
  </div>
</template>
<style scoped>
.worksheet-v2 { display: flex; flex-direction: column; gap: 20px; min-width: 0; max-width: 960px; }
h1 { margin: 0; font-size: 22px; }
h2 { margin: 0; font-size: 15px; }
.steps { display: flex; list-style: none; padding: 0; margin: 0; gap: 8px; }
.steps li { flex: 1; border-radius: 8px; background: var(--surface-2, #f1f3f8); color: var(--ink); }
.steps li.active { background: #555de0; color: #fff; }
.steps button, .steps span { display: flex; align-items: center; justify-content: center; min-height: 44px; width: 100%; border: 0; border-radius: inherit; background: transparent; color: inherit; font: inherit; font-size: 13px; padding: 0 4px; box-sizing: border-box; }
.steps button:not(:disabled) { cursor: pointer; }
.steps button:disabled { opacity: 1; }
.source-step, .confirm-step, .custom-source, .preview-pane { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.source-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.source-card { display: flex; flex-direction: column; gap: 5px; align-items: flex-start; padding: 18px; background: var(--surface); color: var(--ink); border: 1px solid var(--border); border-radius: 10px; cursor: pointer; text-align: left; min-width: 0; }
.source-card strong { font-size: 16px; }
.source-card span { color: var(--muted); font-size: 12px; line-height: 1.6; }
.source-card.selected { border-color: #646cff; box-shadow: inset 0 0 0 1px #646cff; }
.saved-content { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.restore-draft { align-self: flex-start; }
.mode-section { display: flex; flex-direction: column; gap: 10px; }
.mode-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.mode-option { padding: 14px; border: 1px solid var(--border); border-radius: 10px; margin: 0; min-width: 0; }
.mode-option.chosen { border-color: #646cff; }
.mode-hint { display: block; color: var(--muted); font-size: 12px; margin-top: 4px; }
.optional-section { border-top: 1px solid var(--border); padding-top: 4px; min-width: 0; }
.optional-section summary { cursor: pointer; min-height: 44px; padding: 10px 0; box-sizing: border-box; font-size: 13px; line-height: 24px; }
.optional-content { display: flex; flex-direction: column; gap: 14px; padding: 8px 0; min-width: 0; }
.optional-hint, .required-hint { margin-left: 8px; color: var(--muted); font-size: 12px; }
.required-hint { color: #b45309; }
.process-hint { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.step-actions, .output-actions, .confirmation-actions, .fill-row, .draft-row, .count-row, .page-controls, .preview-controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.step-actions { padding-top: 6px; }
.step-actions :deep(.n-button) { min-height: 44px; }
.count-label, .preview-controls { font-size: 13px; color: var(--muted); }
.count-warn { color: #b45309; font-size: 12px; }
.layout-settings { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.wide-field { grid-column: 1 / -1; }
@media (pointer: coarse), (max-width: 720px) {
  .worksheet-v2 :deep(.n-button:not(.n-button--circle)), .worksheet-v2 :deep(.n-input), .worksheet-v2 :deep(.n-base-selection) { min-height: 44px; }
}
@media (max-width: 480px) {
  .worksheet-v2 { gap: 16px; }
  .source-card { padding: 14px 8px; align-items: center; text-align: center; }
  .source-card strong { font-size: 14px; }
  .source-card span { display: none; }
  .mode-option { padding: 12px 8px; }
  .mode-option :deep(.n-radio__label) { padding-left: 6px; }
  .layout-settings { grid-template-columns: minmax(0, 1fr); }
  .step-actions > .process-hint { flex-basis: 100%; }
}
</style>
