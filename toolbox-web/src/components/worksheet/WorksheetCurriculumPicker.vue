<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { NAlert, NButton, NCheckbox, NCheckboxGroup, NFormItem, NSelect, NTag, NTreeSelect } from 'naive-ui';
import { curriculumEditionLabel, curriculumScopeLabel, useWorksheetCurriculum, type WorksheetCurriculumImport } from '../../composables/useWorksheetCurriculum';
import type { ListType, Semester, Subject } from '../../domain/curriculum';

/** 教材是原文输入来源，年级、册次、版本、字表与课文都不决定最终练习题型。 */
const props = defineProps<{ subject: Subject; hasExistingText?: boolean }>();
const emit = defineEmits<{ import: [selection: WorksheetCurriculumImport, mode: 'append' | 'replace'] }>();
const picker = useWorksheetCurriculum(props.subject);
const { grade, semester, editionId, version, listTypes, scopes, loading, catalogLoading, error,
  catalog, gradeOptions, editions, coverage, selectedEdition, scopeTree, selectedEntries } = picker;
const editionOptions = computed(() => editions.value.map(edition => ({
  label: curriculumEditionLabel(edition), value: edition.editionId
})));
/** 按真实单元与附录分组展示所选词条，便于直接对照原书单词表。 */
const selectedWordGroups = computed(() => props.subject === 'english'
  ? (version.value?.units ?? []).slice().sort((left, right) => left.order - right.order).map(unit => ({
    key: unit.unitId, label: curriculumScopeLabel(unit.title, unit.unitId, unit.order, 'unit'),
    entries: selectedEntries.value.filter(entry => entry.unitId === unit.unitId)
  })).filter(group => group.entries.length > 0)
  : []);
const listLabels: Record<ListType, string> = {
  recognition: '识字表', writing: '写字表', vocabulary: props.subject === 'chinese' ? '词语表' : '单词表', expression: '表达与短语'
};
onMounted(() => { void picker.loadCatalog(); });

/** 导入完整身份快照，父组件负责追加到同一个原文输入框。 */
function importSelection(mode: 'append' | 'replace' = 'append'): void {
  const selected = picker.getImport();
  if (selected) emit('import', selected, mode);
}
</script>

<template>
  <section class="curriculum-picker" :aria-label="subject === 'english' ? '选择教材单词表' : '选择教材内容'" tabindex="-1">
    <p v-if="subject === 'english'" class="picker-note">单元词表与专有名词附录分列；全选包含全部。</p>
    <div class="picker-fields">
      <NFormItem label="年级" :show-feedback="false">
        <NSelect :value="grade" :options="gradeOptions" aria-label="年级" @update:value="picker.chooseGrade" />
      </NFormItem>
      <NFormItem label="册次" :show-feedback="false">
        <NSelect :value="semester" :options="[{ label: '上册', value: 'first' }, { label: '下册', value: 'second' }]" aria-label="册次" @update:value="value => picker.chooseSemester(value as Semester)" />
      </NFormItem>
      <NFormItem v-if="editionOptions.length > 1" label="教材" :show-feedback="false" class="wide-field">
        <NSelect :value="editionId" :options="editionOptions" :loading="catalogLoading" aria-label="教材版本" @update:value="picker.chooseEdition" />
      </NFormItem>
    </div>
    <p v-if="selectedEdition && editionOptions.length === 1" class="selected-textbook">{{ curriculumEditionLabel(selectedEdition) }}</p>
    <NAlert v-if="error" type="warning" :show-icon="false">
      {{ error }} <NButton text @click="picker.loadCatalog">重试加载</NButton>
    </NAlert>
    <p v-else-if="catalog && !editionOptions.length" class="picker-note">当前册次尚未发布内容，可选择其他年级或册次，也可以直接输入文本。</p>
    <div v-if="coverage" class="coverage-row">
      <NTag size="small" :type="coverage.coverageStatus === 'complete' ? 'success' : 'warning'" :bordered="false">
        {{ coverage.coverageStatus === 'complete' ? '内容完整' : coverage.coverageStatus === 'partial' ? '部分内容可用' : '尚未发布' }}
      </NTag>
      <details v-if="selectedEdition?.missingScopes.length" class="coverage-details">
        <summary>查看缺少的内容</summary>
        <ul><li v-for="gap in selectedEdition.missingScopes" :key="gap">{{ gap }}</li></ul>
      </details>
    </div>
    <template v-if="version">
      <NFormItem :label="subject === 'english' ? '单词表范围' : '练习范围'" :show-feedback="false">
        <NTreeSelect v-model:value="scopes" :options="scopeTree" multiple checkable :cascade="false" :max-tag-count="2" :placeholder="subject === 'english' ? '选择单元或附录' : '选择单元或课文'" :aria-label="subject === 'english' ? '单词表单元范围' : '单元或课文范围'" />
      </NFormItem>
      <div class="picker-actions scope-actions" aria-label="练习范围操作">
        <NButton size="small" :disabled="loading || catalogLoading" @click="picker.selectAllScopes">全选</NButton>
        <NButton size="small" quaternary :disabled="loading || catalogLoading || !scopes.length" @click="scopes = []">清除选择</NButton>
      </div>
      <section v-if="subject === 'english' && selectedEntries.length" class="selected-word-preview" aria-label="所选单词表内容" tabindex="0">
        <div v-for="group in selectedWordGroups" :key="group.key" class="selected-word-group">
          <h3>{{ group.label }}</h3>
          <ul class="selected-word-items">
            <li v-for="entry in group.entries" :key="entry.entryId">
              <strong>{{ entry.text }}</strong>
              <span>{{ entry.meaning }}</span>
              <small v-if="entry.context">{{ entry.context }}</small>
            </li>
          </ul>
        </div>
      </section>
      <details v-if="subject === 'chinese'" class="filter-options">
        <summary>更多筛选<template v-if="listTypes.length !== version.listTypes.length"> · {{ listTypes.length ? listTypes.map(kind => listLabels[kind]).join('、') : '未选字表' }}</template></summary>
        <div class="filter-options-body">
          <NFormItem label="字表类型" :show-feedback="false">
            <NCheckboxGroup :value="listTypes" class="list-options" @update:value="value => listTypes = value.map(String) as ListType[]">
              <NCheckbox v-for="kind in version.listTypes" :key="kind" :value="kind">{{ listLabels[kind] }}</NCheckbox>
            </NCheckboxGroup>
          </NFormItem>
          <NButton v-if="hasExistingText" text :disabled="!selectedEntries.length || loading" @click="importSelection('replace')">替换当前内容并继续</NButton>
        </div>
      </details>
      <div v-else-if="hasExistingText" class="picker-actions">
        <NButton text :disabled="!selectedEntries.length || loading || catalogLoading" @click="importSelection('replace')">替换当前内容并继续</NButton>
      </div>
      <div class="picker-actions primary-action">
        <NButton type="primary" :loading="loading" :disabled="!selectedEntries.length || loading" @click="importSelection('append')">{{ hasExistingText ? '下一步（追加）' : '下一步' }}</NButton>
        <span class="picker-note">已选 {{ selectedEntries.length }} 项<template v-if="hasExistingText"> · 保留已有内容</template></span>
      </div>
    </template>
    <p v-else-if="loading || catalogLoading" class="picker-note">正在加载教材…</p>
  </section>
</template>

<style scoped>
.curriculum-picker { display: flex; flex-direction: column; gap: 12px; border: 1px solid var(--border); border-radius: 8px; padding: 14px; min-width: 0; }
.selected-textbook { margin: 0; color: var(--ink); font-size: 14px; line-height: 1.6; }
.filter-options summary, .coverage-details summary { cursor: pointer; color: var(--muted); font-size: 12px; width: fit-content; padding: 5px 0; }
.filter-options-body { display: flex; flex-direction: column; align-items: flex-start; gap: 12px; padding-top: 10px; }
.filter-options-body :deep(.n-form-item) { width: 100%; }
.primary-action { padding-top: 2px; }
.selected-word-preview { max-height: 200px; overflow-y: auto; min-width: 0; border: 1px solid var(--border); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 12px; }
.selected-word-preview:focus-visible { outline: 2px solid #646cff; outline-offset: 2px; }
.selected-word-group { min-width: 0; }
.selected-word-group h3 { margin: 0 0 6px; color: var(--muted); font-size: 12px; font-weight: 500; }
.selected-word-items { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 14px; }
.selected-word-items li { display: flex; flex-direction: column; gap: 2px; min-width: 0; overflow-wrap: anywhere; line-height: 1.5; }
.selected-word-items strong { color: var(--ink); font-size: 14px; font-weight: 600; }
.selected-word-items span { color: var(--ink); font-size: 12px; }
.selected-word-items small { color: var(--muted); font-size: 11px; }
.picker-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.wide-field { grid-column: 1 / -1; }
.picker-note, .coverage-row { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.coverage-row, .picker-actions, .list-options { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
ul { margin: 6px 0 0; padding-left: 18px; }
:deep(.n-form-item-blank) { min-width: 0; }
@media (pointer: coarse), (max-width: 720px) { .scope-actions :deep(.n-button) { min-height: 44px; } }
@media (max-width: 720px) { .selected-word-items { grid-template-columns: minmax(0, 1fr); } summary { min-height: 44px; display: flex; align-items: center; } }
@media (max-width: 480px) { .picker-fields { grid-template-columns: minmax(0, 1fr); } .picker-actions :deep(.n-button) { max-width: 100%; white-space: normal; height: auto; min-height: 44px; } }
</style>
