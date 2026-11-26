<script setup lang="ts">
import { computed } from 'vue';
import { NAlert, NButton, NCheckbox, NCheckboxGroup, NFormItem, NInputNumber, NRadio, NRadioGroup, NSelect, NTag, NTree } from 'naive-ui';
import { text } from '../locales/common';
import { worksheetText as t } from '../locales/worksheet';
import type { WorksheetDraftState } from '../composables/useWorksheetDraft';
import type { ListType } from '../domain/curriculum';

/**
 * 教材范围选择器。
 * 只提供目录中真实可用的选项，并显示覆盖状态；未发布的册次明确提示，不猜测内容。
 */
const props = defineProps<{ draft: WorksheetDraftState }>();

const semesterOptions = [{ label: t.upper, value: 'first' }, { label: t.lower, value: 'second' }];

const editionOptions = computed(() => props.draft.editions.map(edition => ({
  label: `${edition.series} · ${edition.editionLabel}`,
  value: edition.editionId
})));

const coverageTag = computed(() => {
  const status = props.draft.coverage?.coverageStatus ?? 'missing';
  if (status === 'complete') return { type: 'success' as const, label: `${t.coverageScope}: ${text.coverageComplete}` };
  if (status === 'partial') return { type: 'warning' as const, label: `${t.coverageScope}: ${text.coveragePartial}` };
  return { type: 'default' as const, label: `${t.coverageScope}: ${text.coverageMissing}` };
});

/** 已选版本声明的缺口，必须在界面上说明，不能让用户以为整册完整。 */
const selectedGaps = computed(() => {
  const selected = props.draft.editions.find(edition => edition.editionId === props.draft.editionId);
  return selected?.missingScopes ?? [];
});

/** 清单类别文案：按学科显示真实存在的类别。 */
const listTypeOptions = computed(() => props.draft.availableListTypes.map(value => ({
  value,
  label: listLabel(value)
})));

/** 清单类别中文名称。 */
function listLabel(value: ListType): string {
  if (value === 'recognition') return t.listRecognition;
  if (value === 'writing') return t.listWriting;
  if (value === 'expression') return t.listExpression;
  return props.draft.contentSource === 'textbook' && props.draft.editionId ? t.listVocabulary : t.englishVocabulary;
}

/** 单元与课文合并为同一棵树的勾选键。 */
const checkedKeys = computed(() => [...props.draft.unitIds, ...props.draft.lessonIds]);

/** 拆分勾选结果为单元与课文，父节点半选由 NTree 处理。 */
function updateChecked(keys: Array<string | number>): void {
  const values = keys.map(String);
  const unitKeys = new Set(props.draft.unitTree.map(node => String(node.key)));
  props.draft.unitIds = values.filter(value => unitKeys.has(value));
  props.draft.lessonIds = values.filter(value => !unitKeys.has(value));
}

/** 切换选题策略并立即重新抽取。 */
function chooseStrategy(value: string): void {
  props.draft.applyStrategy(value as WorksheetDraftState['strategy'], value === 'all' ? null : props.draft.seed);
}
</script>

<template>
  <section class="settings-section">
    <h2><span class="step-number">1</span>{{ t.selectContent }}</h2>
    <NAlert v-if="draft.catalogState === 'unavailable'" type="warning" :show-icon="false" class="picker-alert">
      {{ text.textbookPendingHint }}
    </NAlert>
    <div class="field-grid">
      <NFormItem :label="t.grade" :show-feedback="false">
        <NSelect v-model:value="draft.grade" :options="draft.gradeOptions" :placeholder="t.chooseGrade" :aria-label="t.grade" />
      </NFormItem>
      <NFormItem :label="t.semester" :show-feedback="false">
        <NSelect v-model:value="draft.semester" :options="semesterOptions" :aria-label="t.semester" />
      </NFormItem>
      <NFormItem :label="t.textbook" :show-feedback="false" class="wide-field">
        <NSelect
          v-model:value="draft.editionId"
          :options="editionOptions"
          :placeholder="draft.grade === null ? t.chooseGrade : t.noEdition"
          :disabled="!editionOptions.length"
          clearable
          :aria-label="t.textbook"
        />
      </NFormItem>
    </div>

    <div class="coverage-row">
      <NTag size="small" :type="coverageTag.type" :bordered="false">{{ coverageTag.label }}</NTag>
      <span v-if="draft.coverage">{{ text.textbookCoverage }} · {{ draft.coverage.editionIds.length }} {{ text.publishedEdition }}</span>
    </div>

    <NAlert v-if="selectedGaps.length" type="warning" :show-icon="false" class="picker-alert gap-alert">
      <strong>{{ text.coveragePartial }}</strong>
      <ul class="gap-list">
        <li v-for="gap in selectedGaps" :key="gap">{{ gap }}</li>
      </ul>
    </NAlert>

    <div v-if="draft.editionId && draft.version" class="scope-block">
      <NFormItem :label="t.scopeUnits" :show-feedback="false">
        <NTree
          :data="draft.unitTree"
          checkable
          cascade
          selectable
          block-line
          :checked-keys="checkedKeys"
          :default-expand-all="true"
          class="scope-tree"
          @update:checked-keys="updateChecked"
        />
      </NFormItem>
      <p class="field-note">{{ draft.unitIds.length + draft.lessonIds.length ? `${t.selectedScope}：${draft.unitIds.length + draft.lessonIds.length}` : t.scopeAll }}</p>
      <NCheckboxGroup :value="draft.listTypes" class="kind-options" :aria-label="t.contentKind" @update:value="value => { draft.listTypes = value.map(String) as ListType[]; }">
        <NCheckbox v-for="option in listTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</NCheckbox>
      </NCheckboxGroup>
    </div>

    <div class="strategy-block">
      <NFormItem :label="t.strategyLabel" :show-feedback="false">
        <NRadioGroup :value="draft.strategy" :aria-label="t.strategyLabel" @update:value="value => chooseStrategy(String(value))">
          <NRadio value="all">{{ t.strategyAll }}</NRadio>
          <NRadio value="random">{{ t.strategyRandom }}</NRadio>
          <NRadio value="balanced">{{ t.strategyBalanced }}</NRadio>
        </NRadioGroup>
      </NFormItem>
      <div class="field-grid">
        <NFormItem v-if="draft.strategy !== 'all'" :label="text.drawCount" :show-feedback="false">
          <NInputNumber v-model:value="draft.requestedCount" :min="1" :max="200" :precision="0" :aria-label="text.drawCount" @update:value="() => draft.refreshSelection()" />
        </NFormItem>
        <NFormItem :label="t.orderLabel" :show-feedback="false" :class="{ 'wide-field': draft.strategy === 'all' }">
          <NSelect
            v-model:value="draft.ordering"
            :options="[{ label: t.orderTextbook, value: 'textbook' }, { label: t.orderRandom, value: 'random' }]"
            :aria-label="t.orderLabel"
            @update:value="() => draft.refreshSelection()"
          />
        </NFormItem>
      </div>
      <div class="strategy-actions">
        <NButton size="small" :disabled="!draft.candidates.length" @click="draft.refreshSelection()">{{ t.strategyAll }}</NButton>
        <NButton size="small" secondary type="primary" @click="draft.regroup()">{{ text.regroup }}</NButton>
        <NButton v-if="draft.previousSelection" size="small" text @click="draft.restoreGroup()">{{ text.restoreGroup }}</NButton>
      </div>
      <p v-if="draft.seed" class="field-note">{{ text.seedLabel }}：{{ draft.seed }}</p>
      <NAlert v-if="draft.lastPlan?.shortage" type="warning" :show-icon="false" class="picker-alert">
        {{ text.shortage }}（{{ draft.lastPlan.available }}）
      </NAlert>
      <NAlert v-if="draft.lastPlan?.blocked.length" type="warning" :show-icon="false" class="picker-alert">
        {{ t.blockedCount }}：{{ draft.lastPlan.blocked.length }}
      </NAlert>
      <p v-if="draft.lastPlan?.quotaByUnit.length && draft.strategy === 'balanced'" class="field-note">
        {{ text.quotaSummary }}：{{ draft.lastPlan.quotaByUnit.map(item => `${item.unitId.split('-').pop()}=${item.quota}`).join(' / ') }}
      </p>
    </div>
  </section>
</template>

<style scoped>
.settings-section { padding: 21px 0; border-bottom: 1px solid var(--border); }
h2 { margin: 0 0 20px; color: var(--ink); font-size: 16px; font-weight: 650; display: flex; align-items: center; gap: 10px; }
.step-number { width: 27px; height: 27px; display: grid; place-items: center; border-radius: 50%; color: #6366f1; background: #eef0ff; font-size: 16px; }
.field-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px 10px; }
.wide-field { grid-column: 1 / -1; }
.picker-alert { margin-top: 12px; font-size: 12px; border-radius: 6px; }
.coverage-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 14px; font-size: 12px; color: var(--muted); }
.scope-block { margin-top: 6px; }
.scope-tree { width: 100%; max-height: 240px; overflow: auto; }
.kind-options { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 10px; }
.strategy-block { margin-top: 6px; }
.strategy-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.strategy-actions :deep(.n-button) { min-height: 34px; }
.field-note { font-size: 11px; color: var(--muted); margin: 10px 0 0; line-height: 1.7; }
.gap-alert { font-size: 12px; }
.gap-list { margin: 6px 0 0; padding-left: 18px; line-height: 1.7; }
:deep(.n-form-item-label) { font-size: 12px; color: var(--muted); padding-bottom: 5px; }
:deep(.n-radio__label) { font-size: 13px; }
@media (max-width: 767px) {
  .field-grid { gap: 13px 10px; }
  .strategy-actions :deep(.n-button) { min-height: 44px; }
}
</style>
