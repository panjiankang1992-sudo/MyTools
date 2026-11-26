import { computed, ref } from 'vue';
import { createSession, importTextbook, processSession, type WorksheetSession } from '../domain/worksheet-session';
import { generateWorksheet } from '../lib/worksheet';
import { releasePdf, renderPdfPage } from '../lib/pdf-runtime';
import { openPrintPreview, type PrintWindowLike } from '../lib/worksheet-print';
import {
  previewScopeFailureMessage, previewScopeQuery, type PreviewScopeNode
} from '../lib/admin-preview-scope';
import { adminErrorMessage } from '../lib/admin-error-messages';
import type { WorksheetArtifact, WorksheetMode, WordEntry } from '../types';
import { AdminApiError, adminRequest } from '../services/admin-api';

/**
 * 管理端草稿预览（设计文档 §3.6）。
 *
 * 关键要求：预览必须**复用已发布的渲染管线**，而不是另画一套近似的预览。
 * 因此这里走的是与公共字帖完全相同的路径：
 * 建会话 → 导入教材条目 → 处理（确认/去重/选题/排序）→ 生成 PDF → 逐页渲染。
 *
 * 与公共流程的唯一区别：数据来自**管理端草稿**接口而不是公开目录，
 * 且**只读**——不写草稿、不产生发布。
 */

/** 预览载荷中的条目形状与公开目录一致，因此可以直接喂给既有导入逻辑。 */
export interface DraftPreviewPayload {
  edition: { editionId: string; series?: string; contentVersion?: string };
  units: Array<{ unitId: string; title: string }>;
  lessons: Array<{ lessonId: string; title: string; unitId: string | null }>;
  entries: Parameters<typeof importTextbook>[1]['entries'];
  draftPreview: boolean;
}

/**
 * @param options.subject 取当前学科的**函数**（F10）
 * @param options.mode    取当前题型的**函数**
 *
 * 为什么是函数而不是值：学科与题型在编辑器里是响应式的，
 * 而组合式在 `setup` 期只调用一次。传值会把**第一次**的学科永久固定下来——
 * 英语教材的预览会一直走语文链路。
 */
export const useAdminDraftPreview = (options: {
  mode: () => WorksheetMode;
  subject: () => 'chinese' | 'english';
}) => {
  const artifact = ref<WorksheetArtifact | null>(null);
  const page = ref(1);
  const zoom = ref<'fit' | 'actual'>('fit');
  const busy = ref(false);
  const error = ref('');
  /**
   * 本次预览**声称基于**的草稿修订号（由调用方在保存成功后传入）。
   *
   * 为什么要有它：预览接口读的是"服务器此刻的草稿"，而界面上的内容可能还没保存
   * （自动保存有 2 秒防抖）。调用方先 flush 保存、拿到新修订号再传入，
   * 界面上就能明确写出"本次预览基于修订 N"——而不是让用户以为看到的是当前编辑内容。
   */
  const revision = ref<number | null>(null);
  /** 本次预览的范围说明（整册／单元／课文），用于界面标注。 */
  const scope = ref('');

  const pageCount = computed(() => artifact.value?.pageCount ?? 0);

  /** 释放上一次产物，避免 PDF 对象与 blob URL 泄漏。 */
  function release(): void {
    if (artifact.value) {
      releasePdf(artifact.value.bytes);
      URL.revokeObjectURL(artifact.value.url);
      artifact.value = null;
    }
  }

  /** 把处理后的题序转成渲染器输入，与公共流程使用同一映射。 */
  function toWordEntries(items: ReturnType<typeof processSession>['items']): WordEntry[] {
    return items.map((token, index) => ({
      id: token.tokenId,
      text: token.text,
      pinyin: token.pinyinSegments.map(segment => segment.syllable).join(' '),
      meaning: token.meaning ?? '',
      unit: index + 1,
      kind: token.text.length > 1 ? 'word' : 'character'
    }));
  }

  /**
   * 生成草稿预览。
   *
   * **调用方必须先保存**：本函数只读服务器上**已保存**的草稿，
   * 因此编辑器在调用前要 flush 防抖中的自动保存并确认成功。
   *
   * 请求参数（设计 164 行「使用当前草稿的已选范围」）：
   * - `nodeKind`／`nodeKey` 来自 `snapshot.scopeNode`；整册时**两个都不传**（后端缺省即整册）；
   * - `draftRevision` 传**flush 之后**的最新修订号：服务端据此核对，
   *   不一致时返回 409——而不是让界面把"别人刚改过的草稿"标注成自己保存的那一版。
   *
   * @param editionId 教材标识
   * @param snapshot  本次预览所基于的修订号、范围说明与范围节点
   * @returns 是否成功产出
   */
  async function generate(
    editionId: string,
    snapshot: { revision?: number; scope?: string; scopeNode?: PreviewScopeNode } = {}
  ): Promise<boolean> {
    busy.value = true;
    error.value = '';
    revision.value = snapshot.revision ?? null;
    scope.value = snapshot.scope ?? '';
    release();
    try {
      const query = new URLSearchParams();
      const scoped = snapshot.scopeNode ? previewScopeQuery(snapshot.scopeNode) : {};
      if (scoped.nodeKind) {
        query.set('nodeKind', scoped.nodeKind);
        query.set('nodeKey', scoped.nodeKey ?? '');
      }
      if (snapshot.revision !== undefined && snapshot.revision !== null) {
        query.set('draftRevision', String(snapshot.revision));
      }
      const search = query.toString();
      const payload = await adminRequest<DraftPreviewPayload>(
        `/textbooks/${encodeURIComponent(editionId)}/draft/preview${search ? `?${search}` : ''}`
      );
      if (!payload.entries.length) {
        error.value = '草稿还没有内容，先录入再预览。';
        return false;
      }
      // 与公共流程同构：建会话 → 导入条目 → 处理 → 渲染。
      let session: WorksheetSession = createSession(options.subject(), { gridMm: 12, title: '' });
      const imported = importTextbook(session, {
        entries: payload.entries,
        editionId: payload.edition.editionId,
        contentVersion: payload.edition.contentVersion ?? 'draft',
        label: `${payload.edition.series ?? payload.edition.editionId} · 草稿预览`,
        mode: 'replace'
      });
      session = imported.session;
      const processed = processSession(session);
      if (!processed.items.length) {
        error.value = '草稿内容无法生成练习题，请检查条目。';
        return false;
      }
      const produced = await generateWorksheet(toWordEntries(processed.items), {
        mode: options.mode(),
        title: session.layout.title,
        grade: 1,
        gridMm: session.layout.gridMm,
        repetitions: 1,
        expandedQuestions: true,
        answer: false
      });
      artifact.value = produced;
      page.value = 1;
      return true;
    } catch (cause) {
      // 管理接口错误翻成人的话：范围不存在的 404 要**说清楚是哪个范围不见了**，
      // 而不是只显示一句"目标不存在"（那会被误读成整册预览失败）。
      error.value = cause instanceof AdminApiError
        ? (previewScopeFailureMessage(cause.details) ?? adminErrorMessage(cause.messageKey))
        : cause instanceof Error
          ? cause.message
          : '预览生成失败，请重试。';
      return false;
    } finally {
      busy.value = false;
    }
  }

  /**
   * 打开打印预览。
   *
   * **复用公共字帖的 `openPrintPreview`**：其中已包含"打印页全部准备完成后才允许打印"
   * 以及后台标签页不超时的那套流程，因此管理页不另写一份。
   * 打印持有独立字节快照，关闭管理预览不会破坏正在准备的纸张。
   *
   * @returns 是否成功打开
   */
  async function print(): Promise<boolean> {
    if (!artifact.value) {
      error.value = '请先生成预览，再打印。';
      return false;
    }
    const current = artifact.value;
    const printBytes = new Uint8Array(current.bytes);
    const printUrl = URL.createObjectURL(new Blob([printBytes], { type: 'application/pdf' }));
    const handle = await openPrintPreview(
      () => window.open('', '_blank') as unknown as PrintWindowLike | null,
      {
        pdfUrl: printUrl,
        filename: current.filename,
        pageCount: current.pageCount,
        title: '教材草稿预览',
        renderPageImage: async (pageNumber, signal) => {
          if (signal.aborted) throw new Error('PRINT_CANCELLED');
          const canvas = document.createElement('canvas');
          await renderPdfPage(printBytes, canvas, pageNumber, 2, 'print');
          if (signal.aborted) throw new Error('PRINT_CANCELLED');
          const image = canvas.toDataURL('image/png');
          canvas.width = 0;
          canvas.height = 0;
          return image;
        },
        onRenderDisposed: () => releasePdf(printBytes)
      }
    );
    if (!handle.opened) {
      // 窗口被拦截时必须释放临时地址，否则每次点击都会泄漏一个 PDF。
      URL.revokeObjectURL(printUrl);
      return false;
    }
    return true;
  }

  return { artifact, page, zoom, busy, error, pageCount, revision, scope, generate, release, print };
};
