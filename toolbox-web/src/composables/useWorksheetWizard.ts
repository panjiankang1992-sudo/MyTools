import { ref } from 'vue';
import { serializeTokens, tokenize, type ParsePolicy } from '../domain/worksheet-input';

/** 三步独立页面及三种输入来源。 */
export type WorksheetWizardStage = 'source' | 'confirm' | 'preview';
export type WorksheetSourceChoice = 'textbook' | 'custom' | 'image';

/** 保存界面导航与已应用候选身份，不复制原文或会话业务状态。 */
export function useWorksheetWizard() {
  const stage = ref<WorksheetWizardStage>('source');
  const source = ref<WorksheetSourceChoice | null>(null);
  const appliedCandidates = new Map<WorksheetSourceChoice, string>();

  /** 改选来源只切换输入界面，不清空已确认的数据。 */
  function chooseSource(value: WorksheetSourceChoice): void { source.value = value; stage.value = 'source'; }

  /** 只有有效且非空的原文可以进入确认，失败时停留原步骤。 */
  function toConfirm(validInput: boolean): boolean {
    if (!validInput) return false;
    stage.value = 'confirm';
    return true;
  }

  /** 预览只接纳生成成功的当前产物，禁止跳过确认与生成。 */
  function toPreview(currentArtifact: boolean): boolean {
    if (stage.value !== 'confirm' || !currentArtifact) return false;
    stage.value = 'preview';
    return true;
  }

  /** 步骤栏仅可后退，不能绕过校验跳到未来步骤。 */
  function backTo(target: 'source' | 'confirm'): boolean {
    if (target === 'confirm' && stage.value !== 'preview') return false;
    stage.value = target;
    return true;
  }

  /** 候选已经应用时，返回来源后点继续不会再次追加同一批内容。 */
  function wasApplied(choice: WorksheetSourceChoice, signature: string): boolean {
    return appliedCandidates.get(choice) === signature;
  }

  /** 只在原文更新成功后记录候选身份。 */
  function markApplied(choice: WorksheetSourceChoice, signature: string): void { appliedCandidates.set(choice, signature); }

  /** 清空内容或恢复另一份草稿时，旧候选标记不再代表新原文。 */
  function resetApplied(): void { appliedCandidates.clear(); }

  return { stage, source, chooseSource, toConfirm, toPreview, backTo, wasApplied, markApplied, resetApplied };
}


/** 来源确认前先解析候选；追加时沿用现有原文规则，避免英文短语被拆开。 */
export function prepareWorksheetSourceText(rawText: string, currentPolicy: ParsePolicy, incoming: string, incomingPolicy: ParsePolicy, mode: 'append' | 'replace') {
  const parsed = tokenize(incoming, incomingPolicy);
  if (parsed.blocked || !parsed.spans.length) return { ok: false as const, rawText, parsePolicy: currentPolicy };
  if (mode === 'replace' || !rawText.trim()) return { ok: true as const, rawText: incoming, parsePolicy: incomingPolicy };
  const addition = serializeTokens(parsed.spans.map(span => span.text), currentPolicy);
  return { ok: true as const, rawText: `${rawText}${currentPolicy === 'one-per-line' ? '\n' : ' '}${addition}`, parsePolicy: currentPolicy };
}
