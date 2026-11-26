import { computed, nextTick, ref, watch } from 'vue';
import type { InputToken } from '../domain/worksheet-input';
import { requirementFor, type WorksheetOutputKind } from '../domain/worksheet-processing';

/** 只阻断需要已确认拼音的输出，不把答案要求施加到学生卷。 */
export function pinyinEditingBlocksOutput(mode: string | null, kind: WorksheetOutputKind, pending: boolean): boolean {
  return pending && mode !== null && requirementFor(mode, kind).needsConfirmedPinyin;
}

/** 多字词读音编辑缓冲：允许逐键输入，失焦提交后保留错误输入供修正。 */
export function useWorksheetPinyinEditor(tokens: () => readonly InputToken[], submit: (tokenId: string, value: string) => void) {
  const drafts = ref<Record<string, string>>({});
  const pending = computed(() => tokens().some(token => {
    const draft = drafts.value[token.tokenId];
    return draft !== undefined && (token.pinyinState !== 'confirmed' || draft.trim().replace(/\s+/gu, ' ') !== token.pinyinSegments.map(segment => segment.syllable).join(' '));
  }));
  const invalid = computed(() => tokens().filter(token => {
    const draft = drafts.value[token.tokenId];
    if (draft === undefined) return false;
    const syllables = draft.trim() ? draft.trim().split(/\s+/u) : [];
    const count = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(token.text)].length;
    return syllables.length !== count;
  }));
  watch(tokens, current => {
    const ids = new Set(current.map(token => token.tokenId));
    for (const id of Object.keys(drafts.value)) {
      if (!ids.has(id)) delete drafts.value[id];
    }
  });

  /** 模型尚未接受完整读音时，输入框继续展示用户输入。 */
  function valueOf(token: InputToken): string {
    return drafts.value[token.tokenId] ?? token.pinyinSegments.map(segment => segment.syllable).join(' ');
  }

  /** 失焦提交；仅清除模型已确认接受的缓冲，不吞掉错误内容。 */
  async function commit(token: InputToken): Promise<void> {
    const value = drafts.value[token.tokenId];
    if (value === undefined) return;
    submit(token.tokenId, value);
    await nextTick();
    const updated = tokens().find(item => item.tokenId === token.tokenId);
    if (updated?.pinyinState === 'confirmed' && updated.pinyinSegments.map(segment => segment.syllable).join(' ') === value.trim().replace(/\s+/gu, ' ')) {
      delete drafts.value[token.tokenId];
    }
  }

  return { drafts, invalid, pending, valueOf, commit };
}
