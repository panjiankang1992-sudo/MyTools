<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { NButton, NInput, NSelect, NTag } from 'naive-ui';
import { useWorksheetPinyinEditor } from '../../composables/useWorksheetPinyinEditor';
import { englishInputValue, worksheetEntryPresentation } from './worksheet-entry-presentation';
import type { InputToken } from '../../domain/worksheet-input';

/** 按学科校对并分页展示全部条目，英语释义不依赖拼音状态。 */
const props = defineProps<{
  subject: 'chinese' | 'english';
  tokens: readonly InputToken[];
  isAmbiguous: (grapheme: string) => boolean;
  limit?: number;
}>();
const emit = defineEmits<{
  updatePinyin: [tokenId: string, syllables: string];
  updateMeaning: [tokenId: string, meaning: string];
  updateEnglish: [tokenId: string, value: string];
  commitEnglish: [];
  confirmToken: [tokenId: string];
  confirmAll: [];
  pendingInvalid: [value: boolean];
  pendingPinyin: [value: boolean];
}>();
const page = ref(1);
const pageSize = computed(() => Math.max(1, props.limit ?? 30));
const pageCount = computed(() => Math.max(1, Math.ceil(props.tokens.length / pageSize.value)));
watch(pageCount, count => { page.value = Math.min(page.value, count); });
const offset = computed(() => (page.value - 1) * pageSize.value);
const shown = computed(() => props.tokens.slice(offset.value, offset.value + pageSize.value));
const isChinese = computed(() => props.subject === 'chinese');
const missing = computed(() => props.tokens.filter(token => worksheetEntryPresentation(token, props.subject).missing).length);
const suggested = computed(() => props.tokens.filter(token => worksheetEntryPresentation(token, props.subject).automatic).length);
const polyphonicCount = computed(() => isChinese.value ? props.tokens.filter(token =>
  [...token.text].some(grapheme => props.isAmbiguous(grapheme))).length : 0);
const pageOptions = computed(() => Array.from({ length: pageCount.value }, (_, index) => ({
  label: `第 ${index + 1} 页（${index * pageSize.value + 1}–${Math.min((index + 1) * pageSize.value, props.tokens.length)} 项）`, value: index + 1
})));

/** 英文框保留逐键输入，避免领域层trim导致短语中的空格无法输入。 */
const englishDrafts = ref<Record<string, string>>({});
watch(() => props.tokens, tokens => {
  const ids = new Set(tokens.map(token => token.tokenId));
  for (const id of Object.keys(englishDrafts.value)) if (!ids.has(id)) delete englishDrafts.value[id];
});

/** 同步人工修改，同时保留显示缓冲，不接受自动回填覆盖用户当前输入。 */
function updateEnglishInput(token: InputToken, value: string): void {
  englishDrafts.value[token.tokenId] = value;
  emit('updateEnglish', token.tokenId, value);
}

/** 失焦后只有模型已接纳人工值才移除缓冲。 */
async function finishEnglishInput(token: InputToken): Promise<void> {
  await nextTick();
  const current = props.tokens.find(item => item.tokenId === token.tokenId);
  const draft = englishDrafts.value[token.tokenId];
  if (current && draft !== undefined && englishInputValue(current) === draft.trim()) delete englishDrafts.value[token.tokenId];
  if (draft !== undefined) emit('commitEnglish');
}

/** 编辑中的拼音先保存在缓冲区，失焦或回车后再提交模型。 */
const pinyinEditor = useWorksheetPinyinEditor(() => props.tokens, (tokenId, value) => emit('updatePinyin', tokenId, value));
const { drafts: pinyinDrafts, invalid: invalidDrafts, valueOf: pinyinValue, commit: commitPinyin } = pinyinEditor;
watch(invalidDrafts, invalid => emit('pendingInvalid', invalid.length > 0), { immediate: true });
watch(pinyinEditor.pending, pending => emit('pendingPinyin', pending), { immediate: true });

/** 有效自动结果直接可用，仍如实标注机器生成并允许修改。 */
function stateOf(token: InputToken) { return worksheetEntryPresentation(token, props.subject); }

/** 定位尚缺字段的条目，不把有效自动建议当作缺失。 */
function locateReview(): void {
  const pending = props.tokens.map((token, index) => ({ token, index }))
    .filter(({ token }) => stateOf(token).missing);
  const found = pending.find(({ index }) => index >= offset.value + shown.value.length) ?? pending[0];
  if (found) page.value = Math.floor(found.index / pageSize.value) + 1;
}
</script>

<template>
  <section class="entry-list" aria-label="逐项校对">
    <header class="list-head">
      <h2>{{ isChinese ? '拼音' : '中英文' }}</h2>
      <div class="list-counts">
        <NTag size="small" :bordered="false">共 {{ tokens.length }} 项</NTag>
        <NTag v-if="suggested" size="small" :bordered="false">自动生成 {{ suggested }} 项</NTag>
        <NTag v-if="missing" size="small" type="warning" :bordered="false">待补全 {{ missing }} 项</NTag>
      </div>
    </header>
    <p class="list-hint">
      <template v-if="isChinese">拼音会自动生成，可以直接使用或修改<template v-if="polyphonicCount">；{{ polyphonicCount }} 项含多音字，可按实际读音调整</template>。</template>
      <template v-else>中文、英文会自动补全，任何一栏都可以修改。</template>
    </p>
    <NButton v-if="missing" size="small" class="locate-missing" @click="locateReview">定位未补全项</NButton>
    <div v-if="pageCount > 1" class="pagination">
      <NButton size="small" :disabled="page <= 1" @click="page--">上一页条目</NButton>
      <NSelect v-model:value="page" :options="pageOptions" aria-label="校对页码" class="page-select" />
      <NButton size="small" :disabled="page >= pageCount" @click="page++">下一页条目</NButton>
    </div>
    <ul class="rows">
      <li v-for="(token, index) in shown" :key="token.tokenId" class="row" :data-token-id="token.tokenId" :class="{ 'needs-review': stateOf(token).missing, 'english-row': !isChinese }">
        <span v-if="isChinese" class="row-text"><span class="row-number">{{ offset + index + 1 }}.</span> {{ token.text }}</span>
        <NInput
          v-if="isChinese" class="row-pinyin" size="small"
          :value="pinyinValue(token)"
          :aria-label="`${token.text}的拼音`" placeholder="如 chūn tiān"
          @update:value="value => pinyinDrafts[token.tokenId] = value"
          @blur="commitPinyin(token)"
          @keydown.enter="commitPinyin(token)"
        />
        <template v-if="!isChinese">
          <span class="row-number english-number">{{ offset + index + 1 }}.</span>
          <NInput class="row-english" size="small" :value="englishDrafts[token.tokenId] ?? englishInputValue(token)"
            :aria-label="`${token.text}的英文`" placeholder="英文"
            @update:value="value => updateEnglishInput(token, value)" @blur="finishEnglishInput(token)" />
          <NInput class="row-meaning" size="small" :value="token.meaning ?? ''"
            :aria-label="`${token.text}的中文释义`" placeholder="中文"
            @update:value="value => emit('updateMeaning', token.tokenId, value)" />
        </template>
        <span v-if="invalidDrafts.some(item => item.tokenId === token.tokenId)" class="field-error">请补齐与汉字数量一致的音节</span>
        <NTag size="small" :type="stateOf(token).type" :bordered="false">{{ stateOf(token).label }}</NTag>
      </li>
    </ul>
    <p class="list-hint">当前显示第 {{ tokens.length ? offset + 1 : 0 }}–{{ offset + shown.length }} 项，共 {{ tokens.length }} 项。</p>
  </section>
</template>

<style scoped>
.entry-list { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.list-head h2 { margin: 0; font-size: 15px; }
.list-counts, .list-actions, .pagination { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.page-select { flex: 1; min-width: 150px; max-width: 240px; }
.list-hint { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.7; }
.rows { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; max-height: 520px; overflow: auto; }
.row { display: grid; grid-template-columns: minmax(64px, 1fr) minmax(0, 1.5fr) auto; gap: 8px; align-items: center; padding: 4px; }
.row.english-row { grid-template-columns: auto minmax(0, 1fr) minmax(0, 1fr) auto; }
.locate-missing { align-self: flex-start; }
.row-text { font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.field-error { color: #b42318; font-size: 12px; }
.row-number { font-size: 12px; font-weight: 400; color: var(--muted); }
.row.needs-review { background: var(--surface-2, #fff8e6); border-radius: 6px; }
@media (max-width: 720px) { .row { grid-template-columns: minmax(0, 1fr); } .row.english-row { grid-template-columns: auto minmax(0, 1fr); } .row.english-row .row-meaning, .row.english-row :deep(.n-tag) { grid-column: 2; } .pagination :deep(.n-button), .row :deep(.n-button) { min-height: 44px; } }
</style>
