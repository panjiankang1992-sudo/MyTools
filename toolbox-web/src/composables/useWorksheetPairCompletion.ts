import { getCurrentScope, nextTick, onScopeDispose, ref, toRaw, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue';
import type { EditableWorksheetPair } from '../domain/worksheet-editable-pairs';
export { clearStalePairCompletions } from '../domain/worksheet-editable-pairs';
import { suggestPinyin } from '../lib/pinyin-adapter';
import { completePinyinFromCurriculum } from '../lib/curriculum-pinyin';
import { translateEntries } from '../services/public-api';

/** 来源编辑器的补全依赖；教材、自定义和识别结果使用相同规则。 */
export interface WorksheetPairCompletionOptions {
  enabled?: MaybeRefOrGetter<boolean>;
  debounceMs?: number;
  translate?: typeof translateEntries;
  suggest?: typeof suggestPinyin;
}

/** 未导入的编辑行不能冒用词条标识；内部身份随对象展开保留，但不参与 JSON 保存。 */
const rowIdentity = Symbol('worksheet-completion-row');
let identitySequence = 0;
type Subject = 'chinese' | 'english';
type SourceField = 'text' | 'meaning' | 'english';
type TargetField = 'pinyin' | 'meaning' | 'english';
interface PendingPair { id: string; source: SourceField; target: TargetField; text: string }
type IdentifiedRow = EditableWorksheetPair & {
  [rowIdentity]?: string;
};

/** 空白只影响首尾判定，短语内部空格保持原样。 */
function cell(value: string | undefined): string { return (value ?? '').normalize('NFC').trim(); }

/** 为编辑行分配独立身份，重复词语也不能借用另一行的回包。 */
function identity(row: EditableWorksheetPair): string {
  if (row.tokenId) return `token:${row.tokenId}`;
  const raw = toRaw(row) as IdentifiedRow;
  raw[rowIdentity] ??= `client:${++identitySequence}`;
  return raw[rowIdentity];
}

/** 自动补齐空白配对栏；已输入内容、已删除行和过期来源始终优先于异步结果。 */
export function useWorksheetPairCompletion(
  subject: MaybeRefOrGetter<Subject>,
  rows: Ref<EditableWorksheetPair[]>,
  options: WorksheetPairCompletionOptions = {}
) {
  const translate = options.translate ?? translateEntries;
  const suggest = options.suggest ?? suggestPinyin;
  const busy = ref(false);
  const error = ref<string | null>(null);
  let timer: ReturnType<typeof setTimeout> | null = null;
  let controller: AbortController | null = null;
  let task: Promise<boolean> | null = null;
  let generation = 0;
  let disposed = false;
  let internalSignature: string | null = null;
  const enabled = (): boolean => !disposed && toValue(options.enabled ?? true);

  /** 快照包含两栏和身份，防止删除、复制或改写后仍接收旧翻译。 */
  function signature(current = rows.value): string {
    if (!enabled()) return JSON.stringify([toValue(subject), false]);
    return JSON.stringify([toValue(subject), enabled(), current.map(row => [identity(row), row.text, row.pinyin,
      row.meaning, row.english, row.pinyinState, row.meaningState, row.englishState, row.automaticInitialized])]);
  }

  /** 新建空行尚未输入内容时不占用首次机会，完整配对无需调用翻译。 */
  function hasContent(row: EditableWorksheetPair): boolean {
    return toValue(subject) === 'chinese' ? !!(cell(row.text) || cell(row.pinyin)) : !!(cell(row.meaning) || cell(row.english));
  }

  /** 自身状态更新不算人工编辑；通过父组件回填时等下一轮渲染。 */
  async function writeRows(updated: EditableWorksheetPair[]): Promise<void> {
    internalSignature = signature(updated);
    rows.value = updated;
    await nextTick();
  }

  /** 仅空白目标栏可补全；两个空栏不发请求，已有拼音和释义不覆盖。 */
  function pendingPairs(force = false): PendingPair[] {
    const chinese = toValue(subject) === 'chinese';
    return rows.value.flatMap<PendingPair>(row => {
      if (!force && row.automaticInitialized) return [];
      const id = identity(row);
      if (chinese) return cell(row.text) && !cell(row.pinyin)
        ? [{ id, source: 'text' as const, target: 'pinyin' as const, text: cell(row.text) }] : [];
      if (cell(row.meaning) && !cell(row.english)) return [{ id, source: 'meaning' as const, target: 'english' as const, text: cell(row.meaning) }];
      if (cell(row.english) && !cell(row.meaning)) return [{ id, source: 'english' as const, target: 'meaning' as const, text: cell(row.english) }];
      return [];
    });
  }

  /** 停止计时和在途请求；即便服务忽略取消信号，代次校验也会拒绝迟到数据。 */
  function cancel(): void {
    generation++;
    if (timer) clearTimeout(timer);
    timer = null;
    controller?.abort();
    controller = null;
    task = null;
    busy.value = false;
  }

  /** 按身份、输入快照及目标仍空三项条件写回，每次保留其它所有字段。 */
  async function merge(values: ReadonlyArray<{ item: PendingPair; value: string }>): Promise<void> {
    if (!values.length) return;
    const counts = new Map<string, number>();
    rows.value.forEach(row => counts.set(identity(row), (counts.get(identity(row)) ?? 0) + 1));
    let changed = false;
    const updated = rows.value.map(row => {
      const id = identity(row);
      const match = values.find(({ item }) => item.id === id && counts.get(id) === 1
        && cell(row[item.source]) === item.text && !cell(row[item.target]));
      if (!match || !cell(match.value)) return row;
      changed = true;
      const { target } = match.item;
      const provenance = row.automaticFields;
      return { ...row, [target]: cell(match.value), [`${target}State`]: 'suggested' as const,
        automaticFields: { ...provenance, [target]: {
          source: match.item.source, sourceText: match.item.text, value: cell(match.value)
        } } };
    });
    if (!changed) return;
    // 父组件通过 emit 更新时要等下一次渲染，避免下一批回写覆盖上一批结果。
    await writeRows(updated);
  }

  /** 仅首次自动处理；再次补空必须由用户显式触发，失败也不隐式重试。 */
  function completeNow(force = false): Promise<boolean> {
    if (timer) clearTimeout(timer);
    timer = null;
    if (!enabled()) return Promise.resolve(false);
    if (task) return task;
    const pending = pendingPairs(force);
    const fresh = rows.value.filter(row => !row.automaticInitialized && hasContent(row));
    if (!pending.length && !fresh.length) return Promise.resolve(true);
    error.value = null;
    const ticket = ++generation;
    const request = new AbortController();
    controller = request;
    busy.value = true;
    const active = (): boolean => enabled() && generation === ticket && !request.signal.aborted;
    const run = async (): Promise<boolean> => {
      // 在任何异步请求之前记住首次动作，修改、取消或返回不能再隐式触发。
      if (fresh.length) await writeRows(rows.value.map(row => !row.automaticInitialized && hasContent(row)
        ? { ...row, automaticInitialized: true } : row));
      if (!active()) return false;
      if (toValue(subject) === 'chinese') {
        const candidates = await completePinyinFromCurriculum(pending, request.signal, translate, suggest);
        if (!active()) return false;
        const values = pending.flatMap(item => {
          const result = candidates.find(value => value.id === item.id && value.sourceText === item.text)?.suggestion;
          if (!result) return [];
          // 缺音不能压缩音节后错位配对，保留空栏供人工补充。
          return result.unresolvedIndexes.length || !result.segments.length ? []
            : [{ item, value: result.segments.map(segment => segment.syllable).join(' ') }];
        });
        if (active()) await merge(values);
      } else {
        for (let start = 0; start < pending.length && active(); start += 20) {
          const batch = pending.slice(start, start + 20);
          try {
            const results = await translate(batch.map(item => ({ id: item.id, text: item.text })), request.signal);
            if (!active()) return false;
            const values = batch.flatMap(item => {
              const result = results.find(value => value.id === item.id && value.sourceText === item.text && value.status === 'translated');
              const value = item.target === 'english' ? result?.englishText : result?.meaning;
              return value?.trim() ? [{ item, value }] : [];
            });
            await merge(values);
          } catch {
            if (!active()) return false;
            // 服务不可用时保留当前内容，不继续排队，也不自动重试。
            break;
          }
        }
      }
      if (!active()) return false;
      const remaining = pendingPairs(true).length;
      error.value = remaining ? `还有 ${remaining} 项未能自动补全，可重试或手动填写。` : null;
      return true;
    };
    const aborted = new Promise<boolean>(resolve => request.signal.addEventListener('abort', () => resolve(false), { once: true }));
    task = Promise.race([run().catch(() => {
      if (active()) error.value = `还有 ${pendingPairs(true).length} 项未能自动补全，可重试或手动填写。`;
      return active();
    }), aborted]).finally(() => {
      if (generation !== ticket) return;
      busy.value = false;
      controller = null;
      task = null;
    });
    return task;
  }

  const stop = watch(() => signature(), value => {
    if (value === internalSignature) { internalSignature = null; return; }
    internalSignature = null;
    cancel();
    error.value = null;
    if (!enabled()) return;
    // 识别、教材或用户已有完整配对时立即保护，随后清空不应变成新的补全机会。
    const complete = (row: EditableWorksheetPair): boolean => toValue(subject) === 'chinese'
      ? !!(cell(row.text) && cell(row.pinyin)) : !!(cell(row.meaning) && cell(row.english));
    if (rows.value.some(row => !row.automaticInitialized && complete(row))) {
      void writeRows(rows.value.map(row => !row.automaticInitialized && complete(row) ? { ...row, automaticInitialized: true } : row));
    }
    timer = setTimeout(() => { timer = null; void completeNow(); }, options.debounceMs ?? 500);
  }, { immediate: true, flush: 'sync' });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; stop(); cancel(); });

  return { busy, error, completeNow, flush: () => completeNow(false), cancel };
}
