<script setup lang="ts">
import {
  NAlert, NButton, NCheckbox, NCheckboxGroup, NCollapse, NCollapseItem, NFormItem, NInput, NSelect, NSpace, NTag
} from 'naive-ui';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import WorksheetPairedEditor from '../../components/worksheet/WorksheetPairedEditor.vue';
import type { EditableWorksheetPair } from '../../domain/worksheet-editable-pairs';
import type { ListType } from '../../domain/curriculum';
import { SaveState, newSaveKey, type FrozenSave } from '../../lib/admin-save-queue';
import { useAdminAuthStore } from '../../stores/admin-auth';
import {
  applyCompletionResults, beginAutomaticCompletion, computeContentDigest, entriesToPairs, markConfirmed,
  mergeSubset, pairHasAnyContent, pairsToEntries, selectCompletionTargets, type DraftEntry
} from '../../lib/admin-textbook-draft';
import { completePinyinFromCurriculum } from '../../lib/curriculum-pinyin';

import WorksheetPreview from '../../components/WorksheetPreview.vue';
import VersionHistoryDrawer from '../../components/admin/VersionHistoryDrawer.vue';
import { useAdminDraftPreview } from '../../composables/useAdminDraftPreview';
import {
  coercePreviewMode, defaultPreviewMode, previewModeLabel, previewModeOptions
} from '../../lib/admin-preview-mode';
import { describePreviewScope } from '../../lib/admin-preview-scope';
import {
  ADMIN_LIST_TYPE_ORDER, EMPTY_SOURCE_REF, applySourceRefToRows, isSourceRefEmpty, listTypeLabel,
  matchesListTypeFilter, primarySourceRefOf, setListTypesForRows, type EntrySourceRef
} from '../../lib/admin-entry-metadata';
import {
  CONFLICT_DIFF_LABELS, diffDraftEntries, resolveConflictPanel, summarizeConflictDiff, type ConflictDiff
} from '../../lib/admin-draft-conflict';
import { buildPublishIntent } from '../../lib/admin-publish-request';
import type { WorksheetMode } from '../../types';
import { parseBulkRows, selectNewItems } from '../../lib/admin-bulk-paste';
import { formatIssue } from '../../lib/admin-validation-messages';
import { adminErrorMessage } from '../../lib/admin-error-messages';
import { UndoStack } from '../../lib/admin-textbook-history';
import {
  addLesson, addUnit, detachRemovedLessons, moveNode, removeLesson, removeUnit, renderTree,
  detachRemovedUnit, renameLesson, renameUnit, resolvePasteTarget, type Outline
} from '../../lib/admin-textbook-outline';
import { gradesForSubject } from '../../lib/admin-textbook-view';
import { AdminApiError } from '../../services/admin-api';
import {
  createTextbook, fetchDraft, publishDraft, saveDraft, validateDraft, type AdminValidationResult,
  translateEntriesForAdmin
} from '../../services/admin-textbooks-api';

/**
 * 教材编辑页（设计文档 §3.4）。新增与修改共用本页。
 *
 * 本页已实现：教材信息表单 + 双列内容录入 + 草稿保存 + 校验 + 发布 +
 * 左侧单元／课文目录树 + 批量粘贴 + 版本记录抽屉（`VersionHistoryDrawer`）+ 草稿预览。
 *
 * **本页尚未实现**：图片识别录入（OCR 录入界面在公开字帖链路的
 * `components/worksheet/WorksheetImageInput.vue`，管理编辑页没有接）。
 *
 * 说明：本注释此前写着「尚未实现：左侧单元／课文目录树、批量粘贴、图片识别、
 * **版本记录**、草稿预览」——其中版本记录等项后来**已经实现**，那句注释在撒谎。
 * 因此这里按实际状态重写，只留真实未达成项；版本记录里"内容对比"（设计 166 行）
 * 与"修改摘要"的后端字段缺口，如实写在抽屉组件与验证文档里。
 */

const props = defineProps<{ editionId?: string }>();

/** 鉴权状态：编辑缓冲的所有权与它绑定（F02）。 */
const auth = useAdminAuthStore();
const canWrite = computed(() => auth.canWrite);
const router = useRouter();

/** 新增与修改共用一个页面，用 edtionId 是否存在区分。 */
const isNew = computed(() => !props.editionId);

const subject = ref<'chinese' | 'english'>('chinese');
const grade = ref<number | null>(null);
const semester = ref<'first' | 'second'>('first');
const publisher = ref('');
const series = ref('');
const editionLabel = ref('');
const sourceNote = ref('');
const coverage = ref<'partial' | 'complete'>('partial');
// F03：设计 104/105 行要求草稿携带**适用地区代码**与**可选的课标年份／ISBN**。
//
// **这两个 ref 不只是为了将来加控件**：`buildDraft()` 会把它们一起 PUT 上去，
// 而接口 PUT 的是**整份草稿**——若这里不带，界面保存一次就会把
// 通过接口设好的地区／ISBN **悄悄丢掉**。所以先保住往返，再加控件。
const regionCodes = ref<string[]>([]);
const curriculumYear = ref<number | null>(null);
const isbn = ref('');

/**
 * 地区候选（**只列常用几个**）。
 *
 * 完整的行政区划代码表是一份独立的数据，不该塞进这个组件里——
 * 界面先支持"选全国／江苏／几个常见省份"，其余靠 `filterable` 手输代码。
 * 设计 104 行要求**存代码、显示名称**，所以选项的 `value` 是代码。
 */
const regionOptions = [
  { label: '全国', value: '000000' },
  { label: '江苏', value: '320000' },
  { label: '浙江', value: '330000' },
  { label: '上海', value: '310000' },
  { label: '北京', value: '110000' }
];

/** 年份是可选数字：空串按"未填"处理，不写 0（0 是一个合法的错误值）。 */
function onCurriculumYearInput(value: string): void {
  const trimmed = value.trim();
  if (!trimmed) {
    curriculumYear.value = null;
    return;
  }
  const parsed = Number(trimmed);
  curriculumYear.value = Number.isFinite(parsed) ? Math.trunc(parsed) : curriculumYear.value;
}
const title = ref('');

const rows = ref<EditableWorksheetPair[]>([{}]);
const entries = ref<DraftEntry[]>([]);
const revision = ref(0);
const savedAt = ref('');
const busy = ref(false);
const message = ref('');
const errorText = ref('');
/**
 * 发布请求体的三个字段（设计第 362 行）。
 *
 * `baseContentVersion` 只能从**读取草稿**得到（保存响应只回修订号），
 * 因此这里随 `load()`／"读取最新"一起更新。
 */
const baseContentVersion = ref<string | null>(null);
/** 修改摘要（设计 166 行：版本记录含修改摘要）；设计未规定必填，允许留空。 */
const changeNote = ref('');
/**
 * 保存冲突面板（设计 §3.7）。
 *
 * 以前 409 只显示一句"已被其他人修改过，请刷新"——用户拿不到差异，
 * 也不知道刷新会丢掉什么。这里记下面板需要的全部状态。
 */
const conflict = ref<{ expectedRevision: number | null; abandonedRetry: boolean } | null>(null);
/** 本地与服务器条目的差异；"查看差异"后才有值。 */
const conflictDiff = ref<ConflictDiff | null>(null);
/** 冲突面板里的状态文案（读取失败、差异基于哪一版等）。 */
const conflictNotice = ref('');
/** "读取最新"的二次确认是否展开（设计：读取最新前确认）。 */
const readLatestConfirming = ref(false);
/**
 * 读取最新前**暂存到内存**的本地改动（设计原文：「当前本地改动暂存在内存」）。
 *
 * 不是"直接丢弃"：读最新会用服务器版本替换编辑器，但暂存一份之后
 * 用户可以把它放回来，再基于最新修订重新提交。
 */
const stashedLocal = ref<{ entries: DraftEntry[]; outline: Outline } | null>(null);
/** 表类别筛选（设计 115 行）；空数组表示不筛选。 */
const listTypeFilter = ref<ListType[]>([]);
/** 给当前范围设置默认类别时使用的集合。 */
const defaultListTypes = ref<ListType[]>([]);
/** 给当前范围设置默认来源与页码时使用的一条来源。 */
const defaultSourceRef = ref<EntrySourceRef>({ ...EMPTY_SOURCE_REF });
const validation = ref<AdminValidationResult | null>(null);
const outline = ref<Outline>({ units: [], lessons: [] });
/**
 * 服务端持久化的「临时标识 → 正式标识」映射。
 *
 * **必须原样回传**：服务端保存的是调用方发来的整份草稿，若这里固定发 `{}`，
 * 每次保存都会抹掉映射，发布时就会按位置重新分配正式 ID——
 * 于是"插入一个单元"会让其后所有单元的 ID 变化。
 */
const idMapping = ref<Record<string, string>>({});
/** 当前选中的目录节点，用于把内容过滤到该单元／课文。 */
const selectedNode = ref<{ kind: 'unit' | 'lesson' | 'all'; id: string }>({ kind: 'all', id: '' });
const newUnitTitle = ref('');
const newLessonTitle = ref('');
const newAppendixTitle = ref('');
/**
 * 撤销栈（设计文档 B03 要求"重命名／重排／删除后**撤销**"）。
 * 采用快照式：**任何改动结构的操作之前**先记一份，正确性不依赖每个操作各自写对逆操作。
 */
const history = new UndoStack<{ outline: Outline; entries: DraftEntry[] }>();

/**
 * 在改动结构之前记录一份可撤销快照。
 *
 * @param label 即将执行的操作名，用于撤销提示
 */
function recordBeforeChange(label: string): void {
  history.record({ outline: outline.value, entries: entries.value }, label);
}

/**
 * 同级移动，并记录可撤销快照。
 *
 * @param kind  节点类型
 * @param id    节点标识
 * @param delta 位移（-1 上移，1 下移）
 */
function moveOutline(kind: 'unit' | 'lesson', id: string, delta: number): void {
  recordBeforeChange('调整顺序');
  outline.value = moveNode(outline.value, kind, id, delta);
}

/** 撤销最近一次结构改动。 */
function undo(): void {
  const snapshot = history.undo();
  if (!snapshot) {
    notice.value = '没有可撤销的操作。';
    return;
  }
  outline.value = snapshot.state.outline;
  entries.value = snapshot.state.entries;
  syncRowsToSelection();
  notice.value = `已撤销：${snapshot.label}。`;
}
const pasteText = ref('');
const pasteNotice = ref('');
const notice = ref('');
const previewOpen = ref(false);
/**
 * 版本记录抽屉的可见状态（设计 3.6 第 166 行）。
 *
 * 设计第 176 行要求「弹窗关闭焦点回触发入口」。这里**刻意不自己写归还逻辑**：
 * `NDrawer` 的 `VFocusTrap` 自带 `returnFocusOnDeactivated`，实测（本地回环真浏览器，
 * 见 `version-history-and-keyboard.md`）Escape／关闭按钮两条路径都把焦点放回了
 * 「版本记录」触发按钮。删掉自己那段之后，同一条键盘路径仍然 17/17 通过 ——
 * 所以没有再加一份重复实现。若将来换掉抽屉实现，这条断言会立刻发现。
 */
const versionDrawerOpen = ref(false);
/**
 * 草稿预览：与公共字帖走**同一条渲染管线**（建会话 → 导入条目 → 处理 → 生成 → 逐页渲染），
 * 只读、不写草稿、不产生发布。
 */
// F10：**学科与题型都跟着编辑器走，不能写死**。
//
// 此前这里是 `{ mode: 'chinese-pinyin', subject: 'chinese' }`——
// 英语教材的预览也走语文链路，预览出来的东西与这本教材无关。
const previewMode = ref<WorksheetMode>(defaultPreviewMode('chinese'));
const draftPreview = useAdminDraftPreview({
  subject: () => subject.value,
  // 学科切换后把不匹配的题型纠回来：用户在语文下选了拼音，切到英语时必须换成英语题型。
  mode: () => coercePreviewMode(subject.value, previewMode.value)
});

// 学科一变，题型立刻跟到该学科的默认值。
// F02：**鉴权一旦失效，立刻清空编辑缓冲**。
//
// 审查的反例：A 退出后 B 登录，**B 看到 A 的缓冲**。
// 编辑缓冲只在内存里（不落 localStorage），所以"换账号清空"必须由组件自己做：
// 编辑器此前**完全没有接鉴权状态**，于是 A 的内容会一直留在页面上。
//
// 为什么是"清空"而不是"只禁用按钮"：禁用只挡住**新的写入**，
// 而屏幕上仍然显示着上一个人的内容——那既是信息泄露，也会让人以为那是自己的草稿。
watch(canWrite, allowed => {
  if (allowed) return;
  entries.value = [];
  rows.value = [{}];
  outline.value = { units: [], lessons: [] };
  selectedNode.value = { kind: 'all', id: '' };
  revision.value = 0;
  history.clear();
  resetConflictState();
  stashedLocal.value = null;
  notice.value = '登录状态已失效，编辑内容已清空。';
}, { immediate: false });

watch(subject, next => {
  previewMode.value = coercePreviewMode(next, previewMode.value);
});

/**
 * 预览范围说明（设计 164 行：「使用当前草稿的已选范围」）。
 *
 * 范围就是**左侧目录里选中的那个节点**（整册／单元／课文），它同时决定：
 * - 编辑器里显示与录入哪些条目；
 * - `openPreview()` 发给后端预览接口的 `nodeKind`／`nodeKey`（整册时两个都不传）。
 *
 * 因此这里给出的名称与限制说明必须与**真实请求**一致：
 * 说"当前范围：单元：第一单元"，预览里就只有该单元；范围取不到时如实报错，不退化成整册。
 */
const previewScope = computed(() => {
  const node = selectedNode.value;
  const lesson = node.kind === 'lesson'
    ? outline.value.lessons.find(item => item.clientLessonId === node.id)
    : undefined;
  const titles = node.kind === 'unit'
    ? { unitTitle: outline.value.units.find(unit => unit.clientUnitId === node.id)?.title }
    : node.kind === 'lesson'
      ? {
          lessonTitle: lesson?.title,
          // 课文的父单元要带出来：预览该课文时后端会把所属单元一并返回。
          unitTitle: outline.value.units.find(unit => unit.clientUnitId === lesson?.clientUnitId)?.title
        }
      : {};
  return describePreviewScope({ kind: node.kind, id: node.id }, titles);
});
/** 当前学科可选的两种题型（语文两型／英语两型）。 */
const previewModeChoices = computed(() => previewModeOptions(subject.value));
/** 表类别筛选选项。 */
const listTypeFilterOptions = computed(() => ADMIN_LIST_TYPE_ORDER.map(type => ({
  label: listTypeLabel(type, subject.value),
  value: type
})));

/**
 * 解析粘贴内容并追加到当前选中节点下。
 * 每行一条、剥离句末标点、去重保留首次出现；**不按空格拆分**，因为词语与短语内部允许有空格。
 */
function onBulkPaste(): void {
  // 批量粘贴会一次改动多行，同样应当可撤销。
  recordBeforeChange('批量粘贴');
  // 课文所属单元从**目录**解析：从"已有条目"里查会在一篇空课文上退化成"未分单元"，
  // 把内容挂到不属于该课文的单元下。
  const target = resolvePasteTarget(outline.value, selectedNode.value);
  // F12：按**双列行**解析，而不是扁平文本。
  //
  // 此前 `明天见\tSee you tomorrow!` 会被当成**一条正文**——
  // 制表符连同两列内容挤进左列，导入后汉字格里混着英文。
  const rows = parseBulkRows(pasteText.value);
  if (!rows.length) {
    pasteNotice.value = '没有可添加的内容。';
    return;
  }
  // 判重按**左格**（语文是汉字、英语是中文），且只在目标节点内：
  // 同一个词出现在不同课文时必须都保留。
  const picked = selectNewItems(rows.map(row => row.left), entries.value, target);
  const skipped = picked.skippedCount;
  // 从原行里取回左右两格。
  //
  // **不能只写 `picked.items.includes(row.left)`**：那样在输入里有重复行时
  // 会把**两行都留下**（`selectNewItems` 只保留了一条，`includes` 却对两条都为真），
  // 于是"去重"看起来做了、实际没做。
  const consumed = new Set<string>();
  const kept = rows.filter(row => {
    if (!picked.items.includes(row.left)) return false;
    if (consumed.has(row.left)) return false;
    consumed.add(row.left);
    return true;
  });
  const startIndex = entries.value.length;
  const appended: DraftEntry[] = kept.map((row, index) => ({
    rowId: `row-${startIndex + index + 1}-${Date.now().toString(36)}`,
    entryId: null,
    unitId: null,
    clientUnitId: target.clientUnitId,
    lessonId: null,
    clientLessonId: target.clientLessonId,
    // 语文：左汉字右拼音；英语：左中文右英文——与双列语义一致。
    text: subject.value === 'english' ? (row.right || row.left) : row.left,
    pinyin: subject.value === 'chinese' ? (row.right || null) : null,
    meaning: subject.value === 'english' ? (row.left || null) : null,
    // 设计 115 行：类别只表达教材来源。新行不再写死空数组——
    // 管理员可以先用"默认类别"表达"这一批都来自词语表"，避免逐条勾选。
    listTypes: [...defaultListTypes.value],
    confirmed: false
  }));
  // 设计 297 行：给当前范围设置默认来源后，新录入的内容也带同一条来源，
  // 而不是只有"设置默认来源"那一刻已存在的行才有。
  entries.value = [...entries.value, ...appendWithDefaultSource(appended)];
  syncRowsToSelection();
  pasteText.value = '';
  pasteNotice.value = `新增 ${appended.length} 条；忽略重复 ${skipped} 条。`;
}

/** 目录树渲染数据，按 order 排序。 */
const tree = computed(() => renderTree(outline.value));

/** 当前应展示的条目：选中节点时只显示归属该节点的内容。 */
const visibleEntries = computed(() => entriesForNode(selectedNode.value));

/**
 * 就地重命名。
 * 用最小交互完成；重命名**不改变标识**，因此不影响已分配的正式 ID。
 */
function renamePrompt(kind: 'unit' | 'lesson', id: string, current: string): void {
  const next = window.prompt(kind === 'unit' ? '单元名称' : '课文标题', current);
  if (next === null) return;
  const title = next.trim();
  if (!title) return;
  recordBeforeChange(kind === 'unit' ? '重命名单元' : '重命名课文');
  outline.value = kind === 'unit' ? renameUnit(outline.value, id, title) : renameLesson(outline.value, id, title);
}

/**
 * 自动补全当前可见内容的读音／释义。
 *
 * 复用公共字帖的教材优先补全，未命中的拼音才使用本机候选，
 * 规则遵循"**先标记再补全**"——即使补全失败也不会在下次输入时重复补，
 * 满足用户要求的「一条内容只自动补全一次」。
 *
 * @param force 为真时忽略标记，显式重新补全
 */
async function runAutomaticCompletion(force = false): Promise<void> {
  if (!canWrite.value) return;
  const current = rows.value;
  const began = beginAutomaticCompletion(current, force);
  // 先落标记：失败、取消都不能让下一次输入再补一遍。
  rows.value = began.pairs;
  if (!began.eligibleIds.size) {
    notice.value = '没有需要补全的内容（每条内容只自动补全一次）。';
    return;
  }
  // F06：**持久化闸门**——先把"已补全标记"与新行落库，成功后才发补全请求。
  //
  // 此前标记只放在**内存**里：用户补全后刷新页面，`automaticInitialized` 就丢了，
  // 于是同一行会被**再补一次**（违反"一条内容最多首次补全一次"）。
  // 落库之后，"补过"这件事才真正持久。
  await save();
  if (errorText.value) {
    // 保存失败：**不发请求**。否则标记没落库、内容却变了，
    // 下次打开会再补一遍——比"这次没补上"更糟。
    notice.value = '保存失败，已跳过自动补全。';
    return;
  }
  if (subject.value === 'chinese') {
    const targets = selectCompletionTargets(rows.value, 'chinese', began.eligibleIds);
    if (!targets.length) return;
    inFlightCompletion?.abort();
    const controller = new AbortController();
    inFlightCompletion = controller;
    const generation = ++completionGeneration;
    const actor = auth.userId;
    const scope = JSON.stringify([selectedNode.value, listTypeFilter.value]);
    const snapshots = new Map(rows.value.map((pair, index) => [pair.tokenId ?? `row-${index + 1}`, JSON.stringify([pair.text, pair.pinyin, pair.pinyinState])]));
    const candidates = await completePinyinFromCurriculum(targets.map(item => ({ id: item.rowId, text: item.text })),
      controller.signal, translateEntriesForAdmin);
    if (controller.signal.aborted || generation !== completionGeneration || !canWrite.value || auth.userId !== actor
      || subject.value !== 'chinese' || JSON.stringify([selectedNode.value, listTypeFilter.value]) !== scope) return;
    let filled = 0;
    const filledRows = rows.value.map((pair, index) => {
      const id = pair.tokenId ?? `row-${index + 1}`;
      if (snapshots.get(id) !== JSON.stringify([pair.text, pair.pinyin, pair.pinyinState]) || pair.pinyin?.trim()) return pair;
      const suggestion = candidates.find(item => item.id === id && item.sourceText === pair.text?.trim())?.suggestion;
      if (!suggestion?.segments.length || suggestion.unresolvedIndexes.length) return pair;
      filled++;
      return { ...pair, pinyin: suggestion.segments.map(segment => segment.syllable).join(' '), pinyinState: 'suggested' as const };
    });
    rows.value = filledRows;
    onRowsUpdate(filledRows);
    notice.value = `已自动补全 ${filled} 条拼音，可直接修改（不会再次自动覆盖）。`;
    return;
  }

  // 英语：复用公开翻译接口，只对本次允许补全且**缺少一侧**的行请求。
  const targets = selectCompletionTargets(rows.value, 'english', began.eligibleIds);
  if (!targets.length) {
    notice.value = '没有需要补全的内容（每条内容只自动补全一次）。';
    return;
  }
  // F06：**取消上一次仍在途的补全请求**（如果有）。
  //
  // 此前 `AbortController` 建了、signal 也传了，但**从来没调用过 `.abort()`**——
  // 于是用户改了原文之后，旧请求仍会跑完并把结果送回来（结果会被
  // `sourceText` 校验丢掉，但**网络与上游额度已经浪费了**）。
  inFlightCompletion?.abort();
  const controller = new AbortController();
  inFlightCompletion = controller;
  // 记下本次代次：回包时若已经有更新的一次发起，就丢弃这份结果。
  const generation = (completionGeneration += 1);
  try {
    // F06：走**管理端**入口，额度按 `admin:<管理员标识>` 记，与访客主体分开。
    const results = await translateEntriesForAdmin(
      targets.map(item => ({ id: item.rowId, text: item.text })),
      controller.signal
    );
    // F06：**旧代次的回包不得写回**。
    //
    // 与下面的 `sourceText` 校验是两个维度：那一个挡"原文改过"，
    // 这一个挡"原文没改、但这是被超越的一次请求"。
    if (generation !== completionGeneration) {
      return;
    }
    // F06：把**发起时的原文**带进结果。
    //
    // 合并只按 rowId 匹配不够：行身份是稳定的（F04 特意保证），
    // 用户在我们等待回包期间把 `cat` 改成 `dog` 时 rowId 不变——
    // 于是 `cat` 的释义会安到 `dog` 的行上，得到 `dog` / `猫`。
    // 带上原文，合并时才校验得出"这份结果已经过期"。
    const requestedText = new Map(targets.map(item => [item.rowId, item.text]));
    const merged = applyCompletionResults(rows.value, began.eligibleIds,
      results.map(item => ({
        rowId: item.id,
        sourceText: requestedText.get(item.id),
        meaning: item.meaning,
        english: item.englishText
      })));
    rows.value = merged.pairs;
    onRowsUpdate(merged.pairs);
    notice.value = `已自动补全 ${merged.filledCount} 条，可直接修改（不会再次自动覆盖）。`;
  } catch {
    // 翻译失败不能影响编辑；标记已经落下，因此这次不会重试，避免反复打扰。
    notice.value = '补全请求失败，可手工填写（本条内容不会再次自动补全）。';
  }
}

/**
 * 确认当前可见内容的正确性。
 *
 * 摘要在**确认的这一刻**计算并随确认一起保存；服务端据此判断"确认之后内容有没有被改过"。
 * 只有拿到摘要的条目才会被标为已核对——**不产生"确认了但服务端无法验证"的中间态**。
 */
async function confirmVisible(): Promise<void> {
  // 先把编辑器里的最新内容合并回条目，避免确认的是旧内容。
  onRowsUpdate(rows.value);
  const visible = visibleEntries.value;
  if (!visible.length) {
    notice.value = '没有可确认的内容。';
    return;
  }
  busy.value = true;
  try {
    const digests = new Map<string, string>();
    for (const entry of visible) {
      digests.set(entry.rowId, await computeContentDigest({
        text: entry.text, pinyin: entry.pinyin, meaning: entry.meaning
      }));
    }
    entries.value = markConfirmed(entries.value, digests);
    syncRowsToSelection();
    notice.value = `已确认 ${digests.size} 项。内容若再改动，确认会被自动撤销。`;
  } catch {
    notice.value = '确认失败（浏览器不支持所需加密接口），未做任何修改。';
  } finally {
    busy.value = false;
  }
}

/**
 * 打开草稿预览并生成（设计 164 行）。
 *
 * **预览必须用"当前编辑内容"**：预览接口读的是服务器上**已保存**的草稿，
 * 而自动保存有 2 秒防抖——不阻塞地直接预览，用户可能看到的是**保存前的内容**
 * （连范围也可能是旧的：范围节点同样来自草稿）。
 * 因此这里先 flush（取消防抖、立即保存并等待成功），再带着**保存后的修订号**与当前范围预览。
 * 保存没成功就取消预览，而不是拿旧内容糊弄。
 */
async function openPreview(): Promise<void> {
  if (isNew.value) {
    notice.value = '请先保存草稿，再预览。';
    return;
  }
  if (conflict.value) {
    notice.value = '存在保存冲突：请先查看差异或读取最新，再预览。';
    return;
  }
  draftPreview.release();
  const flushed = await flushPendingSave();
  if (!flushed) {
    notice.value = errorText.value
      ? '当前编辑内容未能保存成功，已取消预览（避免预览到保存前的内容）。'
      : '正在保存，请稍候再点预览。';
    return;
  }
  previewOpen.value = true;
  // 题型与范围都跟着编辑器走；范围**真的进入请求**（`nodeKind`／`nodeKey`，
  // 整册时两个都不传），修订号用 flush 之后拿到的最新值（后端会核对）。
  const ok = await draftPreview.generate(props.editionId as string, {
    revision: revision.value,
    scope: previewScope.value.label,
    scopeNode: selectedNode.value
  });
  if (!ok) notice.value = draftPreview.error.value;
}

/**
 * 把防抖中的自动保存立刻执行并等待完成。
 *
 * @returns 是否已经处于"本地内容全部已保存"的状态
 */
async function flushPendingSave(): Promise<boolean> {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer);
    autoSaveTimer = null;
  }
  if (!saveStateRef.value.dirty) {
    // 没有待保存的改动：当前修订号就是内容的修订号。
    return !errorText.value;
  }
  if (busy.value) {
    // 有保存在途：此时无法保证"预览的就是刚保存的那一版"，让调用方给出提示。
    return false;
  }
  await save();
  return !errorText.value;
}

/** 关闭预览并释放产物。 */
function closePreview(): void {
  previewOpen.value = false;
  draftPreview.release();
}

/** 切换预览页。 */
function selectPreviewPage(): void {
  const total = draftPreview.pageCount.value;
  if (total > 0) draftPreview.page.value = (draftPreview.page.value % total) + 1;
}

/** 新增单元。 */
function onAddUnit(): void {
  const title = newUnitTitle.value.trim();
  if (!title) return;
  recordBeforeChange('新增单元');
  outline.value = addUnit(outline.value, title);
  newUnitTitle.value = '';
}

/**
 * 新增附录。
 *
 * 设计文档 §114：**英语附录作为命名单元承载**，不需要独立的数据结构，
 * 也不得为了满足界面而生成虚构课文。因此这里只是"新增一个单元并给出附录默认名"。
 * 设计 §111 把目录工具写作「新增单元／附录」，两者都应有入口。
 */
function onAddAppendix(): void {
  const title = newAppendixTitle.value.trim();
  if (!title) return;
  recordBeforeChange('新增附录');
  outline.value = addUnit(outline.value, title);
  newAppendixTitle.value = '';
}

/** 在选中单元（或未分单元）下新增课文。 */
function onAddLesson(): void {
  const title = newLessonTitle.value.trim();
  if (!title) return;
  recordBeforeChange('新增课文');
  const parent = selectedNode.value.kind === 'unit' ? selectedNode.value.id : '';
  outline.value = addLesson(outline.value, parent, title);
  newLessonTitle.value = '';
}

/** 删除单元：连同其下课文，并解除相关条目的课文归属，避免悬空引用。 */
function onRemoveUnit(clientUnitId: string): void {
  recordBeforeChange('删除单元');
  const result = removeUnit(outline.value, clientUnitId);
  outline.value = result.outline;
  entries.value = detachRemovedLessons(entries.value, result.removedLessonIds);
  // 还必须解绑**该单元下所有条目**：只清课文会让条目继续指向一个已删除的单元，
  // 形成悬空引用（发布时才以难懂的错误暴露）。
  entries.value = detachRemovedUnit(entries.value, clientUnitId);
  if (selectedNode.value.id === clientUnitId) selectedNode.value = { kind: 'all', id: '' };
}

/** 删除课文并解除条目归属。 */
function onRemoveLesson(clientLessonId: string): void {
  recordBeforeChange('删除课文');
  outline.value = removeLesson(outline.value, clientLessonId);
  entries.value = detachRemovedLessons(entries.value, [clientLessonId]);
  if (selectedNode.value.id === clientLessonId) selectedNode.value = { kind: 'all', id: '' };
}

const gradeOptions = computed(() => gradesForSubject(subject.value).map(value => ({ label: `${value} 年级`, value })));

/**
 * 切换学科。
 *
 * 学科决定"左右两栏各是什么"（语文：汉字／拼音；英语：英文／释义），因此切换会改变
 * **已有内容的解释方式**。另一学科的字段会被保留（切换可逆），但用户需要知情。
 */
function onSubjectChange(next: 'chinese' | 'english'): void {
  const changed = next !== subject.value;
  subject.value = next;
  if (changed && entries.value.length) {
    notice.value = '已切换学科：左右两栏的含义随之改变。另一学科已填写的内容会保留，切回即可恢复。';
  }
}

/** 组装提交给服务端的草稿对象。 */
function buildDraft(): Record<string, unknown> {
  return {
    editionId: props.editionId ?? title.value.trim(),
    subject: subject.value,
    grade: grade.value ?? 0,
    semester: semester.value,
    publisher: publisher.value.trim() || null,
    series: series.value.trim() || null,
    editionLabel: editionLabel.value.trim() || null,
    sourceNote: sourceNote.value.trim() || null,
    coverageDeclaration: coverage.value,
    regionCodes: [...regionCodes.value],
    curriculumYear: curriculumYear.value,
    isbn: isbn.value.trim() || null,
    // 目录树进入草稿：单元与课文都要带上，否则发布时条目会缺失归属。
    units: outline.value.units.map(unit => ({
      unitId: unit.unitId, clientUnitId: unit.clientUnitId, title: unit.title, order: unit.order
    })),
    lessons: outline.value.lessons.map(lesson => ({
      lessonId: lesson.lessonId, clientLessonId: lesson.clientLessonId,
      unitId: null, clientUnitId: lesson.clientUnitId, title: lesson.title, order: lesson.order
    })),
    entries: entries.value,
    idMapping: idMapping.value
  };
}

/** 编辑器行变化：只把可见子集的改动合并回去，其他单元的内容不受影响。 */
function onRowsUpdate(next: EditableWorksheetPair[]): void {
  // 当前选中节点决定**新增行**的归属；'all' 不指定归属。
  const owner = selectedNode.value.kind === 'unit'
    ? { clientUnitId: selectedNode.value.id, clientLessonId: null }
    : selectedNode.value.kind === 'lesson'
      ? { clientUnitId: null, clientLessonId: selectedNode.value.id }
      : {};
  const visibleIds = visibleEntries.value.map(entry => entry.rowId);
  const updatedSubset = pairsToEntries(next, visibleEntries.value, subject.value, owner);
  entries.value = mergeSubset(entries.value, visibleIds, updatedSubset);
  // **把生成的身份回填给编辑器行**。
  //
  // 此前这里直接 `rows.value = next`，而 `next` 是编辑器原始的、**没有 tokenId** 的行。
  // 于是下一次输入时 `pairsToEntries` 又把它当成新行、**再生成一个新 rowId**——
  // 同一行在两次输入之间换了身份，既让归属和已确认状态丢失，
  // 也让"稳定行"这个前提失效。
  // 与 `pairsToEntries` 用**同一个判空规则**取非空行，再按对象身份对齐。
  //
  // 不能按下标直接对齐：`updatedSubset` 是过滤后的结果，
  // 只要原文里有一行两侧全空排在前面，后面所有行都会错位一个——
  // 那会把 A 行的身份安到 B 行头上，比不填更糟。
  const nonEmpty = next.filter(pair => pairHasAnyContent(pair, subject.value));
  const identityOf = new Map<EditableWorksheetPair, string>();
  nonEmpty.forEach((pair, index) => {
    const entry = updatedSubset[index];
    if (entry) {
      identityOf.set(pair, entry.rowId);
    }
  });
  rows.value = next.length
    ? next.map(pair => (pair.tokenId || !identityOf.has(pair)
        ? pair
        : { ...pair, tokenId: identityOf.get(pair) }))
    : [{}];
  // F05：内容变了就标脏并安排一次自动保存（约 2 秒防抖）。
  // **加载期间不标脏**：那是程序在填内容，不是用户在改。
  // **保存过程中的同步也不标脏**：同理 —— 那是保存自己在写，不是用户新改的。
  // 少了后半个条件，`save()` 每次都会把自己重新标脏、再排下一次保存，形成无限写库循环。
  if (!loadingDraft && !syncingForSave) {
    markDirtyAndSchedule();
  }
}

/** 切换目录节点时把编辑器换成该节点的内容。 */
function syncRowsToSelection(): void {
  const visible = visibleEntries.value;
  rows.value = visible.length ? entriesToPairs(visible, subject.value) : [{}];
}

watch(selectedNode, (_next, previous) => {
  // **切换节点前先提交当前输入缓冲**（设计第 118 行：不能悄悄丢失）。
  //
  // 编辑器持续 emit `update:rows`，但"持续"不等于"已经发生"：
  // 输入法组词中、或用户打完字立刻点目录树，最后一次输入可能还没上抛。
  // 此时 `syncRowsToSelection()` 会用**新节点**的行覆盖 `rows`，
  // 那些尚未合并进 `entries` 的字就**无声消失**了。
  if (previous) {
    commitRowsFor(previous);
  }
  syncRowsToSelection();
});

/**
 * 切换范围后**真的重新取预览**（不是只改文案）。
 *
 * **必须注册在上面那个 watcher 之后**：那个 watcher 负责把旧节点未提交的输入
 * 合并回条目、再把编辑器换成新节点的内容。若预览抢先一步，
 * `save()` 里的 `onRowsUpdate()` 会拿着**旧节点的行**按**新节点的可见范围**合并——
 * 把上一单元的内容写进刚选中的单元。注册顺序就是这里的正确性依赖。
 *
 * 只在预览已打开时触发：没打开时点"预览草稿"本来就会带上当前范围。
 * 复用 `openPreview()`，因此同样先 flush、保存失败就不换预览。
 */
watch(selectedNode, () => {
  if (!previewOpen.value) return;
  void openPreview();
});

/**
 * 把当前输入缓冲按**指定节点**的可见范围提交进条目列表。
 *
 * 必须在切换节点**之前**、用**旧**节点调用：新节点的可见范围已经把旧节点的行排除了。
 *
 * @param node 提交时依据的节点（切换前的选中节点）
 */
function commitRowsFor(node: { kind: 'all' | 'unit' | 'lesson'; id: string }): void {
  const visibleIds = entriesForNode(node).map(entry => entry.rowId);
  if (!visibleIds.length) {
    return;
  }
  const updatedSubset = pairsToEntries(rows.value, entriesForNode(node), subject.value);
  entries.value = mergeSubset(entries.value, visibleIds, updatedSubset);
}

/**
 * 按指定节点过滤条目。
 *
 * 与 `visibleEntries` 同规则，但接收显式节点——切换过程中需要按**旧**节点取一次。
 *
 * 表类别筛选在这里一并生效：它必须同时作用于"编辑器显示的可见子集"与
 * "合并回条目列表的范围"（`onRowsUpdate` / `commitRowsFor` 都走本函数）。
 * 若只过滤显示、合并仍按整节点，被筛掉的行会在下一次输入时被当成"已删除"而消失。
 *
 * @param node 目标节点
 * @returns 该节点可见的条目
 */
function entriesForNode(node: { kind: 'all' | 'unit' | 'lesson'; id: string }): DraftEntry[] {
  const scoped = node.kind === 'all'
    ? entries.value
    : node.kind === 'unit'
      ? entries.value.filter(entry => entry.clientUnitId === node.id)
      : entries.value.filter(entry => entry.clientLessonId === node.id);
  return scoped.filter(entry => matchesListTypeFilter(entry, listTypeFilter.value));
}

/** 新追加的条目套用"当前范围默认来源"（设计 297 行）。 */
function appendWithDefaultSource(appended: readonly DraftEntry[]): DraftEntry[] {
  if (isSourceRefEmpty(defaultSourceRef.value)) return [...appended];
  return applySourceRefToRows(appended, appended.map(entry => entry.rowId), defaultSourceRef.value);
}

/**
 * 批量改动类别／来源前的统一准备：提交编辑器缓冲、记录撤销快照。
 *
 * 必须先 `onRowsUpdate`：它同时是"把未提交的输入并回条目"和"标记需要保存"的入口。
 * 直接改 `entries` 而不标脏会让这些改动**永远不会被自动保存**——
 * 界面上看起来改了，刷新后就不见了。
 */
function beginMetadataChange(label: string): void {
  onRowsUpdate(rows.value);
  recordBeforeChange(label);
}

/** 给当前范围内的所有条目设置默认类别（设计 115 行）。 */
function applyDefaultListTypesToScope(): void {
  beginMetadataChange('设置表类别');
  const rowIds = visibleEntries.value.map(entry => entry.rowId);
  if (!rowIds.length) {
    notice.value = '当前范围内没有条目。';
    return;
  }
  entries.value = setListTypesForRows(entries.value, rowIds, defaultListTypes.value);
  // 类别会改变筛选结果，编辑器要跟着刷新，否则会显示已被筛掉的旧行。
  syncRowsToSelection();
  notice.value = `已给当前范围 ${rowIds.length} 条设置类别。`;
}

/** 给当前范围内的所有条目设置默认来源与页码（设计 297 行）。 */
function applyDefaultSourceToScope(): void {
  beginMetadataChange('设置条目来源');
  const rowIds = visibleEntries.value.map(entry => entry.rowId);
  if (!rowIds.length) {
    notice.value = '当前范围内没有条目。';
    return;
  }
  entries.value = applySourceRefToRows(entries.value, rowIds, defaultSourceRef.value);
  notice.value = `已给当前范围 ${rowIds.length} 条设置来源：${defaultSourceRef.value.sourceId.trim() || '（仅页码／定位）'}。`;
}

/** 勾选／取消某一条的表类别。 */
function onEntryListTypes(rowId: string, value: Array<string | number>): void {
  beginMetadataChange('修改表类别');
  entries.value = setListTypesForRows(entries.value, [rowId], value.map(String));
  // 该行可能因为取消类别而不再命中筛选；同步一次避免显示"已被筛掉的行"。
  syncRowsToSelection();
}

/**
 * 修改某一条的来源。
 *
 * 直接改 `entries` 而不是 `rows`：类别与来源不属于双列编辑器的字段，
 * 而 `pairsToEntries` 会按 rowId 从既有条目里保留 `listTypes` 与 `preserved`，
 * 因此随后的任何输入都不会把它们抹掉。
 */
function onEntrySource(rowId: string, patch: Partial<EntrySourceRef>): void {
  const entry = entries.value.find(item => item.rowId === rowId);
  if (!entry) return;
  beginMetadataChange('修改条目来源');
  entries.value = applySourceRefToRows(entries.value, [rowId], { ...primarySourceRefOf(entry), ...patch });
}

/** 把页码输入解析成数字；空串表示未填（不写 0——0 是一个合法页码）。 */
function parsePageInput(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
}

/** 取条目的页码输入文本。 */
function pageInputOf(entry: DraftEntry): string {
  const page = primarySourceRefOf(entry).page;
  return page === null ? '' : String(page);
}

/** 载入已有草稿。 */
async function load(): Promise<void> {
  if (isNew.value || !props.editionId) return;
  busy.value = true;
  try {
    loadingDraft = true;
    const result = await fetchDraft(props.editionId);
    revision.value = result.revision;
    // 重新载入后本地与服务端一致：状态机跟着对齐，避免把上一次的脏标记带过来。
    resetSaveState(result.revision);
    applyDraft(result.draft as Record<string, unknown>);
    // 基线版本只能从读取草稿得到；发布请求体需要它（设计 362 行）。
    baseContentVersion.value = result.baseContentVersion ?? null;
  } catch (cause) {
    errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
  } finally {
    loadingDraft = false;
    busy.value = false;
  }
}

/**
 * 把服务端草稿写入编辑器状态。
 *
 * 抽出来是因为"读取最新"必须走**完全相同的**载入路径：
 * 若两处各写一份，冲突恢复后的状态就会与正常打开时不一致（例如漏掉地区或 idMapping），
 * 而那正是"保存一次就悄悄丢字段"的来源。
 *
 * 调用方负责 `loadingDraft` 与修订号。
 *
 * @param draft 服务端返回的草稿对象
 */
function applyDraft(draft: Record<string, unknown>): void {
  subject.value = (draft.subject as 'chinese' | 'english') ?? 'chinese';
  grade.value = (draft.grade as number) ?? null;
  semester.value = (draft.semester as 'first' | 'second') ?? 'first';
  publisher.value = (draft.publisher as string) ?? '';
  series.value = (draft.series as string) ?? '';
  editionLabel.value = (draft.editionLabel as string) ?? '';
  sourceNote.value = (draft.sourceNote as string) ?? '';
  coverage.value = (draft.coverageDeclaration as 'partial' | 'complete') ?? 'partial';
  // 读回这三个字段，否则"打开草稿再保存"同样会丢掉它们。
  const rawRegions = draft.regionCodes;
  regionCodes.value = Array.isArray(rawRegions) ? rawRegions.map(String) : [];
  curriculumYear.value = typeof draft.curriculumYear === 'number' ? draft.curriculumYear : null;
  isbn.value = (draft.isbn as string) ?? '';
  entries.value = (draft.entries as DraftEntry[]) ?? [];
  idMapping.value = (draft.idMapping as Record<string, string>) ?? {};
  // 换了一册教材，旧的撤销历史不再适用。
  history.clear();
  rows.value = [{}];
  outline.value = {
    units: (draft.units as Outline['units']) ?? [],
    lessons: ((draft.lessons as Array<Record<string, unknown>>) ?? []).map(lesson => ({
      lessonId: (lesson.lessonId as string) ?? null,
      clientLessonId: lesson.clientLessonId as string,
      clientUnitId: (lesson.clientUnitId as string) ?? '',
      title: lesson.title as string,
      order: lesson.order as number
    }))
  };
  syncRowsToSelection();
}

/** 清空冲突面板的全部状态（保存成功、读取最新、鉴权失效时都要清）。 */
function resetConflictState(): void {
  conflict.value = null;
  conflictDiff.value = null;
  conflictNotice.value = '';
  readLatestConfirming.value = false;
}

/**
 * 进入冲突态（设计 §3.7）。
 *
 * 顺序很关键：`SaveState.conflict()` 会**清空待重试操作**，所以
 * "刚才是否放弃了一份排队中的重试"必须在调用它**之前**取。
 *
 * @param cause     服务端 409 错误
 * @param operation 本次冻结的保存操作；没有（例如发布前的保存）时传 null
 */
function enterConflict(cause: AdminApiError, operation: FrozenSave<Record<string, unknown>> | null): void {
  const panel = resolveConflictPanel(cause.details, saveStateRef.value.peekPendingRetry() !== null);
  if (operation) {
    // 冲突**不自动重试**：用旧 payload 重发会覆盖服务器上的新内容。
    saveStateRef.value.conflict(operation);
  }
  conflict.value = panel;
  conflictDiff.value = null;
  readLatestConfirming.value = false;
  errorText.value = 'admin.conflict';
  conflictNotice.value = panel.expectedRevision === null
    ? '服务器的草稿修订已前进；本地改动仍保留在本页。'
    : `本地基于修订 ${panel.expectedRevision}，服务器上已有更新的修订。本地改动仍保留在本页。`;
}

/**
 * "查看差异"：读取服务器最新草稿，与本地条目逐行比对。
 *
 * 只读、不改本地缓冲；用户据此判断覆盖的后果，再决定是否读取最新。
 */
async function viewConflictDiff(): Promise<void> {
  if (!props.editionId) return;
  conflictNotice.value = '正在读取服务器版本…';
  try {
    const remote = await fetchDraft(props.editionId);
    const remoteEntries = (remote.draft.entries as DraftEntry[]) ?? [];
    conflictDiff.value = diffDraftEntries(entries.value, remoteEntries);
    conflictNotice.value = `差异基于服务器修订 ${remote.revision}。`;
  } catch (cause) {
    conflictNotice.value = `读取服务器版本失败：${cause instanceof AdminApiError ? adminErrorMessage(cause.messageKey) : '请重试。'}`;
  }
}

/** 展开／收起"读取最新"的二次确认（设计：读取最新前确认）。 */
function toggleReadLatestConfirm(): void {
  readLatestConfirming.value = !readLatestConfirming.value;
}

/**
 * "读取最新"：确认后用服务器草稿替换编辑器，并**更新本地修订号**。
 *
 * 这是此前真正的 bug：冲突后不回读修订号，再点保存仍然 409，用户只能刷新整页。
 * 本地改动不会被丢掉——先整份暂存到内存，用户可以再放回编辑器。
 */
async function readLatest(): Promise<void> {
  if (!props.editionId) return;
  readLatestConfirming.value = false;
  busy.value = true;
  conflictNotice.value = '正在读取服务器最新内容…';
  try {
    // 重新读取而不是复用差异时的那一份：用户确认之前服务器可能又变了。
    const remote = await fetchDraft(props.editionId);
    stashedLocal.value = { entries: entries.value.map(entry => ({ ...entry })), outline: outline.value };
    loadingDraft = true;
    applyDraft(remote.draft as Record<string, unknown>);
    revision.value = remote.revision;
    baseContentVersion.value = remote.baseContentVersion ?? baseContentVersion.value;
    // 状态机必须跟着服务端走，否则下一次保存仍会拿旧修订号发 If-Match。
    resetSaveState(remote.revision);
    selectedNode.value = { kind: 'all', id: '' };
    resetConflictState();
    errorText.value = '';
    notice.value = `已读取服务器最新内容（修订 ${remote.revision}）。本地改动已暂存在内存，可点"恢复本地改动"放回编辑器。`;
  } catch (cause) {
    conflictNotice.value = `读取最新失败：${cause instanceof AdminApiError ? adminErrorMessage(cause.messageKey) : '请重试。'}`;
  } finally {
    loadingDraft = false;
    busy.value = false;
  }
}

/** 把暂存的本地改动放回编辑器（基于最新修订，保存时会作为一次新的提交）。 */
function restoreStashedLocal(): void {
  if (!stashedLocal.value) {
    notice.value = '没有暂存的本地改动。';
    return;
  }
  recordBeforeChange('恢复本地改动');
  entries.value = stashedLocal.value.entries.map(entry => ({ ...entry }));
  outline.value = stashedLocal.value.outline;
  syncRowsToSelection();
  notice.value = '已把本地改动放回编辑器；下次保存会基于服务器最新修订提交。';
}

/** 保存草稿；成功记录新修订号与时间。 */
/**
 * F05：保存状态机。
 *
 * 用 ref 包住是为了换册／重新载入时**整体替换**——
 * 直接改内部状态会让"上一次教材的脏标记"带到新教材上。
 */
const saveStateRef = ref(new SaveState<Record<string, unknown>>(0, () => newSaveKey(Date.now())));

/** 重新加载草稿后，状态机要跟着对齐到服务端的修订号。 */
function resetSaveState(currentRevision: number): void {
  // 换册／重新载入意味着"本地与服务端一致"，历史脏标记不再适用。
  saveStateRef.value = new SaveState<Record<string, unknown>>(currentRevision, () => newSaveKey(Date.now()));
}

/** 自动保存的防抖定时器。 */
/**
 * 有未保存的改动时，浏览器关闭/刷新前提示（F05）。
 *
 * 编辑器此前**完全没有离开保护**：自动保存虽然 2 秒后会把改动落库，
 * 但"刚改完就关页面"仍会丢失最后那两秒的内容，而**用户不会得到任何提示**。
 *
 * 只在 `dirty` 为真时拦截；已经保存干净时不该打扰用户。
 */
function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!saveStateRef.value.dirty) return;
  // 现代浏览器忽略自定义文案，但仍要求设置 returnValue 才会弹出确认。
  event.preventDefault();
  event.returnValue = '';
}

let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * 是否正在从服务端加载草稿。
 *
 * **必须有它**：`load()` 结束时调 `syncRowsToSelection()`，那条路会走到
 * `onRowsUpdate` → `markDirtyAndSchedule()` —— 于是**刚打开一册草稿就被标脏**，
 * 2 秒后触发一次**无谓的自动保存**，而用户什么都没改。
 *
 * 我是靠"没改动时不该拦截离开"那条断言发现它的：
 * 它失败了，说明加载完就已经是 dirty。
 */
let loadingDraft = false;

/** 仍在途的补全请求；新的补全或卸载时会取消它（F06）。 */
let inFlightCompletion: AbortController | null = null;

/**
 * 补全请求的代次（F06）。
 *
 * `sourceText` 只能挡住"原文被改过"的情形 ✗。**两次补全交错**时它挡不住：
 * 第一次的原文没变、但它**回来得比第二次晚** ✗——那时它的结果会把
 * 第二次（更新）的结果**覆盖掉** ✗。所以每次发起都递增代次，
 * **只有"最新一次"的回包才允许写回** ✓。
 */
let completionGeneration = 0;

/**
 * `save()` 内部会先 `onRowsUpdate(rows.value)` 同步一次编辑器内容 ✓。
 * 但那次同步**不是用户在改** ✗ —— 若它也标脏，就会"每次保存都把脏标回来 → 再排下一次保存"，
 * 形成**每约 2 秒写库一次的无限循环**（实测间隔 `[2011,2008,2008,2004]` ms，修订号 2→7；
 * 见 `tests/e2e/remaining-b09b-autosave-loop.mjs`）。
 *
 * 这与既有的 `loadingDraft`（"加载期间不标脏"）是**同一个概念**：
 * **程序在写内容时不标脏，只有用户在改才标脏** ✓。
 */
let syncingForSave = false;

/** 标记内容有变化并安排一次自动保存（约 2 秒防抖）。 */
function markDirtyAndSchedule(): void {
  saveStateRef.value.markDirty();
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer);
  }
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null;
    void save();
  }, 2000);
}

async function save(): Promise<void> {
  if (busy.value) return;
  errorText.value = '';
  message.value = '';
  busy.value = true;
  // 本次冻结的操作；409 时要把它交给状态机的 `conflict()`（那会放弃任何排队重试）。
  let frozen: FrozenSave<Record<string, unknown>> | null = null;
  try {
    // 先同步一次编辑器内容，避免最后一次输入未进入草稿（只合并当前可见子集）。
    // 这次同步**必须不标脏**（`syncingForSave`），否则保存会给自己排下一次保存。
    syncingForSave = true;
    try {
      onRowsUpdate(rows.value);
    } finally {
      syncingForSave = false;
    }
    if (isNew.value) {
      const created = await createTextbook(buildDraft());
      revision.value = created.revision;
      message.value = '草稿已创建。';
      await router.replace(`/admin/textbooks/${created.editionId}/edit`);
    } else {
      // F05：发起时冻结 payload 与幂等键。
      // 等待期间用户继续输入，用的仍是**冻结的这一份**；回包再判断是否已经过期。
      frozen = saveStateRef.value.begin(buildDraft());
      const draftNow = frozen?.payload ?? buildDraft();
      const key = frozen?.idempotencyKey;
      const result = await saveDraft(props.editionId as string, revision.value, draftNow, key);
      revision.value = result.revision;
      const outcome = frozen ? saveStateRef.value.succeed(frozen, result.revision) : 'saved';
      // 保存期间又改了内容时**不能**说"已保存"——那会让人以为改动已经落库。
      message.value = outcome === 'saved' ? '草稿已保存。' : '有新改动待保存。';
      // 保存成功即离开冲突态：面板必须随一次成功保存消失，否则用户会以为还冲突着。
      if (conflict.value) resetConflictState();
    }
    savedAt.value = new Date().toLocaleTimeString();
  } catch (cause) {
    if (cause instanceof AdminApiError && cause.status === 409) {
      // 冲突不覆盖他人改动：进入冲突面板（含差异与"读取最新"），保留当前编辑缓冲。
      enterConflict(cause, frozen);
    } else if (cause instanceof AdminApiError && cause.status === 422) {
      // 保存也会做草稿级校验（服务端 `Level.DRAFT`），因此这里同样可能 422。
      // 只给一句通用错误等于"提示了但没告诉用户改哪里"——设计第 118 行要求**就地提示**。
      // 与发布路径保持一致：拉一次校验结果，把问题清单显示出来；**本地缓冲一律保留**。
      errorText.value = 'admin.publish.invalid';
      validation.value = await validateDraft(props.editionId as string).catch(() => null);
    } else {
      // **把这次失败交回状态机**：`SaveState.fail()` 会把冻住的操作（含**原幂等键**与原 payload）
      // 放进 `pendingRetry`，于是下一次 `begin()` 会**原样重发**而不是生成新键。
      // 此前这里只写 `errorText`，`fail()` 从未被调用 → `pendingRetry` 永远是 null →
      // 重试每次都换新键，"同键重试"的设计只存在于单测里（实测 reused=false）。
      // 注意：409 已在上面交给 `conflict()`（那会主动放弃重试，正确）；
      // 422 是校验失败、重发同样会失败，因此**不**排重试（保持原行为）。
      if (frozen) {
        saveStateRef.value.fail(frozen);
      }
      errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
    }
  } finally {
    busy.value = false;
  }
}

/** 校验并展示问题清单。 */
async function validate(): Promise<void> {
  busy.value = true;
  errorText.value = '';
  try {
    onRowsUpdate(rows.value);
    await saveDraft(props.editionId as string, revision.value, buildDraft()).then(result => {
      revision.value = result.revision;
    });
    validation.value = await validateDraft(props.editionId as string);
  } catch (cause) {
    errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
  } finally {
    busy.value = false;
  }
}

/** 发布；服务端会重新校验，不信任前端结果。 */
async function publish(): Promise<void> {
  busy.value = true;
  errorText.value = '';
  message.value = '';
  try {
    onRowsUpdate(rows.value);
    const saved = await saveDraft(props.editionId as string, revision.value, buildDraft());
    revision.value = saved.revision;
    // 设计 362 行：请求体为 `{draftRevision,baseContentVersion,changeNote}`。
    // 修订号取自**这次保存之后**的值，因此"发布的是我确认过的那一版"成立。
    const intent = buildPublishIntent({
      revision: revision.value,
      baseContentVersion: baseContentVersion.value,
      changeNote: changeNote.value
    });
    const result = await publishDraft(props.editionId as string, intent);
    // **防御性分支**：当前服务端的 `reloadSnapshot` 失败时**抛异常**（→ 503），
    // 因此 `snapshotReloaded === false` 在实际部署中**不可达**。
    // 保留它是为了在服务端将来改为"返回 false 而不抛"时不会误报成功；
    // 但也必须承认：这条分支没有测试覆盖，因为无法在当前服务端上触发。
    if (result.snapshotReloaded === false) {
      errorText.value = 'admin.snapshotNotReloaded';
      message.value = `已提交 ${result.contentVersion}，但目录快照未能重建：公开内容可能仍是旧版本。`;
    } else {
      message.value = `已发布 ${result.contentVersion}。`;
    }
  } catch (cause) {
    if (cause instanceof AdminApiError && cause.status === 409) {
      // 发布前的保存或发布本身撞上别人的更新：同样进入冲突面板，而不是只留一句话。
      enterConflict(cause, null);
    } else if (cause instanceof AdminApiError && cause.status === 422) {
      errorText.value = 'admin.publish.invalid';
      validation.value = await validateDraft(props.editionId as string).catch(() => null);
    } else {
      errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
    }
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void load();
  // F05：注册离开前的未保存提示。用 `window` 而非组件根元素——
  // 关标签/刷新时组件根元素上收不到这个事件。
  window.addEventListener('beforeunload', onBeforeUnload);
});

onUnmounted(() => {
  window.removeEventListener('beforeunload', onBeforeUnload);
  // F06：离开页面时取消仍在途的补全请求。
  inFlightCompletion?.abort();
  inFlightCompletion = null;
});
</script>

<template>
  <section class="admin-editor" aria-label="编辑教材">
    <header class="editor-header">
      <div>
        <h2>{{ isNew ? '新增教材' : '修改教材' }}</h2>
        <p class="admin-note">填写教材信息，然后录入对应内容。</p>
      </div>
      <NSpace>
        <NButton :loading="busy" @click="save">保存草稿</NButton>
        <NButton :loading="busy" :disabled="isNew" @click="validate">校验</NButton>
        <NButton :loading="busy" @click="runAutomaticCompletion(false)">自动补全</NButton>
        <NButton :loading="busy" quaternary @click="runAutomaticCompletion(true)">重新补全空白</NButton>
        <NButton :loading="busy" @click="confirmVisible">确认内容</NButton>
        <NButton :disabled="isNew" @click="openPreview">预览草稿</NButton>
        <!-- 版本记录（设计 3.6 第 166 行）：只读历史，放右侧抽屉。
             新增教材还没有版本，因此新增态禁用；**不是隐藏**——隐藏会让键盘用户
             在 Tab 顺序里找不到它，也就无法知道这个功能存在。 -->
        <NButton
          :disabled="isNew"
          aria-haspopup="dialog"
          :aria-expanded="versionDrawerOpen"
          @click="versionDrawerOpen = true"
        >
          版本记录
        </NButton>
        <NButton type="primary" :loading="busy" :disabled="isNew" @click="publish">保存并发布</NButton>
      </NSpace>
    </header>

    <!-- 预览设置（设计 164 行）：题型按学科给两种，范围始终可见 -->
    <div class="preview-settings">
      <span class="admin-note">预览题型</span>
      <NSelect
        :value="previewMode"
        :options="previewModeChoices"
        size="small"
        style="width: 150px"
        aria-label="预览题型"
        @update:value="value => (previewMode = coercePreviewMode(subject, value))"
      />
      <span class="admin-note">题型：{{ previewModeLabel(previewMode) }} · 当前范围：{{ previewScope.label }}</span>
      <span v-if="!previewScope.wholeBook" class="admin-note scope-limit">{{ previewScope.notice }}</span>
      <span v-else class="admin-note">预览整册内容。在左侧目录中选择单元或课文，即可只预览该范围。</span>
      <NInput
        v-model:value="changeNote"
        size="small"
        style="width: 260px"
        placeholder="修改摘要（可选，写入版本记录）"
        :input-props="{ 'aria-label': '修改摘要' }"
      />
    </div>

    <!-- 保存冲突面板（设计 §3.7）：显示"服务器已有更新"，提供查看差异／读取最新 -->
    <section v-if="conflict" class="conflict-block" aria-label="保存冲突">
      <div class="conflict-head">
        <NTag size="small" type="error" :bordered="false">服务器已有更新</NTag>
        <span class="admin-note">{{ conflictNotice }}</span>
        <NButton size="small" @click="viewConflictDiff">查看差异</NButton>
        <NButton size="small" type="warning" @click="toggleReadLatestConfirm">读取最新</NButton>
        <NButton v-if="!readLatestConfirming" size="small" quaternary @click="resetConflictState">稍后处理</NButton>
      </div>
      <div v-if="readLatestConfirming" class="conflict-confirm">
        <span>读取最新会用服务器上的内容替换当前编辑器中的改动（本地改动会暂存在内存中，可再恢复）。确定吗？</span>
        <NButton size="small" type="warning" :loading="busy" @click="readLatest">确认读取最新</NButton>
        <NButton size="small" quaternary @click="toggleReadLatestConfirm">取消</NButton>
      </div>
      <div v-if="conflictDiff" class="conflict-diff">
        <p class="admin-note">{{ summarizeConflictDiff(conflictDiff) }}</p>
        <ul class="diff-list">
          <li v-for="row in conflictDiff.rows.filter(item => item.kind !== 'identical').slice(0, 12)" :key="row.rowId">
            <NTag size="tiny" :bordered="false">{{ CONFLICT_DIFF_LABELS[row.kind] }}</NTag>
            <span class="diff-row-id">{{ row.rowId }}</span>
            <span class="diff-local">本地：{{ row.local ? `${row.local.text} / ${row.local.pinyin} / ${row.local.meaning}` : '（无）' }}</span>
            <span class="diff-remote">服务器：{{ row.remote ? `${row.remote.text} / ${row.remote.pinyin} / ${row.remote.meaning}` : '（无）' }}</span>
          </li>
        </ul>
      </div>
    </section>

    <NAlert v-if="errorText" type="error" :show-icon="false" class="editor-alert">
      操作失败：{{ adminErrorMessage(errorText) }}　当前编辑内容仍保留在本页。
    </NAlert>
    <!-- 读取最新后本地改动仍在内存里：恢复入口**不能**只放在冲突面板内，
         否则"读取最新"成功后面板关闭，暂存的内容就再也没有入口了。 -->
    <NAlert v-if="stashedLocal" type="info" :show-icon="false" class="editor-alert">
      读取最新前的本地改动已暂存在内存中。
      <NButton text type="primary" @click="restoreStashedLocal">恢复本地改动</NButton>
    </NAlert>
    <NAlert v-if="message" type="success" :show-icon="false" class="editor-alert">{{ message }}</NAlert>
    <NAlert v-if="notice" type="info" :show-icon="false" class="editor-alert">{{ notice }}</NAlert>
    <NAlert v-if="validation" :type="validation.publishable ? 'info' : 'warning'" :show-icon="false" class="editor-alert">
      {{ validation.publishable ? '校验通过，可以发布。' : `校验未通过，共 ${validation.issues.length} 项问题。` }}
      <ul class="issue-list">
        <li v-for="issue in validation.issues.slice(0, 8)" :key="`${issue.code}-${issue.rowId ?? ''}-${issue.fieldPath}`">
          {{ formatIssue(issue) }}
        </li>
      </ul>
    </NAlert>

    <!-- 草稿预览：复用已发布渲染管线，只读；明确标注避免被误认为已发布内容 -->
    <section v-if="previewOpen" class="preview-block" aria-label="草稿预览">
      <div class="preview-head">
        <h3>草稿预览</h3>
        <NTag size="small" type="warning" :bordered="false">草稿预览，尚未发布</NTag>
        <span class="admin-note">本预览与发布使用同一渲染管线，但不会写入或发布任何内容。</span>
        <!-- 本次预览基于的修订号、范围与**真实页数**：先保存再预览，因此三者对得上。
             页数直接来自产物，范围过滤有没有生效一眼可见（不再只靠文字声明）。 -->
        <span class="admin-note preview-snapshot">
          本次预览基于修订 {{ draftPreview.revision.value ?? '—' }} · 范围 {{ draftPreview.scope.value || previewScope.label }}{{ draftPreview.pageCount.value ? ` · 共 ${draftPreview.pageCount.value} 页` : '' }}
        </span>
        <NButton size="small" :disabled="!draftPreview.artifact.value" @click="draftPreview.print()">打印预览</NButton>
        <NButton size="small" quaternary @click="closePreview">关闭预览</NButton>
      </div>
      <WorksheetPreview
        :artifact="draftPreview.artifact.value"
        :page="draftPreview.page.value"
        :zoom="draftPreview.zoom.value"
        :busy="draftPreview.busy.value"
        :error="draftPreview.error.value"
        @retry="openPreview"
        @select="selectPreviewPage"
      />
    </section>

    <!-- 新增时展开表单；已有教材折叠为摘要，避免每次滚动过长 -->
    <!-- `display-directive="show"`：折叠未展开时也**渲染**内容（只是隐藏）。
           默认的 `if` 会把它从 DOM 里摘掉，于是更多信息里的控件
           在展开之前根本不存在——测试找不到，读屏也读不到。 -->
    <NCollapse :default-expanded-names="isNew ? ['info'] : []" display-directive="show">
      <NCollapseItem title="教材信息" name="info">
        <div class="info-grid">
          <NFormItem label="学科" :show-feedback="false">
            <NSelect :value="subject" :options="[{ label: '语文', value: 'chinese' }, { label: '英语', value: 'english' }]" aria-label="学科" @update:value="onSubjectChange" />
          </NFormItem>
          <NFormItem label="年级" :show-feedback="false">
            <NSelect v-model:value="grade" :options="gradeOptions" aria-label="年级" />
          </NFormItem>
          <NFormItem label="册次" :show-feedback="false">
            <NSelect v-model:value="semester" :options="[{ label: '上册', value: 'first' }, { label: '下册', value: 'second' }]" aria-label="册次" />
          </NFormItem>
          <NFormItem label="出版社" :show-feedback="false">
            <NInput v-model:value="publisher" placeholder="如 人民教育出版社" :input-props="{ 'aria-label': '出版社' }" />
          </NFormItem>
          <NFormItem label="教材名称" :show-feedback="false">
            <NInput v-model:value="series" placeholder="如 统编语文" :input-props="{ 'aria-label': '教材名称' }" />
          </NFormItem>
          <NFormItem label="教材版本" :show-feedback="false">
            <NInput v-model:value="editionLabel" placeholder="如 2024 年修订版" :input-props="{ 'aria-label': '教材版本' }" />
          </NFormItem>
          <NFormItem v-if="isNew" label="教材标识" :show-feedback="false">
            <NInput v-model:value="title" placeholder="用于生成稳定 ID，如 cn-ty-5-1-pep-2024" :input-props="{ 'aria-label': '教材标识' }" />
          </NFormItem>
          <NFormItem label="收录范围" :show-feedback="false">
            <NSelect v-model:value="coverage" :options="[{ label: '部分收录', value: 'partial' }, { label: '完整收录', value: 'complete' }]" aria-label="收录范围" />
          </NFormItem>
        </div>
        <NFormItem label="来源说明" :show-feedback="false">
          <NInput v-model:value="sourceNote" placeholder="发布时必填，例如：纸质教材附录词语表，第 78—82 页" :input-props="{ 'aria-label': '来源说明' }" />
        </NFormItem>
      </NCollapseItem>

      <!-- F03：设计 104/105 行把它放在"更多信息"里——
           地区**存代码、显示名称**，默认江苏；ISBN 与课标年份**可选**。
           这三个字段过去在 `toEdition` 里被硬编码丢掉，所以界面上一直没有它们。 -->
      <NCollapseItem title="更多信息" name="more" display-directive="show">
        <div class="info-grid">
          <NFormItem label="适用地区" :show-feedback="false">
            <NSelect
              v-model:value="regionCodes"
              multiple
              filterable
              :options="regionOptions"
              placeholder="默认江苏；可多选或选全国"
              aria-label="适用地区"
            />
          </NFormItem>
          <NFormItem label="课标／修订年份" :show-feedback="false">
            <NInput
              :value="curriculumYear === null ? '' : String(curriculumYear)"
              placeholder="如 2024（可选）"
              :input-props="{ 'aria-label': '课标年份' }"
              @update:value="onCurriculumYearInput"
            />
          </NFormItem>
          <NFormItem label="ISBN" :show-feedback="false">
            <NInput
              v-model:value="isbn"
              placeholder="按真实封面／版权页填写，不推断（可选）"
              :input-props="{ 'aria-label': 'ISBN' }"
            />
          </NFormItem>
        </div>
      </NCollapseItem>
    </NCollapse>

    <section class="content-workspace" aria-label="内容录入">
      <!-- 左：目录树 -->
      <aside class="outline-pane" aria-label="教材目录">
        <div class="outline-head">
          <h3>目录</h3>
          <NButton text :type="selectedNode.kind === 'all' ? 'primary' : 'default'" @click="selectedNode = { kind: 'all', id: '' }">
            全部内容（{{ entries.length }}）
          </NButton>
        </div>

        <ul class="outline-tree">
          <li v-for="group in tree" :key="group.unit?.clientUnitId ?? '__none__'">
            <div v-if="group.unit" class="outline-unit">
              <NButton
                text
                :type="selectedNode.kind === 'unit' && selectedNode.id === group.unit.clientUnitId ? 'primary' : 'default'"
                @click="selectedNode = { kind: 'unit', id: group.unit.clientUnitId }"
              >
                {{ group.unit.title || '（未命名单元）' }}
              </NButton>
              <NSpace :size="2">
                <NButton text size="tiny" aria-label="上移单元" @click="moveOutline('unit', group.unit.clientUnitId, -1)">↑</NButton>
                <NButton text size="tiny" aria-label="下移单元" @click="moveOutline('unit', group.unit.clientUnitId, 1)">↓</NButton>
                <NButton text size="tiny" aria-label="重命名单元" @click="renamePrompt('unit', group.unit.clientUnitId, group.unit.title)">改名</NButton>
                <NButton text size="tiny" aria-label="删除单元" @click="onRemoveUnit(group.unit.clientUnitId)">删除</NButton>
              </NSpace>
            </div>
            <div v-else class="outline-unit outline-none">未分单元</div>
            <ul class="outline-lessons">
              <li v-for="lesson in group.lessons" :key="lesson.clientLessonId">
                <NButton
                  text
                  :type="selectedNode.kind === 'lesson' && selectedNode.id === lesson.clientLessonId ? 'primary' : 'default'"
                  @click="selectedNode = { kind: 'lesson', id: lesson.clientLessonId }"
                >
                  {{ lesson.title || '（未命名课文）' }}
                </NButton>
                <NSpace :size="2">
                  <NButton text size="tiny" aria-label="上移课文" @click="moveOutline('lesson', lesson.clientLessonId, -1)">↑</NButton>
                  <NButton text size="tiny" aria-label="下移课文" @click="moveOutline('lesson', lesson.clientLessonId, 1)">↓</NButton>
                  <NButton text size="tiny" aria-label="重命名课文" @click="renamePrompt('lesson', lesson.clientLessonId, lesson.title)">改名</NButton>
                  <NButton text size="tiny" aria-label="删除课文" @click="onRemoveLesson(lesson.clientLessonId)">删除</NButton>
                </NSpace>
              </li>
            </ul>
          </li>
        </ul>

        <div class="outline-add">
          <NInput
            v-model:value="pasteText"
            type="textarea"
            size="small"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder="批量粘贴：每行一条"
            aria-label="批量粘贴"
          />
          <NButton size="small" :disabled="!pasteText.trim()" @click="onBulkPaste">解析并追加</NButton>
          <span v-if="pasteNotice" class="admin-note paste-notice">{{ pasteNotice }}</span>
        </div>
        <div class="outline-add">
          <NInput v-model:value="newUnitTitle" size="small" placeholder="新单元名称" :input-props="{ 'aria-label': '新单元名称' }" @keyup.enter="onAddUnit" />
          <NButton size="small" @click="onAddUnit">新增单元</NButton>
          <NInput v-model:value="newAppendixTitle" size="small" placeholder="新附录名称（如：专有名词附录）" :input-props="{ 'aria-label': '新附录名称' }" @keyup.enter="onAddAppendix" />
          <NButton size="small" @click="onAddAppendix">新增附录</NButton>
          <NButton size="small" :disabled="history.depth === 0" @click="undo">撤销</NButton>
          <NInput v-model:value="newLessonTitle" size="small" placeholder="新课文标题" :input-props="{ 'aria-label': '新课文标题' }" @keyup.enter="onAddLesson" />
          <NButton size="small" @click="onAddLesson">
            新增课文{{ selectedNode.kind === 'unit' ? '（到选中单元）' : '（到未分单元）' }}
          </NButton>
        </div>
      </aside>

      <!-- 右：内容编辑 -->
      <div class="editor-pane">
        <div class="workspace-head">
          <h3>内容</h3>
          <NTag size="small" :bordered="false">{{ visibleEntries.length }} 条</NTag>
          <span v-if="revision" class="admin-note">修订 {{ revision }}{{ savedAt ? ` · 保存于 ${savedAt}` : '' }}</span>
        </div>
        <!-- 复用现有双列配对编辑器：语文左汉字右拼音，英语左英文右释义 -->
        <WorksheetPairedEditor
          :subject="subject"
          :rows="rows"
          label="录入内容"
          :busy="busy"
          @update:rows="onRowsUpdate"
        />

        <!-- 设计 115 / 297 行：表类别（可多选、可筛选）与条目级来源。
             与双列编辑器分开，避免改动公开端的字帖组件。 -->
        <section class="entry-metadata" aria-label="表类别与来源">
          <div class="metadata-head">
            <h3>表类别与来源</h3>
            <NSelect
              v-model:value="listTypeFilter"
              multiple
              clearable
              size="small"
              style="min-width: 200px"
              :options="listTypeFilterOptions"
              placeholder="按表类别筛选"
              aria-label="按表类别筛选"
            />
            <span class="admin-note">筛选只改变视图，不更换条目的稳定身份。</span>
          </div>

          <div class="metadata-defaults">
            <span class="admin-note">当前范围的默认类别</span>
            <NCheckboxGroup v-model:value="defaultListTypes" aria-label="默认类别">
              <NCheckbox v-for="type in ADMIN_LIST_TYPE_ORDER" :key="type" :value="type" :label="listTypeLabel(type, subject)" />
            </NCheckboxGroup>
            <NButton size="small" @click="applyDefaultListTypesToScope">应用到当前范围</NButton>
          </div>

          <div class="metadata-defaults">
            <span class="admin-note">当前范围的默认来源</span>
            <NInput v-model:value="defaultSourceRef.sourceId" size="small" style="width: 160px" placeholder="来源，如 附录词语表" :input-props="{ 'aria-label': '默认来源' }" />
            <NInput v-model:value="defaultSourceRef.locator" size="small" style="width: 140px" placeholder="定位，如 第二列" :input-props="{ 'aria-label': '默认来源定位' }" />
            <NInput
              :value="defaultSourceRef.page === null ? '' : String(defaultSourceRef.page)"
              size="small"
              style="width: 90px"
              placeholder="页码"
              :input-props="{ 'aria-label': '默认来源页码' }"
              @update:value="value => (defaultSourceRef.page = parsePageInput(value))"
            />
            <NButton size="small" @click="applyDefaultSourceToScope">应用到当前范围</NButton>
          </div>

          <ul class="metadata-list">
            <li v-for="entry in visibleEntries" :key="entry.rowId">
              <span class="metadata-row-id">{{ entry.rowId }}</span>
              <span class="metadata-text">{{ entry.text }}</span>
              <NCheckboxGroup
                :value="entry.listTypes"
                :aria-label="`${entry.rowId} 表类别`"
                @update:value="value => onEntryListTypes(entry.rowId, value)"
              >
                <NCheckbox v-for="type in ADMIN_LIST_TYPE_ORDER" :key="type" :value="type" :label="listTypeLabel(type, subject)" />
              </NCheckboxGroup>
              <NInput
                :value="primarySourceRefOf(entry).sourceId"
                size="tiny"
                style="width: 130px"
                placeholder="来源"
                :input-props="{ 'aria-label': `${entry.rowId} 来源` }"
                @update:value="value => onEntrySource(entry.rowId, { sourceId: value })"
              />
              <NInput
                :value="primarySourceRefOf(entry).locator"
                size="tiny"
                style="width: 120px"
                placeholder="定位"
                :input-props="{ 'aria-label': `${entry.rowId} 来源定位` }"
                @update:value="value => onEntrySource(entry.rowId, { locator: value })"
              />
              <NInput
                :value="pageInputOf(entry)"
                size="tiny"
                style="width: 80px"
                placeholder="页码"
                :input-props="{ 'aria-label': `${entry.rowId} 来源页码` }"
                @update:value="value => onEntrySource(entry.rowId, { page: parsePageInput(value) })"
              />
            </li>
          </ul>
          <p v-if="!visibleEntries.length" class="admin-note">当前范围内没有条目。</p>
        </section>
      </div>
    </section>

    <!-- 版本记录抽屉：只读（设计 3.6 第 166 行）。
         关闭后焦点归还由 NDrawer 的焦点陷阱负责（实测回落到「版本记录」按钮）。 -->
    <VersionHistoryDrawer
      :edition-id="props.editionId ?? ''"
      v-model:show="versionDrawerOpen"
    />  </section>
</template>

<style scoped>
.admin-editor {
  padding: 20px;
  color: var(--ink);
}
.editor-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.admin-note {
  color: var(--ink-muted, #666);
  margin: 4px 0 0;
}
.editor-alert {
  margin: 12px 0;
}
.issue-list {
  margin: 6px 0 0;
  padding-left: 18px;
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 4px 12px;
}
.preview-block {
  margin: 16px 0;
  border: 1px solid var(--border, #e5e5e5);
  border-radius: 8px;
  padding: 12px;
}
.preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.content-workspace {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  align-items: start;
}
.outline-pane {
  border: 1px solid var(--border, #e5e5e5);
  border-radius: 8px;
  padding: 10px;
}
.outline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.outline-tree {
  list-style: none;
  margin: 0;
  padding: 0;
}
.outline-unit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 4px 0;
}
.outline-none {
  color: var(--ink-muted, #666);
  font-size: 13px;
}
.outline-lessons {
  list-style: none;
  margin: 0 0 8px;
  padding-left: 14px;
}
.outline-lessons li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}
.paste-notice {
  font-size: 12px;
}
.outline-add {
  display: grid;
  gap: 6px;
  margin-top: 10px;
  border-top: 1px solid var(--border, #e5e5e5);
  padding-top: 10px;
}
@media (max-width: 900px) {
  .preview-block {
  margin: 16px 0;
  border: 1px solid var(--border, #e5e5e5);
  border-radius: 8px;
  padding: 12px;
}
.preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.content-workspace {
    grid-template-columns: 1fr;
  }
}
.workspace-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
/* 预览设置与冲突面板、条目元数据面板（本轮新增的可见状态） */
.preview-settings {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.scope-limit {
  color: #a56a00;
}
.conflict-block {
  margin: 12px 0;
  border: 1px solid #e0a0a0;
  border-radius: 8px;
  padding: 10px;
  background: #fff7f7;
}
.conflict-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.conflict-confirm {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.conflict-diff {
  margin-top: 8px;
}
.diff-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
}
.diff-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
.diff-row-id {
  font-family: monospace;
  color: var(--ink-muted, #666);
}
.entry-metadata {
  margin-top: 16px;
  border-top: 1px solid var(--border, #e5e5e5);
  padding-top: 10px;
}
.metadata-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.metadata-defaults {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.metadata-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: grid;
  gap: 6px;
}
.metadata-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  border-bottom: 1px dashed var(--border, #eee);
  padding-bottom: 4px;
}
.metadata-row-id {
  font-family: monospace;
  color: var(--ink-muted, #666);
  min-width: 44px;
}
.metadata-text {
  min-width: 72px;
  font-weight: 600;
}
@media (max-width: 720px) {

  /* 移动端命中区：设计第 176 行要求 44px。
     实测（2026-10-07，375×812）：按钮普遍高 40px —— 209 个可点击元素中 174 个 < 44px。
     这里在窄屏下把可点击控件的最小高度抬到 44px；**桌面尺寸不受影响**。 */
  /* 注意必须用 :deep()：这些是 Naive UI 的子组件，作用域样式默认选不中。
     另外图标类按钮（如「编辑」「打开目录」）高度够了但**宽度不足**，所以同时给 min-width。 */
  :deep(.n-button),
  button,
  :deep(.n-base-selection),
  :deep(.n-input),
  input,
  select,
  a[href] {
    min-height: 44px;
  }
  :deep(.n-button),
  button,
  [class*='drawer'],
  [aria-label*='目录'],
  [aria-label*='菜单'] {
    min-width: 44px;
  }
  /* Naive UI 的输入类组件**内部**才是真正的尺寸元素：
     外层 `.n-input` 只是容器，高度由内部 input/选择器标签决定。
     所以这里要选到内部元素，只给外层加 min-height 是选不中的（第 128 轮实测）。 */
  :deep(.n-input__input-el),
  :deep(.n-input__textarea-el),
  :deep(.n-base-selection-label),
  :deep(.n-base-selection-input) {
    min-height: 44px;
  }
  :deep(.n-input),
  :deep(.n-base-selection) {
    --n-height: 44px;
  }
  .editor-header {
    flex-direction: column;
  }
}
</style>
