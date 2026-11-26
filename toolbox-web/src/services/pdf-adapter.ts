import { appConfig, type PdfExecution } from '../config/env';
import { PDF_OPERATIONS, findTool } from '../config/tools';
import { PDF_LIMITS, runPdfTool, type PdfOutput, type PdfToolId, type PdfToolOptions } from '../lib/pdf-tools';
import { PublicApiError, publicRequest } from './public-api';

/**
 * PDF 执行适配层。
 * 执行位置始终由界面明示；本机适配器不因失败而静默上传文件。
 */

/** 处理位置。 */
export type PdfExecutionLocation = PdfExecution;

/** 公开能力描述。 */
export interface PdfToolCapability {
  toolId: string;
  operation: string;
  execution: PdfExecutionLocation;
  limits: Record<string, number>;
}

/** 一次处理请求。 */
export interface PdfExecutionRequest {
  toolId: string;
  options: PdfToolOptions;
}

/** 处理阶段，仅上传阶段提供可测量百分比。 */
export interface PdfExecutionProgress {
  phase: 'validating' | 'processing' | 'rendering' | 'done';
  percent?: number;
}

/** 处理结果。 */
export interface PdfExecutionResult {
  files: PdfOutput[];
  text?: string;
  execution: PdfExecutionLocation;
}

/** 适配器接口。 */
export interface PdfAdapter {
  readonly location: PdfExecutionLocation;
  execute(request: PdfExecutionRequest, onProgress?: (progress: PdfExecutionProgress) => void): Promise<PdfExecutionResult>;
  cancel(): void;
}

/** 由工具标识推导内部工具分类。 */
export function pdfToolSuffix(toolId: string): PdfToolId | null {
  const suffix = toolId.startsWith('pdf.') ? toolId.slice(4) : toolId;
  return (['images', 'text', 'markdown', 'merge', 'split', 'crop', 'render', 'extract'] as const).includes(suffix as PdfToolId)
    ? suffix as PdfToolId
    : null;
}

/** 由内部工具分类推导注册表工具标识。 */
export function pdfToolId(suffix: PdfToolId): string {
  return `pdf.${suffix}`;
}

/** 返回本机处理能力与限制，数值与服务端能力接口保持同规则。 */
export function localCapabilities(): PdfToolCapability[] {
  return Object.entries(PDF_OPERATIONS).map(([toolId, operation]) => ({
    toolId,
    operation,
    execution: 'local' as const,
    limits: { ...PDF_LIMITS }
  }));
}

/** 本机适配器：直接调用现有浏览器处理实现。 */
class LocalPdfAdapter implements PdfAdapter {
  readonly location = 'local' as const;
  private controller = new AbortController();

  /** 在本机线程内完成处理；不产生任何网络上传。 */
  async execute(request: PdfExecutionRequest, onProgress?: (progress: PdfExecutionProgress) => void): Promise<PdfExecutionResult> {
    // 每次执行使用新的取消信号，使执行前的 cancel 成为无副作用的空操作。
    this.controller = new AbortController();
    onProgress?.({ phase: 'validating' });
    if (this.controller.signal.aborted) throw new Error('PDF_CANCELLED');
    onProgress?.({ phase: 'processing' });
    const result = await runPdfTool({ ...request.options, tool: pdfToolSuffix(request.toolId) ?? request.options.tool });
    onProgress?.({ phase: 'rendering' });
    if (this.controller.signal.aborted) throw new Error('PDF_CANCELLED');
    return { files: result.files, text: result.text, execution: 'local' };
  }

  /** 取消正在进行的本机处理；结果会被丢弃而不是作为成功产物返回。 */
  cancel(): void {
    this.controller.abort();
  }
}

/** 服务端适配器：按公开契约提交任务，接口不可用时明确失败而不回退上传。 */
class ServerPdfAdapter implements PdfAdapter {
  readonly location = 'server' as const;
  private taskId: string | null = null;

  /** 提交冻结参数到公开门面，轮询到终态后返回产物。 */
  async execute(request: PdfExecutionRequest, onProgress?: (progress: PdfExecutionProgress) => void): Promise<PdfExecutionResult> {
    const tool = findTool(request.toolId);
    if (!tool) throw new Error('UNKNOWN_TOOL');
    onProgress?.({ phase: 'validating' });
    const created = await publicRequest<{ taskId: string }>('/pdf/tasks', {
      method: 'POST',
      csrf: true,
      body: JSON.stringify({
        toolId: request.toolId,
        outputName: request.options.files[0]?.name ?? 'output.pdf',
        clientRevision: 1,
        options: buildServerOptions(request.toolId, request.options)
      })
    });
    this.taskId = created.taskId;
    try {
      onProgress?.({ phase: 'processing' });
      const task = await pollTask(created.taskId);
      const files: PdfOutput[] = [];
      for (const [index, artifact] of task.artifacts.entries()) {
        const blob = await downloadArtifact(created.taskId, index);
        files.push({ name: artifact.name, blob, pages: artifact.pageCount });
      }
      onProgress?.({ phase: 'done' });
      return { files, text: task.text, execution: 'server' };
    } finally {
      this.taskId = null;
    }
  }

  /** 请求取消当前任务；取消确认前界面不得显示已取消。 */
  cancel(): void {
    if (!this.taskId) return;
    const id = this.taskId;
    void publicRequest(`/pdf/tasks/${encodeURIComponent(id)}/cancel`, { method: 'POST', csrf: true }).catch(() => undefined);
  }
}

interface ServerTaskState {
  status: string;
  artifacts: Array<{ name: string; pageCount: number }>;
  text?: string;
}

/** 轮询任务直到终态，采用退避避免高频请求。 */
async function pollTask(taskId: string): Promise<ServerTaskState> {
  let delay = 800;
  for (let attempt = 0; attempt < 150; attempt += 1) {
    const task = await publicRequest<ServerTaskState>(`/pdf/tasks/${encodeURIComponent(taskId)}`);
    if (['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'EXPIRED'].includes(task.status)) {
      if (task.status !== 'SUCCEEDED') throw new PublicApiError('TOOLBOX_012', `task.${task.status.toLowerCase()}`, undefined, undefined, 200);
      return task;
    }
    await new Promise(resolve => setTimeout(resolve, delay));
    delay = Math.min(delay * 1.5, 4000);
  }
  throw new PublicApiError('TOOLBOX_008', 'task.pollingTimeout', undefined, undefined, 0);
}

/** 下载单个授权产物。 */
async function downloadArtifact(taskId: string, index: number): Promise<Blob> {
  const response = await fetch(`${appConfig.apiBase}/pdf/tasks/${encodeURIComponent(taskId)}/artifacts/${index}/content`, { credentials: 'same-origin' });
  if (!response.ok) throw new PublicApiError(response.status === 410 ? 'TOOLBOX_007' : 'TOOLBOX_012', 'artifact.unavailable', undefined, undefined, response.status);
  return await response.blob();
}

/** 按契约白名单构造服务端参数，不把本机选项原样塞入。 */
function buildServerOptions(toolId: string, options: PdfToolOptions): Record<string, unknown> {
  const operation = PDF_OPERATIONS[toolId];
  switch (operation) {
    case 'IMAGES_TO_PDF':
      return { paperSize: options.paper.toUpperCase(), orientation: options.landscape ? 'LANDSCAPE' : 'PORTRAIT', fit: 'FIT', marginMm: options.margin };
    case 'TEXT_TO_PDF':
    case 'MARKDOWN_TO_PDF':
      return { paperSize: options.paper.toUpperCase(), pageNumbers: true, fontPreset: 'CJK_SANS', marginMm: options.margin };
    case 'PDF_SPLIT':
      return { ranges: options.pages.trim() ? options.pages.trim() : null, pagesPerFile: options.chunkSize };
    case 'PDF_PAGE_CUT':
      return { axis: options.direction === 'vertical' ? 'VERTICAL' : 'HORIZONTAL', position: options.ratio, reverse: options.reverse, ranges: null };
    case 'PDF_TO_IMAGES':
      return { dpi: options.dpi, format: options.imageFormat.toUpperCase(), ranges: null };
    case 'PDF_TO_TEXT':
      return { ranges: null };
    default:
      return {};
  }
}

/** 按配置创建适配器；服务端模式在接口不可用时明确报错。 */
export function createPdfAdapter(location: PdfExecutionLocation = appConfig.pdfExecution): PdfAdapter {
  return location === 'server' ? new ServerPdfAdapter() : new LocalPdfAdapter();
}
