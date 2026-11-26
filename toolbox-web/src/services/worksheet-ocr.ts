import { IMAGE_LIMITS } from '../lib/image-preprocess';
import type { OcrVocabularyEntry } from '../domain/worksheet-ocr-pairs';

/**
 * 本机图片识别服务。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §4.1。
 *
 * 关键约束：
 * - **按需加载**：进入图片入口后才创建 worker 与下载模型，首屏不加载；
 * - 资源只从**同域**读取，生产环境不访问第三方 CDN；
 * - **一次最多一个识别任务**，多图按列表顺序串行；
 * - 资产加载 60 秒与识别 120 秒是**两个独立预算**；
 * - 开始识别时冻结 `jobId`/`imageId`/`inputRevision`，取消后迟到结果一律作废。
 *
 * worker 工厂与计时器都可注入，因此并发、超时与取消规则能在 Node 中验证，不需要真实 OCR 引擎。
 */

/** 识别语言。 */
export type OcrLanguage = 'chinese' | 'english';

/** 一次识别请求。 */
export interface OcrJobSpec {
  jobId: string;
  imageId: string;
  inputRevision: number;
  language: OcrLanguage;
  image: Blob;
}

/** 识别结果。 */
export interface OcrOutcome {
  jobId: string;
  imageId: string;
  inputRevision: number;
  /** 引擎原始全文，单独保留供用户核对。 */
  rawRecognizedText: string;
  /** 提取后的可编辑候选；未提供时使用原始全文。 */
  candidateText?: string;
  /** 图片词表中按位置对应的英文与中文，缺失释义保持空字符串。 */
  entries?: OcrVocabularyEntry[];
  /** 置信度；引擎未提供时为 null，不得显示成 100%。 */
  confidence: number | null;
  /** 本次识别耗时，用于记录真实设备性能。 */
  durationMs: number;
  /** 增强超时但已保留成功阶段的结果，界面需要提示核对。 */
  enhancementIncomplete?: boolean;
}

/** 引擎阶段结果，原始全文与词表候选始终分别存放。 */
export interface OcrWorkerResult {
  data: { text: string; candidateText?: string; confidence?: number; entries?: OcrVocabularyEntry[] };
}

/** 引擎 worker 的最小接口。 */
export interface OcrWorkerLike {
  recognize(image: Blob): Promise<OcrWorkerResult>;
  /** 仅识别超时时读取已经成功的阶段，取消仍一律丢弃。 */
  getFallbackResult?: () => OcrWorkerResult | null;
  terminate(): Promise<unknown> | unknown;
}

/** 创建 worker 的参数；路径全部指向同域资产。 */
export interface OcrWorkerOptions {
  workerPath: string;
  corePath: string;
  langPath: string;
  gzip: boolean;
  cacheMethod: 'none' | 'refresh' | 'write';
}

/** 服务依赖。 */
export interface OcrServiceDeps {
  /** 同域资产前缀，例如 `/ocr/<assetVersion>/`。 */
  assetBase: string;
  /** 创建 worker；由调用方注入真实 Tesseract 或测试桩。 */
  createWorker: (language: string, options: OcrWorkerOptions) => Promise<OcrWorkerLike>;
  assetTimeoutMs?: number;
  recognizeTimeoutMs?: number;
}

/** 识别失败。 */
export class OcrError extends Error {
  constructor(public readonly code: string, detail?: string) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = 'OcrError';
  }
}

/** 两种教材都支持中英混排，优先使用当前学科语言。 */
export function languagePacks(language: OcrLanguage): string[] {
  return language === 'chinese' ? ['chi_sim', 'eng'] : ['eng', 'chi_sim'];
}

/** 由同域前缀推导三个资产路径。 */
export function assetPaths(assetBase: string): OcrWorkerOptions {
  const base = assetBase.endsWith('/') ? assetBase : `${assetBase}/`;
  return {
    workerPath: `${base}worker.min.js`,
    // 只发布 LSTM 核心（simd 优先、非 simd 兜底），与 prepare-ocr-assets.mjs 的清单一致。
    corePath: base,
    langPath: base,
    gzip: true,
    // 语言包由本站提供并带版本目录，不需要引擎再往 IndexedDB 写缓存。
    cacheMethod: 'none'
  };
}

/** 独立预算同时监听取消；及时移除定时器和监听器。 */
async function withBudget<T>(task: Promise<T>, timeoutMs: number, code: string, signal: AbortSignal): Promise<T> {
  if (signal.aborted) throw new OcrError('WORKSHEET_OCR_CANCELLED');
  let timer: ReturnType<typeof setTimeout> | undefined;
  let onAbort: (() => void) | undefined;
  try {
    return await Promise.race([
      task,
      new Promise<never>((_resolve, reject) => {
        onAbort = () => reject(new OcrError('WORKSHEET_OCR_CANCELLED'));
        signal.addEventListener('abort', onAbort, { once: true });
        timer = setTimeout(() => reject(new OcrError(code, `timeout=${timeoutMs}ms`)), timeoutMs);
      })
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    if (onAbort) signal.removeEventListener('abort', onAbort);
  }
}

/** 终止资源时不以清理失败覆盖原始错误。 */
async function safelyTerminate(worker: OcrWorkerLike): Promise<void> {
  try { await worker.terminate(); } catch { /* 清理失败不影响原始结果。 */ }
}

/** 本机识别服务；任务取消会终止引擎，并让等待队列继续。 */
export class WorksheetOcrService {
  private worker: OcrWorkerLike | null = null;
  private workerLanguage: string | null = null;
  private tail: Promise<unknown> = Promise.resolve();
  private disposed = false;
  private jobs = new Map<string, AbortController>();
  private waiting: string[] = [];
  private listeners = new Set<(state: { active: string | null; waiting: string[]; pending: number }) => void>();
  private activeJobId: string | null = null;

  /** 注入 worker 工厂与独立超时预算。 */
  constructor(private readonly deps: OcrServiceDeps) {}

  /** 是否已经加载过 worker。 */
  get loaded(): boolean { return this.worker !== null; }

  /** 取得当前任务与未取消的等待队列。 */
  queueState(): { active: string | null; waiting: string[]; pending: number } {
    return { active: this.activeJobId, waiting: [...this.waiting], pending: this.waiting.length };
  }

  /** 订阅队列变化，返回取消订阅函数。 */
  onQueueChange(listener: (state: { active: string | null; waiting: string[]; pending: number }) => void): () => void {
    this.listeners.add(listener);
    listener(this.queueState());
    return () => this.listeners.delete(listener);
  }

  /** 通知队列订阅者。 */
  private notifyQueue(): void {
    const snapshot = this.queueState();
    for (const listener of this.listeners) listener(snapshot);
  }

  /** 确保引擎就绪；取消或超时后的晚到引擎必须立即释放。 */
  private async ensureWorker(language: OcrLanguage, signal: AbortSignal): Promise<OcrWorkerLike> {
    if (signal.aborted || this.disposed) throw new OcrError('WORKSHEET_OCR_CANCELLED');
    const packs = languagePacks(language).join('+');
    if (this.worker && this.workerLanguage === packs) return this.worker;
    await this.terminateWorker();
    if (signal.aborted || this.disposed) throw new OcrError('WORKSHEET_OCR_CANCELLED');
    let abandoned = false;
    const created = this.deps.createWorker(packs, assetPaths(this.deps.assetBase)).then(worker => {
      if (abandoned || signal.aborted || this.disposed) {
        void safelyTerminate(worker);
        throw new OcrError('WORKSHEET_OCR_CANCELLED');
      }
      return worker;
    });
    try {
      const worker = await withBudget(created, this.deps.assetTimeoutMs ?? IMAGE_LIMITS.assetTimeoutMs, 'WORKSHEET_OCR_ASSET_TIMEOUT', signal);
      if (signal.aborted || this.disposed) {
        void safelyTerminate(worker);
        throw new OcrError('WORKSHEET_OCR_CANCELLED');
      }
      this.worker = worker;
      this.workerLanguage = packs;
      return worker;
    } catch (error) {
      abandoned = true;
      throw error;
    }
  }

  /** 提交一次识别；相同任务标识不能同时入队。 */
  async recognize(spec: OcrJobSpec): Promise<OcrOutcome> {
    if (this.disposed) throw new OcrError('WORKSHEET_OCR_DISPOSED');
    if (this.jobs.has(spec.jobId)) throw new OcrError('WORKSHEET_OCR_DUPLICATE_JOB');
    const controller = new AbortController();
    this.jobs.set(spec.jobId, controller);
    this.waiting.push(spec.jobId);
    this.notifyQueue();
    const run = this.tail.then(() => this.runJob(spec, controller.signal));
    // 单项失败不阻塞下一项，结束后及时清理取消控制器。
    this.tail = run.catch(() => undefined);
    try { return await run; } finally { this.jobs.delete(spec.jobId); }
  }

  /** 串行运行任务，在任何终态释放活动槽位。 */
  private async runJob(spec: OcrJobSpec, signal: AbortSignal): Promise<OcrOutcome> {
    this.waiting = this.waiting.filter(id => id !== spec.jobId);
    if (signal.aborted) {
      this.notifyQueue();
      throw new OcrError('WORKSHEET_OCR_CANCELLED', spec.jobId);
    }
    this.activeJobId = spec.jobId;
    this.notifyQueue();
    const started = Date.now();
    try {
      const worker = await this.ensureWorker(spec.language, signal);
      if (signal.aborted) throw new OcrError('WORKSHEET_OCR_CANCELLED');
      const result = await withBudget(worker.recognize(spec.image), this.deps.recognizeTimeoutMs ?? IMAGE_LIMITS.recognizeTimeoutMs, 'WORKSHEET_OCR_RECOGNIZE_TIMEOUT', signal);
      if (signal.aborted || this.disposed) throw new OcrError('WORKSHEET_OCR_CANCELLED');
      return {
        jobId: spec.jobId, imageId: spec.imageId, inputRevision: spec.inputRevision,
        rawRecognizedText: result.data.text ?? '',
        candidateText: result.data.candidateText,
        entries: result.data.entries,
        confidence: typeof result.data.confidence === 'number' ? result.data.confidence : null,
        durationMs: Date.now() - started
      };
    } catch (error) {
      const timedOut = error instanceof OcrError && error.code === 'WORKSHEET_OCR_RECOGNIZE_TIMEOUT';
      // 终止之前复制成功阶段；用户取消与资产超时都不得回传旧结果。
      const fallback = timedOut && !signal.aborted && !this.disposed ? this.worker?.getFallbackResult?.() : null;
      if (signal.aborted || timedOut) {
        await this.terminateWorker();
      }
      if (fallback && !signal.aborted && !this.disposed) {
        return {
          jobId: spec.jobId, imageId: spec.imageId, inputRevision: spec.inputRevision,
          rawRecognizedText: fallback.data.text ?? '', candidateText: fallback.data.candidateText,
          entries: fallback.data.entries,
          confidence: typeof fallback.data.confidence === 'number' ? fallback.data.confidence : null,
          durationMs: Date.now() - started, enhancementIncomplete: true
        };
      }
      throw error;
    } finally {
      if (this.activeJobId === spec.jobId) this.activeJobId = null;
      this.notifyQueue();
    }
  }

  /** 取消指定任务；活动引擎立即终止，等待任务不会创建引擎。 */
  cancel(jobId: string): void {
    this.jobs.get(jobId)?.abort();
    this.waiting = this.waiting.filter(id => id !== jobId);
    if (this.activeJobId === jobId) void this.terminateWorker();
    this.notifyQueue();
  }

  /** 取消当前和所有后续任务，清空可见等待队列。 */
  cancelAll(): void {
    for (const controller of this.jobs.values()) controller.abort();
    this.waiting = [];
    void this.terminateWorker();
    this.notifyQueue();
  }

  /** 分离后释放引擎，避免晚到清理误终止下一任务的引擎。 */
  private async terminateWorker(): Promise<void> {
    const worker = this.worker;
    this.worker = null;
    this.workerLanguage = null;
    if (worker) await safelyTerminate(worker);
  }

  /** 取消全部任务并释放资源；释放后拒绝新任务。 */
  async dispose(): Promise<void> {
    this.disposed = true;
    this.cancelAll();
    this.listeners.clear();
    await this.terminateWorker();
  }
}
