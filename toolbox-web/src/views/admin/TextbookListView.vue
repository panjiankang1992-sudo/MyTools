<script setup lang="ts">
import { NAlert, NButton, NInput, NPagination, NSelect, NSpin, NTag } from 'naive-ui';
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { AdminApiError } from '../../services/admin-api';
import { describeTextbook, gradesForSubject } from '../../lib/admin-textbook-view';
import { fetchTextbookList, type AdminTextbookListItem } from '../../services/admin-textbooks-api';
import { adminErrorMessage } from '../../lib/admin-error-messages';

const router = useRouter();
const subject = ref<string | null>(null);
const grade = ref<number | null>(null);
const semester = ref<string | null>(null);
const editionId = ref<string | null>(null);
const choices = ref<AdminTextbookListItem[]>([]);
const choicesLoading = ref(false);
const choicesLoaded = ref(false);
const choicesError = ref('');
let choicesRequest = 0;

const subjectOptions = [
  { label: '语文', value: 'chinese' },
  { label: '英语', value: 'english' }
];
const semesterOptions = [
  { label: '上学期', value: 'first' },
  { label: '下学期', value: 'second' }
];
const gradeOptions = computed(() => gradesForSubject(subject.value).map(value => ({ label: `${value} 年级`, value })));
const canLoadChoices = computed(() => Boolean(subject.value && grade.value && semester.value));
const textbookOptions = computed(() => choices.value.map(item => {
  const display = describeTextbook(item.editionId, item.metadataJson);
  // 主流程使用教材名称；缺元数据的记录仍可在更多操作中维护。
  const title = display.title === item.editionId ? '未命名教材' : display.title;
  return { label: [title, display.subtitle].filter(Boolean).join(' · '), value: item.editionId };
}));

/** 上游选择改变后清空下游，旧请求不能恢复之前的教材。 */
function onSubjectChange(value: string | null): void {
  subject.value = value;
  grade.value = null;
  semester.value = null;
  void loadChoices();
}

/** 年级改变后重新选择学期和教材。 */
function onGradeChange(value: number | null): void {
  grade.value = value;
  semester.value = null;
  void loadChoices();
}

/** 学期改变后查询对应教材，不默认替用户选择。 */
function onSemesterChange(value: string | null): void {
  semester.value = value;
  void loadChoices();
}

/** 获取当前范围内的教材，分页接口的后续页也进入下拉选项。 */
async function loadChoices(): Promise<void> {
  const request = ++choicesRequest;
  editionId.value = null;
  choices.value = [];
  choicesError.value = '';
  choicesLoaded.value = false;
  choicesLoading.value = false;
  if (!canLoadChoices.value) return;
  const query = { subject: subject.value!, grade: grade.value!, semester: semester.value! };
  choicesLoading.value = true;
  try {
    const collected: AdminTextbookListItem[] = [];
    let nextPage = 1;
    while (true) {
      const result = await fetchTextbookList({ ...query, page: nextPage, pageSize: 100 });
      // 切换范围或离开页面后，忽略旧回包，包括旧错误和加载状态。
      if (request !== choicesRequest) return;
      collected.push(...result.items);
      if (collected.length >= result.total || result.items.length === 0) break;
      nextPage += 1;
    }
    choices.value = collected;
    choicesLoaded.value = true;
  } catch (cause) {
    if (request !== choicesRequest) return;
    choicesError.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
  } finally {
    if (request === choicesRequest) choicesLoading.value = false;
  }
}

/** 使用已选教材进入录入页。 */
function startEntry(): void {
  if (editionId.value && choices.value.some(item => item.editionId === editionId.value)) {
    void router.push(`/admin/textbooks/${encodeURIComponent(editionId.value)}/edit`);
  }
}

const moreOpen = ref(false);
const loading = ref(false);
const items = ref<AdminTextbookListItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const errorText = ref('');
const loaded = ref(false);
const keyword = ref('');
const status = ref<string | null>(null);
let listRequest = 0;
const statusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' }
];
const hasFilter = computed(() => Boolean(keyword.value || status.value));

/** 展开时才加载管理列表，日常录入无需经过这些操作。 */
function onMoreToggle(event: Event): void {
  moreOpen.value = (event.target as HTMLDetailsElement).open;
  if (moreOpen.value && !loaded.value && !loading.value) void load();
}

/** 按条件重新查询管理列表。 */
async function reload(): Promise<void> {
  page.value = 1;
  await load();
}

/** 管理列表独立分页，不改变主流程已选择的教材。 */
async function load(): Promise<void> {
  const request = ++listRequest;
  loading.value = true;
  errorText.value = '';
  try {
    const result = await fetchTextbookList({
      keyword: keyword.value || undefined,
      status: status.value ?? undefined,
      page: page.value,
      pageSize
    });
    if (request !== listRequest) return;
    items.value = result.items;
    total.value = result.total;
  } catch (cause) {
    if (request !== listRequest) return;
    errorText.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
    items.value = [];
    total.value = 0;
  } finally {
    if (request === listRequest) {
      loading.value = false;
      loaded.value = true;
    }
  }
}

/** 清除管理列表筛选。 */
async function clearFilters(): Promise<void> {
  keyword.value = '';
  status.value = null;
  await reload();
}

onBeforeUnmount(() => {
  choicesRequest += 1;
  listRequest += 1;
});
</script>

<template>
  <section class="admin-list" aria-label="教材录入">
    <header class="list-header">
      <div>
        <h2>教材录入</h2>
        <p class="admin-note">选择教材后，选择单元和录入类型，填写内容并保存发布。</p>
      </div>
      <NButton @click="router.push('/admin/textbooks/new')">新增教材</NButton>
    </header>

    <h3>选择教材</h3>
    <div class="selection-row">
      <label>
        <span>科目</span>
        <NSelect :value="subject" :options="subjectOptions" placeholder="请选择科目" aria-label="科目" clearable @update:value="onSubjectChange" />
      </label>
      <label>
        <span>年级</span>
        <NSelect :value="grade" :options="gradeOptions" :disabled="!subject" placeholder="请选择年级" aria-label="年级" clearable @update:value="onGradeChange" />
      </label>
      <label>
        <span>学期</span>
        <NSelect :value="semester" :options="semesterOptions" :disabled="!grade" placeholder="请选择学期" aria-label="学期" clearable @update:value="onSemesterChange" />
      </label>
      <label class="textbook-select">
        <span>教材名称</span>
        <NSelect v-model:value="editionId" :options="textbookOptions" :disabled="!canLoadChoices || choicesLoading" :loading="choicesLoading" placeholder="请选择教材" aria-label="教材名称" filterable clearable />
      </label>
    </div>
    <NAlert v-if="choicesError" type="error" :show-icon="false" class="list-error">
      教材加载失败：{{ adminErrorMessage(choicesError) }}　<NButton text @click="loadChoices">重试</NButton>
    </NAlert>
    <p v-else-if="choicesLoaded && !choices.length" class="admin-note">这个范围还没有教材，可点击「新增教材」。</p>
    <NButton type="primary" class="start-entry" :disabled="!editionId || choicesLoading" @click="startEntry">开始录入</NButton>

    <details class="more-operations" @toggle="onMoreToggle">
      <summary>更多操作</summary>
      <div v-if="moreOpen" class="management-list">
        <div class="filter-row">
          <NInput v-model:value="keyword" placeholder="搜索教材名称、出版社或系列" aria-label="关键字" clearable @keyup.enter="reload" />
          <NSelect v-model:value="status" :options="statusOptions" placeholder="状态" aria-label="状态" clearable @update:value="reload" />
          <NButton @click="reload">查询</NButton>
          <NButton data-testid="open-restore" @click="router.push('/admin/curriculum/restore')">恢复导入包</NButton>
        </div>
        <NAlert v-if="errorText" type="error" :show-icon="false" class="list-error">
          列表加载失败：{{ adminErrorMessage(errorText) }}　<NButton text @click="load">重试</NButton>
        </NAlert>
        <NSpin :show="loading">
          <table v-if="items.length" class="textbook-table">
            <thead><tr><th>教材</th><th>内容</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="item in items" :key="item.editionId">
                <td>
                  <strong>{{ describeTextbook(item.editionId, item.metadataJson).title }}</strong>
                  <span class="sub">{{ describeTextbook(item.editionId, item.metadataJson).subtitle }}</span>
                </td>
                <td>{{ describeTextbook(item.editionId, item.metadataJson).counts }}</td>
                <td>
                  <NTag size="small" :type="item.published ? 'success' : 'default'" :bordered="false">{{ item.published ? '已发布' : '草稿' }}</NTag>
                  <NTag v-if="item.hasUnpublishedChanges" size="small" type="warning" :bordered="false">有修改</NTag>
                </td>
                <td class="sub">{{ item.updatedBy }} · {{ new Date(item.updatedAt).toLocaleString() }}</td>
                <td><NButton text @click="router.push(`/admin/textbooks/${encodeURIComponent(item.editionId)}/edit`)">选择并录入</NButton></td>
              </tr>
            </tbody>
          </table>
          <p v-else-if="!loading && !errorText && loaded && hasFilter" class="admin-note empty">
            没有符合条件的教材。<NButton text @click="clearFilters">清除筛选</NButton>
          </p>
          <p v-else-if="!loading && !errorText && loaded" class="admin-note empty">还没有教材，点击「新增教材」开始录入。</p>
        </NSpin>
        <NPagination v-if="total > pageSize" v-model:page="page" :page-size="pageSize" :item-count="total" class="list-pagination" @update:page="load" />
      </div>
    </details>
  </section>
</template>

<style scoped>
.admin-list { padding: 20px; color: var(--ink); }
.list-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.list-header > :deep(.n-button) { flex-shrink: 0; }
.admin-note { color: var(--ink-muted, #666); margin: 4px 0 0; }
h3 { margin: 24px 0 12px; font-size: 17px; }
.selection-row { display: grid; grid-template-columns: repeat(3, minmax(100px, 1fr)) minmax(240px, 2fr); gap: 12px; }
.selection-row label { min-width: 0; }
.selection-row label > span { display: block; margin-bottom: 6px; }
.start-entry { margin-top: 20px; }
.more-operations { margin-top: 32px; border-top: 1px solid var(--border, #e5e5e5); padding-top: 16px; }
.more-operations summary { cursor: pointer; color: var(--ink-muted, #666); width: fit-content; }
.filter-row { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(100px, 160px) auto auto; gap: 8px; margin: 16px 0; }
.list-error { margin: 12px 0; }
.textbook-table { width: 100%; border-collapse: collapse; }
.textbook-table th, .textbook-table td { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--border, #e5e5e5); vertical-align: top; }
.textbook-table .sub { display: block; color: var(--ink-muted, #666); font-size: 13px; }
.empty { padding: 24px 0; }
.list-pagination { margin-top: 16px; justify-content: flex-end; }
@media (max-width: 900px) {
  .selection-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .textbook-select { grid-column: 1 / -1; }
}
@media (max-width: 720px) {
  .admin-list { padding: 16px; }
  .filter-row { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
  .selection-row { gap: 8px; }
  .start-entry { width: 100%; }
  :deep(.n-button), :deep(.n-base-selection), :deep(.n-input), summary { min-height: 44px; }
  :deep(.n-button) { min-width: 44px; }
  :deep(.n-input__input-el), :deep(.n-input__textarea-el), :deep(.n-base-selection-label), :deep(.n-base-selection-input) { min-height: 44px; }
  :deep(.n-input), :deep(.n-base-selection) { --n-height: 44px; }
  .textbook-table thead { display: none; }
  .textbook-table td { display: block; border: none; padding: 4px 0; }
  .textbook-table tr { display: block; border-bottom: 1px solid var(--border, #e5e5e5); padding: 10px 0; }
}
</style>
