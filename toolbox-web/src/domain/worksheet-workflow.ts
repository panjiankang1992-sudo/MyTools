/**
 * 字帖工作流状态机。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §5.1。
 *
 * ```text
 * INPUT -> PROCESS -> GENERATING -> PREVIEW -> OUTPUT
 *   ^          |                       |
 *   |          +------ EDIT -----------+
 *   +-------------- ADJUST ------------+
 * ```
 *
 * 本模块只做状态转移，不接触 DOM、网络与渲染，便于用夹具验证异步与失效规则。
 */

/** 当前步骤。 */
export type WorkflowStep = 'input' | 'process' | 'generating' | 'preview' | 'output';

/** 产物与当前草稿的关系。 */
export type ArtifactStatus = 'none' | 'current' | 'stale';

/** 产物种类。 */
export type ArtifactKind = 'student' | 'answer';

/** 已生成的产物。 */
export interface WorkflowArtifact {
  status: ArtifactStatus;
  /** 生成该产物的代次，用于丢弃迟到结果。 */
  generationId: number;
  kind: ArtifactKind;
  /** 冻结内容快照的哈希，用于判断产物是否仍对应当前草稿。 */
  snapshotHash: string;
  pageCount: number;
  sha256: string;
  /** 会话有效内容与版式指纹；由会话层生成并在输出前核对。 */
  sessionFingerprint?: string;
}

/** 产物对应的三类修订号。 */
export interface GeneratedRevisions {
  input: number;
  processing: number;
  render: number;
}

/** 工作流状态。 */
export interface WorkflowState {
  step: WorkflowStep;
  /** 原文修订号：任何影响原文的改动递增。 */
  inputRevision: number;
  /** 处理修订号：题型、重复、选题等递增。 */
  processingRevision: number;
  /** 版式修订号：标题、密度、格距、间距递增。 */
  renderRevision: number;
  /** 生成代次：每次发起生成递增，用于丢弃迟到结果。 */
  generationId: number;
  /** 操作身份：内容确实改变并有效生成后才递增，纯版式变化不变。 */
  operationId: number;
  artifact: WorkflowArtifact | null;
  /** 是否有生成任务在跑；清空必须复位。 */
  busy: boolean;
  /** 最近一次生成失败的原因；成功后清空。 */
  lastError: string | null;
  /**
   * 上次成功生成时的内容修订（原文 + 处理）。
   * 操作身份只跟内容有关：纯版式变化不产生新操作，因此不能用「产物是否过期」来推断内容是否变化。
   */
  generatedContent: { input: number; processing: number } | null;
  /** 输出入口核对的产物版本，不能只信 current 标志。 */
  generatedRevisions: GeneratedRevisions | null;
}

/** 创建初始状态：停在输入步骤，没有产物。 */
export function createWorkflow(): WorkflowState {
  return {
    step: 'input',
    inputRevision: 0,
    processingRevision: 0,
    renderRevision: 0,
    generationId: 0,
    operationId: 0,
    artifact: null,
    busy: false,
    lastError: null,
    generatedContent: null,
    generatedRevisions: null
  };
}

/** 产物是否可以直接输出；过期或不存在时必须禁用输出。 */
export function canOutput(state: WorkflowState): boolean {
  const revisions = state.generatedRevisions;
  return !state.busy && state.artifact !== null && state.artifact.status === 'current'
    && revisions !== null && revisions.input === state.inputRevision
    && revisions.processing === state.processingRevision && revisions.render === state.renderRevision;
}

/** 把已有产物标记为过期，但不删除它。 */
function stale(state: WorkflowState): WorkflowArtifact | null {
  if (!state.artifact) return null;
  return state.artifact.status === 'current' ? { ...state.artifact, status: 'stale' } : state.artifact;
}

/**
 * 原文变化。
 *
 * 用户改了文本就重新解析；如果解析结果与原来语义等价（例如只改了空白），
 * 已有产物内容仍然有效，可以继续沿用，只是绑定到新的原文修订号。
 *
 * @param state 当前状态
 * @param equivalent 改动后解析结果是否与原来等价
 * @returns 新状态
 */
export function onInputChanged(state: WorkflowState, equivalent = false): WorkflowState {
  // 只有当前有效产物才允许把等价原文绑定到新版本，不能复活本已过期的产物。
  const rebind = equivalent && canOutput(state);
  const inputRevision = state.inputRevision + 1;
  return {
    ...state,
    inputRevision,
    artifact: equivalent ? state.artifact : stale(state),
    generatedRevisions: rebind && state.generatedRevisions ? { ...state.generatedRevisions, input: inputRevision } : state.generatedRevisions,
    generatedContent: rebind && state.generatedContent ? { ...state.generatedContent, input: inputRevision } : state.generatedContent,
    lastError: null
  };
}

/**
 * 处理参数变化（题型、重复、实际选题顺序、逐字策略等）。
 * 旧产物立即过期；题型变化不会自动重新 OCR 或重新选教材。
 *
 * @param state 当前状态
 * @returns 新状态
 */
export function onProcessingChanged(state: WorkflowState): WorkflowState {
  return {
    ...state,
    processingRevision: state.processingRevision + 1,
    artifact: stale(state),
    lastError: null
  };
}

/**
 * 纯版式变化（标题、密度、格距、间距）。原文不变，操作身份不变，只重新排版。
 *
 * @param state 当前状态
 * @returns 新状态
 */
export function onLayoutChanged(state: WorkflowState): WorkflowState {
  return { ...state, renderRevision: state.renderRevision + 1, artifact: stale(state) };
}

/** 进入处理步骤。 */
export function goToProcess(state: WorkflowState): WorkflowState {
  return { ...state, step: 'process' };
}

/**
 * 从预览回到编辑。
 *
 * 契约 §5.1：未改变任何值时原文完整保留、旧产物可继续沿用，
 * 因此这里既不递增修订号，也不把产物标记过期。
 *
 * @param state 当前状态
 * @returns 新状态
 */
export function returnToEdit(state: WorkflowState): WorkflowState {
  return { ...state, step: 'input' };
}

/**
 * 发起一次生成，返回新状态与本次代次。
 * 调用方必须把代次回传给 `completeGeneration`，迟到结果会被丢弃。
 *
 * @param state 当前状态
 * @returns 新状态与本次生成代次
 */
export function beginGeneration(state: WorkflowState): { state: WorkflowState; generationId: number } {
  const generationId = state.generationId + 1;
  return { state: { ...state, generationId, busy: true, step: 'generating', lastError: null }, generationId };
}

/**
 * 生成成功。只有当前代次的结果才被采纳，迟到结果直接忽略。
 *
 * @param state 当前状态
 * @param generationId 结果所属代次
 * @param artifact 产物描述（不含 status）
 * @returns 新状态
 */
export function completeGeneration(
  state: WorkflowState,
  generationId: number,
  artifact: Omit<WorkflowArtifact, 'status'>
): WorkflowState {
  // 迟到结果：期间又发起过生成，或已被清空，一律丢弃。
  if (generationId !== state.generationId) return state;
  // 内容（原文或处理参数）确实改变并有效生成时才产生新的操作身份；纯版式变化不新建操作。
  const contentChanged = state.generatedContent === null
    || state.generatedContent.input !== state.inputRevision
    || state.generatedContent.processing !== state.processingRevision;
  return {
    ...state,
    busy: false,
    step: 'preview',
    operationId: contentChanged ? state.operationId + 1 : state.operationId,
    artifact: { ...artifact, generationId, status: 'current' },
    lastError: null,
    generatedContent: { input: state.inputRevision, processing: state.processingRevision },
    generatedRevisions: { input: state.inputRevision, processing: state.processingRevision, render: state.renderRevision }
  };
}

/**
 * 生成失败：保留原文与校对结果，不回到旧 PDF 伪装成功。
 *
 * @param state 当前状态
 * @param generationId 失败所属代次
 * @param error 错误标识
 * @returns 新状态
 */
export function failGeneration(state: WorkflowState, generationId: number, error: string): WorkflowState {
  if (generationId !== state.generationId) return state;
  return { ...state, busy: false, step: 'process', lastError: error };
}

/**
 * 清空：取消任务、清空产物、退出 busy，不产生成功事件，也不更换操作身份。
 *
 * @param state 当前状态
 * @returns 新状态
 */
export function clearWorkflow(state: WorkflowState): WorkflowState {
  return {
    ...state,
    step: 'input',
    // 递增代次让在途生成结果失效；否则迟到结果会把已清空的产物复活。
    generationId: state.generationId + 1,
    busy: false,
    artifact: null,
    lastError: null,
    generatedContent: null,
    generatedRevisions: null
  };
}

/** 切换到输出步骤；产物不是 current 时不允许进入。 */
export function goToOutput(state: WorkflowState): WorkflowState {
  if (!canOutput(state)) return state;
  return { ...state, step: 'output' };
}

/** 是否有任何未决的生成任务。 */
export function isBusy(state: WorkflowState): boolean {
  return state.busy;
}
