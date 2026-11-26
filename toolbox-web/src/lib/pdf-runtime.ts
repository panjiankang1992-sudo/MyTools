import type { PDFFont, PDFDocument } from 'pdf-lib';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import { createPdfFont } from './pdf-font';

let fontBytesPromise: Promise<Uint8Array> | undefined;
let pdfJsPromise: Promise<typeof import('pdfjs-dist')> | undefined;
const documentCache = new WeakMap<Uint8Array, Promise<PDFDocumentProxy>>();
const activeRenders = new WeakMap<HTMLCanvasElement, RenderTask>();
const renderVersions = new WeakMap<HTMLCanvasElement, number>();

/** 缓存随站点分发的许可字体，加载失败后允许重新尝试。 */
export async function getFontBytes(): Promise<Uint8Array> {
  if (!fontBytesPromise) {
    // 字体只从本站读取，不将练习内容发送到外部服务。
    fontBytesPromise = fetch(`${import.meta.env?.BASE_URL ?? '/'}fonts/NotoSansCJKsc-Regular.otf`)
      .then(async (response) => {
        if (!response.ok) throw new Error('FONT_UNAVAILABLE');
        return new Uint8Array(await response.arrayBuffer());
      })
      .catch(() => {
        fontBytesPromise = undefined;
        throw new Error('FONT_UNAVAILABLE');
      });
  }
  return fontBytesPromise;
}

/** 将中文及带声调拼音字体按实际使用字形嵌入当前文档。 */
export async function embedChineseFont(document: PDFDocument): Promise<PDFFont> {
  document.registerFontkit({ create: createPdfFont });
  return document.embedFont(await getFontBytes(), { subset: true });
}

/** 延迟初始化浏览器 PDF 阅读器，避免首页加载时下载工作线程。 */
export async function getPdfJs(): Promise<typeof import('pdfjs-dist')> {
  if (!pdfJsPromise) {
    pdfJsPromise = Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
    ]).then(([pdfJs, worker]) => {
      pdfJs.GlobalWorkerOptions.workerSrc = worker.default;
      return pdfJs;
    }).catch((error: unknown) => {
      pdfJsPromise = undefined;
      throw error;
    });
  }
  return pdfJsPromise;
}

/** 为外来 PDF 提供同站点字符映射与标准字体，关闭动态求值。 */
export function getPdfDocumentOptions(): {
  cMapUrl: string;
  cMapPacked: boolean;
  standardFontDataUrl: string;
  isEvalSupported: boolean;
  useSystemFonts: boolean;
} {
  const base = `${import.meta.env?.BASE_URL ?? '/'}pdfjs/`;
  return {
    cMapUrl: `${base}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${base}standard_fonts/`,
    isEvalSupported: false,
    useSystemFonts: false,
  };
}

/** 打开并缓存当前 PDF，同时复制输入以防工作线程转移原始下载数据。 */
export async function openPdf(bytes: Uint8Array): Promise<PDFDocumentProxy> {
  let cached = documentCache.get(bytes);
  if (!cached) {
    cached = getPdfJs().then((pdfJs) => pdfJs.getDocument({
      data: new Uint8Array(bytes),
      ...getPdfDocumentOptions(),
    }).promise);
    documentCache.set(bytes, cached);
    cached.catch(() => documentCache.delete(bytes));
  }
  return cached;
}

/** 在预览文件被替换时释放其解析文档与工作线程。 */
export function releasePdf(bytes: Uint8Array): void {
  const cached = documentCache.get(bytes);
  documentCache.delete(bytes);
  if (cached) void cached.then((document) => document.destroy()).catch(() => undefined);
}

/** 将同一份 PDF 渲染为预览或打印页；打印不依赖前台标签页的动画帧。 */
export async function renderPdfPage(
  bytes: Uint8Array,
  canvas: HTMLCanvasElement,
  pageNumber = 1,
  scale = 1,
  intent: 'display' | 'print' = 'display',
): Promise<{ width: number; height: number; pageCount: number }> {
  const version = (renderVersions.get(canvas) ?? 0) + 1;
  renderVersions.set(canvas, version);
  const document = await openPdf(bytes);
  if (pageNumber < 1 || pageNumber > document.numPages) throw new Error('INVALID_PAGE');
  const page = await document.getPage(pageNumber);
  if (renderVersions.get(canvas) !== version) throw new Error('STALE_RENDER');
  const previous = activeRenders.get(canvas);
  if (previous) {
    // 同一画布先终止旧渲染，防止快速翻页产生并发写入。
    previous.cancel();
    await previous.promise.catch(() => undefined);
    if (renderVersions.get(canvas) !== version) throw new Error('STALE_RENDER');
  }
  const viewport = page.getViewport({ scale: Math.max(0.1, Math.min(4, scale)) });
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
  const pixelWidth = Math.ceil(viewport.width * pixelRatio);
  const pixelHeight = Math.ceil(viewport.height * pixelRatio);
  if (!Number.isFinite(pixelWidth) || !Number.isFinite(pixelHeight) || pixelWidth > 16384 || pixelHeight > 16384 || pixelWidth * pixelHeight > 24_000_000) {
    // 外来文件可能包含异常大纸张，在分配画布前限制像素与边长。
    throw new Error('PREVIEW_TOO_LARGE');
  }
  const context = canvas.getContext('2d');
  if (!context) throw new Error('CANVAS_UNAVAILABLE');
  canvas.width = pixelWidth;
  canvas.height = pixelHeight;
  canvas.style.width = `${viewport.width}px`;
  canvas.style.height = `${viewport.height}px`;
  const task = page.render({
    // 打印窗口会使来源标签页进入后台，使用打印模式避免动画帧暂停导致超时。
    intent,
    canvas,
    canvasContext: context,
    viewport,
    transform: pixelRatio === 1 ? undefined : [pixelRatio, 0, 0, pixelRatio, 0, 0],
  });
  activeRenders.set(canvas, task);
  try {
    await task.promise;
  } finally {
    if (activeRenders.get(canvas) === task) activeRenders.delete(canvas);
  }
  return { width: viewport.width, height: viewport.height, pageCount: document.numPages };
}

/** 发起浏览器文件下载，并在浏览器取得地址后释放临时资源。 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
