<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { NAlert, NButton, NFormItem, NInput, NSelect, NSpin } from 'naive-ui';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
import WorksheetPairedEditor from '../../components/worksheet/WorksheetPairedEditor.vue';
import { useWorksheetPairCompletion } from '../../composables/useWorksheetPairCompletion';
import type { EditableWorksheetPair } from '../../domain/worksheet-editable-pairs';
import type { ListType } from '../../domain/curriculum';
import { computeContentDigest, entriesToPairs, mergeSubset, pairHasAnyContent, pairsToEntries, type DraftEntry } from '../../lib/admin-textbook-draft';
import { listTypeLabel } from '../../lib/admin-entry-metadata';
import { gradesForSubject } from '../../lib/admin-textbook-view';
import { adminErrorMessage } from '../../lib/admin-error-messages';
import { entryConflictRecovery } from '../../lib/admin-entry-conflict';
import { validationMessage } from '../../lib/admin-validation-messages';
import { useAdminAuthStore } from '../../stores/admin-auth';
import { AdminApiError } from '../../services/admin-api';
import { createTextbook, fetchDraft, publishDraft, rebaseDraft, saveDraft, translateEntriesForAdmin, validateDraft, type AdminDraftResult, type AdminPublishResult } from '../../services/admin-textbooks-api';

/** 教材录入只展示教材、单元、类型和正文，保存及发布串为一次操作。 */
const props = defineProps<{ editionId?: string }>();
const router = useRouter();
const auth = useAdminAuthStore();
interface EntryUnit { unitId: string | null; clientUnitId: string; title: string; order: number; [key: string]: unknown }
const draft = ref<Record<string, unknown>>({ subject: 'chinese', grade: null, semester: 'first', series: '', coverageDeclaration: 'partial', lessons: [], idMapping: {} });
const entries = ref<DraftEntry[]>([]);
const units = ref<EntryUnit[]>([]);
const rows = ref<EditableWorksheetPair[]>([{}]);
const currentId = ref(props.editionId ?? '');
const newId = `textbook-${crypto.randomUUID()}`;
const revision = ref(0);
const baseVersion = ref<string | null>(null);
const unitKey = ref<string | null>(null);
const listType = ref<ListType | null>(null);
const loading = ref(false);
const submitting = ref(false);
const dirty = ref(false);
const errorText = ref('');
const successText = ref('');
const needsReload = ref(false);
const loadFailed = ref(false);
const publicationPending = ref(false);
const editorLocked = computed(() => submitting.value || loading.value || !auth.canWrite || needsReload.value || loadFailed.value || publicationPending.value);
let generation = 0;
let disposed = false;
const touched = new Set<string>();
let saveRetry: { signature: string; key: string; revision: number } | null = null;
let publishRetry: { intent: { draftRevision: number; baseContentVersion: string | null; changeNote: string }; key: string } | null = null;
let rebaseRetry: { revision: number; key: string } | null = null;

const subject = computed(() => draft.value.subject === 'english' ? 'english' : 'chinese');
const grade = computed(() => typeof draft.value.grade === 'number' ? draft.value.grade : null);
const semester = computed(() => draft.value.semester === 'second' ? 'second' : 'first');
const bookName = computed(() => String(draft.value.series ?? ''));
const isNew = computed(() => !currentId.value);
const gradeOptions = computed(() => gradesForSubject(subject.value).map(value => ({ value, label: `${value} 年级` })));
const unitOptions = computed(() => units.value.map(unit => ({ value: unit.clientUnitId || unit.unitId!, label: unit.title })));
const selectedUnit = computed(() => units.value.find(unit => (unit.clientUnitId || unit.unitId) === unitKey.value));
const types = computed<ListType[]>(() => subject.value === 'english' ? ['vocabulary', 'expression'] : ['recognition', 'writing', 'vocabulary']);
const ready = computed(() => !!selectedUnit.value && !!listType.value && !loading.value && !loadFailed.value);
const visibleEntries = computed(() => entries.value.filter(entry => matchesSelection(entry)));
const editableRows = computed({ get: () => rows.value, set: value => updateRows(value) });
const completion = useWorksheetPairCompletion(() => subject.value, editableRows, {
  enabled: () => ready.value && auth.canWrite && !needsReload.value && !publicationPending.value,
  translate: translateEntriesForAdmin
});

/** 同一单元和表类型才属于当前编辑范围，其他范围原样保留。 */
function matchesSelection(entry: DraftEntry): boolean {
  const unit = selectedUnit.value;
  if (!unit || !listType.value) return false;
  const owned = !!(unit.clientUnitId && entry.clientUnitId === unit.clientUnitId) || !!(unit.unitId && entry.unitId === unit.unitId);
  return owned && (entry.listTypes.includes(listType.value) || !entry.listTypes.length);
}

/** 修改基础信息不要求技术标识，学科切换时只清空尚未录入的选择。 */
function updateInfo(field: string, value: unknown): void {
  if (editorLocked.value || (field === 'subject' && entries.value.length > 0)) return;
  draft.value = { ...draft.value, [field]: value };
  if (field === 'subject') {
    if (subject.value === 'english' && (grade.value ?? 0) < 3) draft.value.grade = null;
    listType.value = null;
    rows.value = [{}];
  }
  dirty.value = true;
  successText.value = '';
}

/** 可选已有单元，也可输入新的单元名称，无需维护目录树。 */
function chooseUnit(value: string | null): void {
  if (editorLocked.value) return;
  completion.cancel();
  if (value && !unitOptions.value.some(option => option.value === value)) {
    const title = value.trim();
    if (!title) return;
    const unit = { unitId: null, clientUnitId: `unit-${crypto.randomUUID()}`, title, order: Math.max(0, ...units.value.map(item => item.order)) + 1 };
    units.value = [...units.value, unit];
    value = unit.clientUnitId;
    dirty.value = true;
  }
  unitKey.value = value;
  syncRows();
}

/** 类型只决定新增内容存入哪个表，不改写其他表的内容。 */
function chooseType(value: ListType): void {
  if (editorLocked.value) return;
  completion.cancel();
  listType.value = value;
  syncRows();
}

/** 切换范围仅替换展示缓冲，所有已输入内容仍在整册草稿中。 */
function syncRows(): void {
  const visible = visibleEntries.value;
  rows.value = visible.length ? entriesToPairs(visible, subject.value) : [{}];
}

/** 编辑合并完整原对象，保留未在简洁表单中展示的来源、义项与课文归属。 */
function updateRows(next: EditableWorksheetPair[]): void {
  if (!ready.value || !auth.canWrite || publicationPending.value || needsReload.value) return;
  const unit = selectedUnit.value!;
  const previous = new Map(entries.value.map(entry => [entry.rowId, entry]));
  const visibleIds = visibleEntries.value.map(entry => entry.rowId);
  const updated = pairsToEntries(next, entries.value, subject.value, { clientUnitId: unit.clientUnitId || null }).map(entry => {
    const old = previous.get(entry.rowId);
    const result = { ...old, ...entry, unitId: old?.unitId ?? unit.unitId,
      listTypes: old?.listTypes.length ? [...old.listTypes] : [listType.value!] };
    if (!old || old.text !== result.text || old.pinyin !== result.pinyin || old.meaning !== result.meaning || !old.listTypes.length) touched.add(result.rowId);
    return result;
  });
  // 多表共用的条目在当前表删除时，只移除当前归属，保留其他表内容。
  const updatedIds = new Set(updated.map(entry => entry.rowId));
  const retained = visibleEntries.value.filter(entry => !updatedIds.has(entry.rowId) && entry.listTypes.some(type => type !== listType.value))
    .map(entry => {
      touched.add(entry.rowId);
      return { ...entry, listTypes: entry.listTypes.filter(type => type !== listType.value) };
    });
  entries.value = mergeSubset(entries.value, visibleIds, [...updated, ...retained]);
  let index = 0;
  rows.value = next.map(pair => pairHasAnyContent(pair, subject.value) ? { ...pair, tokenId: updated[index++]!.rowId } : pair);
  dirty.value = true;
  successText.value = '';
}

/** 用完整服务端对象更新基线，发布推进过的修订号也要同步。 */
function applyDraft(result: AdminDraftResult): void {
  draft.value = structuredClone(result.draft);
  entries.value = structuredClone((result.draft.entries as DraftEntry[]) ?? []);
  units.value = structuredClone((result.draft.units as EntryUnit[]) ?? []);
  revision.value = result.revision;
  baseVersion.value = result.baseContentVersion;
  if (!unitOptions.value.some(option => option.value === unitKey.value)) unitKey.value = null;
  if (listType.value && !types.value.includes(listType.value)) listType.value = null;
  syncRows();
  dirty.value = false;
  touched.clear();
}

/** 身份临时失效保留输入，明确退出或权限撤销则清理管理状态。 */
function handleAuthFailure(cause: unknown): void {
  if (!(cause instanceof AdminApiError)) return;
  if (cause.isUnauthenticated) auth.markExpired();
  else if (cause.isUnavailable) auth.markUnavailable();
  else if (cause.isForbidden) auth.clearLocal();
}

/** 读取失败不能继续用空草稿覆盖已有教材，迟到回包也不能进入另一账号。 */
async function load(): Promise<void> {
  if (!currentId.value || !auth.canWrite) return;
  const ticket = ++generation;
  loading.value = true;
  errorText.value = '';
  try {
    const result = await fetchDraft(currentId.value);
    if (ticket !== generation || disposed || !auth.canWrite) return;
    completion.cancel();
    applyDraft(result);
    needsReload.value = false;
    loadFailed.value = false;
    publishRetry = null;
    rebaseRetry = null;
    publicationPending.value = false;
    saveRetry = null;
  } catch (cause) {
    if (ticket !== generation || disposed) return;
    handleAuthFailure(cause);
    loadFailed.value = true;
    errorText.value = '教材加载失败，请重试。';
  } finally { if (ticket === generation) loading.value = false; }
}

/** 已保存的输入由服务端与最新发布内容安全合并，禁止客户端只改版本号覆盖正文。 */
async function alignPublishedBaseline(active: () => boolean): Promise<void> {
  if (!rebaseRetry) return;
  const aligned = await rebaseDraft(currentId.value, rebaseRetry.revision, rebaseRetry.key);
  if (!active()) return;
  completion.cancel();
  applyDraft(aligned);
  // 草稿已合并但尚未发布，离开页保护和发布期间的编辑锁仍保持。
  dirty.value = true;
  publicationPending.value = true;
  publishRetry = { intent: { draftRevision: aligned.revision, baseContentVersion: aligned.baseContentVersion, changeNote: '' }, key: crypto.randomUUID() };
  rebaseRetry = null;
}

/** 基线过期只自动对齐一次；网络重试复用对齐标识，真正修订冲突交给用户处理。 */
async function publishWithBaselineRecovery(active: () => boolean): Promise<AdminPublishResult | null> {
  let recovered = false;
  if (rebaseRetry) {
    await alignPublishedBaseline(active);
    recovered = true;
  }
  if (!active() || !publishRetry) return null;
  try {
    return await publishDraft(currentId.value, publishRetry.intent, publishRetry.key);
  } catch (cause) {
    if (!active()) return null;
    if (recovered || !(cause instanceof AdminApiError) || cause.status !== 409 || cause.details?.reason !== 'baselineMismatch') throw cause;
    rebaseRetry = { revision: publishRetry.intent.draftRevision, key: crypto.randomUUID() };
    await alignPublishedBaseline(active);
    if (!active() || !publishRetry) return null;
    return await publishDraft(currentId.value, publishRetry.intent, publishRetry.key);
  }
}

/** 点击发布就是本次内容的确认动作，无需单独校验或逐条勾选。 */
async function saveAndPublish(): Promise<void> {
  if (submitting.value || !auth.canWrite || !ready.value || needsReload.value) return;
  if (!grade.value || !bookName.value.trim()) { errorText.value = '请填写年级和教材名称。'; return; }
  submitting.value = true;
  errorText.value = '';
  successText.value = '';
  const ticket = generation;
  const active = () => ticket === generation && auth.canWrite && !disposed;
  try {
    if (!publishRetry) {
      if (!await completion.flush() || !active()) return;
      updateRows(rows.value);
      const selected = visibleEntries.value;
      if (!selected.length && !entries.value.length) { errorText.value = '请先录入内容。'; return; }
      const missing = selected.findIndex(entry => !entry.text.trim() || !(subject.value === 'english' ? entry.meaning : entry.pinyin)?.trim());
      if (missing >= 0) { errorText.value = `第 ${missing + 1} 行还没填完整，请补齐${subject.value === 'english' ? '中文和英语' : '汉字和拼音'}。`; return; }
      // 仅确认当前可见或本次改过的条目，不替其他管理员确认未展示的草稿。
      const confirmIds = new Set([...touched, ...selected.map(entry => entry.rowId)]);
      const frozenEntries = await Promise.all(entries.value.map(async entry => confirmIds.has(entry.rowId)
        ? { ...entry, confirmed: true, confirmedDigest: await computeContentDigest(entry) } : { ...entry }));
      if (!active()) return;
      const payload = { ...draft.value, editionId: currentId.value || newId, units: units.value, entries: frozenEntries };
      const signature = JSON.stringify(payload);
      if (!saveRetry || saveRetry.signature !== signature) saveRetry = { signature, key: crypto.randomUUID(), revision: revision.value };
      if (!currentId.value) {
        const result = await createTextbook(payload, saveRetry.key);
        if (!active()) return;
        currentId.value = result.editionId;
        revision.value = result.revision;
      } else {
        const result = await saveDraft(currentId.value, saveRetry.revision, payload, saveRetry.key);
        if (!active()) return;
        revision.value = result.revision;
      }
      entries.value = frozenEntries;
      saveRetry = null;
      publicationPending.value = true;
      publishRetry = { intent: { draftRevision: revision.value, baseContentVersion: baseVersion.value, changeNote: '' }, key: crypto.randomUUID() };
    }
    const published = await publishWithBaselineRecovery(active);
    if (!active() || !published) return;
    publishRetry = null;
    rebaseRetry = null;
    publicationPending.value = false;
    dirty.value = false;
    successText.value = published.snapshotReloaded === false ? '内容已提交，暂未在工具箱生效，请稍后重试。' : '保存发布成功，字帖中可以使用这些内容了。';
    needsReload.value = true;
    try {
      const latest = await fetchDraft(currentId.value);
      if (!active()) return;
      completion.cancel();
      applyDraft(latest);
      needsReload.value = false;
    } catch (cause) {
      if (!active()) return;
      handleAuthFailure(cause);
      errorText.value = '内容已发布，但读取最新状态失败，请重试读取后继续编辑。';
    }
  } catch (cause) {
    if (!active()) return;
    handleAuthFailure(cause);
    if (cause instanceof AdminApiError && cause.status === 409) {
      const titles = Object.fromEntries([
        ...entries.value.flatMap(entry => [entry.rowId, entry.entryId].filter((id): id is string => !!id).map(id => [id, entry.text])),
        ...units.value.flatMap(unit => [unit.unitId, unit.clientUnitId].filter((id): id is string => !!id).map(id => [id, unit.title]))
      ]);
      const recovery = entryConflictRecovery(cause, titles);
      needsReload.value = recovery.requiresReload;
      errorText.value = recovery.message;
      // 仅服务端明确拒绝重复标识时换键，网络结果未知仍复用原键和原请求。
      if (recovery.renewRetryKey) {
        if (saveRetry) saveRetry = { ...saveRetry, key: crypto.randomUUID() };
        if (publishRetry) publishRetry = { ...publishRetry, key: crypto.randomUUID() };
        if (rebaseRetry) rebaseRetry = { ...rebaseRetry, key: crypto.randomUUID() };
      }
    } else if (cause instanceof AdminApiError && cause.status === 422) {
      publishRetry = null;
      rebaseRetry = null;
      publicationPending.value = false;
      const result = currentId.value ? await validateDraft(currentId.value).catch(() => null) : null;
      if (!active()) return;
      errorText.value = result?.issues.filter(issue => issue.severity === 'BLOCKING').slice(0, 3).map(issue => {
        const entry = entries.value.find(item => item.rowId === issue.rowId);
        return `${entry?.text ? `“${entry.text}”：` : ''}${validationMessage(issue.code)}`;
      }).join('；') || '内容未保存完整，请检查输入后重试。';
    } else {
      errorText.value = cause instanceof AdminApiError ? adminErrorMessage(cause.messageKey) : '保存发布失败，输入已保留，请重试。';
    }
  } finally { submitting.value = false; }
}

/** 读取新版本前，仅在确实会丢弃输入时确认。 */
async function reloadLatest(): Promise<void> {
  if (dirty.value && !window.confirm('读取最新教材会替换当前输入，是否继续？')) return;
  await load();
}

/** 离开页面保护尚未保存的录入内容。 */
function beforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return;
  event.preventDefault(); event.returnValue = '';
}
onBeforeRouteLeave(() => !dirty.value || window.confirm('当前内容尚未保存发布，确定离开吗？'));
window.addEventListener('beforeunload', beforeUnload);
watch(() => props.editionId, value => { currentId.value = value ?? ''; if (value) void load(); }, { immediate: true });
watch([() => auth.status, () => auth.userId], ([status, id], [, oldId]) => {
  // 同一账号的过期和临时故障只冻结输入，恢复登录后继续使用原缓冲。
  if (id === oldId && status !== 'anonymous') {
    if (status === 'admin') { if (loadFailed.value && !dirty.value) void load(); return; }
    generation++;
    completion.cancel();
    loading.value = false;
    return;
  }
  generation++;
  completion.cancel();
  entries.value = []; rows.value = [{}]; units.value = []; draft.value = {};
  unitKey.value = null; listType.value = null; dirty.value = false; touched.clear();
  saveRetry = null; publishRetry = null; rebaseRetry = null; publicationPending.value = false;
  loading.value = false; loadFailed.value = false; needsReload.value = false;
  if (status === 'admin') void load();
});
onBeforeUnmount(() => { disposed = true; generation++; completion.cancel(); window.removeEventListener('beforeunload', beforeUnload); });
</script>

<template>
  <section class="textbook-entry" aria-label="教材内容录入">
    <header class="entry-header">
      <div><h2>{{ isNew ? '新增教材' : bookName }}</h2><p v-if="!isNew">{{ subject === 'chinese' ? '语文' : '英语' }} · {{ grade }} 年级 · {{ semester === 'first' ? '上学期' : '下学期' }}</p></div>
      <NButton :disabled="submitting" @click="router.push('/admin/textbooks')">重新选教材</NButton>
    </header>
    <NAlert v-if="errorText" type="error" :show-icon="false">{{ errorText }} <NButton v-if="loadFailed || needsReload" text @click="reloadLatest">重新读取</NButton></NAlert>
    <NAlert v-if="successText" type="success" :show-icon="false">{{ successText }}</NAlert>
    <p v-if="publicationPending && !submitting && !needsReload" role="status">发布结果尚未确认，请点击“保存发布”重试，确认后即可继续编辑。</p>
    <NSpin :show="loading">
      <fieldset :disabled="editorLocked" :inert="editorLocked" class="entry-fields">
        <div v-if="isNew" class="book-fields">
          <NFormItem label="科目" :show-feedback="false"><NSelect :value="subject" :disabled="editorLocked || entries.length > 0" :options="[{label:'语文', value:'chinese'}, {label:'英语',value:'english'}]" aria-label="科目" @update:value="value => updateInfo('subject', value)" /></NFormItem>
          <NFormItem label="年级" :show-feedback="false"><NSelect :value="grade" :disabled="editorLocked" :options="gradeOptions" placeholder="选择年级" aria-label="年级" @update:value="value => updateInfo('grade', value)" /></NFormItem>
          <NFormItem label="学期" :show-feedback="false"><NSelect :value="semester" :disabled="editorLocked" :options="[{label:'上学期', value:'first'}, {label:'下学期',value:'second'}]" aria-label="学期" @update:value="value => updateInfo('semester', value)" /></NFormItem>
          <NFormItem label="教材名称" :show-feedback="false"><NInput :value="bookName" :disabled="editorLocked" :input-props="{'aria-label':'教材名称'}" placeholder="如：统编语文" @update:value="value => updateInfo('series', value)" /></NFormItem>
        </div>
        <NFormItem label="单元" :show-feedback="false" class="unit-field"><NSelect :value="unitKey" :disabled="editorLocked" :options="unitOptions" filterable tag placeholder="选择单元，或输入新单元名称" aria-label="单元" @update:value="chooseUnit" /></NFormItem>
        <section class="type-section" aria-label="录入类型">
          <h3>录入类型</h3>
          <div class="type-options"><NButton v-for="type in types" :key="type" :disabled="!selectedUnit || editorLocked" :type="listType === type ? 'primary' : 'default'" :aria-pressed="listType === type" @click="chooseType(type)">{{ listTypeLabel(type, subject) }}</NButton></div>
        </section>
        <section v-if="ready" class="entry-content">
          <WorksheetPairedEditor :subject="subject" :rows="rows" :paginated="false" auto-append label="录入内容" :busy="completion.busy.value" @update:rows="updateRows" @commit="completion.completeNow()" />
          <div class="completion-note" role="status"><span>{{ completion.busy.value ? '正在补全…' : completion.error.value }}</span><NButton text :disabled="editorLocked || completion.busy.value" @click="completion.completeNow(true)">补全空白</NButton></div>
        </section>
        <p v-else class="entry-hint">{{ selectedUnit ? '选择录入类型后，即可填写或粘贴内容。' : '先选单元，再选录入类型。' }}</p>
      </fieldset>
      <footer v-if="ready" class="save-actions"><NButton type="primary" size="large" :loading="submitting" :disabled="!auth.canWrite || needsReload || loadFailed" @click="saveAndPublish">保存发布</NButton><span>发布后即可在字帖中选择使用。</span></footer>
    </NSpin>
    <details v-if="currentId" class="more-management"><summary>更多管理</summary><NButton text @click="router.push(`/admin/textbooks/${currentId}/advanced`)">教材信息、目录与版本记录</NButton></details>
  </section>
</template>

<style scoped>
.textbook-entry { max-width: 1060px; margin: 0 auto; padding: 20px; color: var(--ink); }
.entry-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; }
h2, h3 { margin: 0; } h2 { font-size: 22px; } h3 { font-size: 16px; margin-bottom: 10px; }
.entry-header p, .entry-hint, .save-actions span, .completion-note, .more-management { color: var(--muted); font-size: 13px; }
.entry-fields { margin: 0; padding: 0; border: 0; min-width: 0; }
.book-fields { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 20px; }
.book-fields > :last-child { grid-column: 1 / -1; }
.unit-field { max-width: 480px; margin-bottom: 24px; }
.type-options { display: flex; gap: 10px; flex-wrap: wrap; }
.entry-content { margin-top: 24px; }
.completion-note { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }
.save-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; margin-top: 24px; }
.more-management { margin-top: 36px; } .more-management summary { cursor: pointer; margin-bottom: 10px; }
:deep(.n-alert) { margin: 12px 0; }
@media (max-width: 600px) {
  .textbook-entry { padding: 12px; } .book-fields { grid-template-columns: 1fr; }
  .entry-header h2 { font-size: 19px; } .entry-header { gap: 10px; }
  .save-actions :deep(.n-button) { width: 100%; }
  :deep(.n-button), :deep(.n-base-selection) { min-height: 44px; }
}
</style>
