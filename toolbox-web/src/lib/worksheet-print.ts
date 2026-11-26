/**
 * 字帖打印窗口只打印已完整解码的 A4 页图。
 * 下载始终使用同一份已生成 PDF；缺少渲染器或准备失败时仅保留下载，不打印内嵌框或半成品。
 */

/** 打印窗口参数。 */
export interface PrintPreviewOptions {
  /** 本次窗口独立拥有的 PDF 对象地址，关闭窗口时释放。 */
  pdfUrl: string;
  filename: string;
  pageCount: number;
  title: string;
  busyLabel?: string;
  /** 从当前 PDF 逐页生成图像；信号中止后不得开始下一页，返回的对象地址由窗口释放。 */
  renderPageImage?: (pageNumber: number, signal: AbortSignal) => Promise<string>;
  /** 真正调用系统打印后回调；打开窗口、下载、失败和关闭均不计数。 */
  onPrintRequested?: () => void;
  /** 渲染完成、失败或窗口关闭时至多调用一次，用于释放专用 PDF 解析器和画布。 */
  onRenderDisposed?: () => void;
  /** 单页渲染及图像解码共用的最长等待时间。 */
  renderTimeoutMs?: number;
}

/** 打印窗口句柄；准备完成前及关闭后调用打印不会执行任何动作。 */
export interface PrintPreviewHandle {
  opened: boolean;
  readonly ready: boolean;
  print: () => void;
  close: () => void;
}

/** 最小窗口边界，避免跨窗口对象使用主窗口的 instanceof 判断。 */
export interface PrintWindowLike {
  document: Document;
  focus(): void;
  print(): void;
  close(): void;
  closed: boolean;
  opener: unknown;
  addEventListener(type: string, listener: () => void): void;
}

/** 工具栏及准备状态文案。 */
export interface PrintLabels {
  export: string;
  print: string;
  close: string;
  pages: string;
  rendering: string;
  failed: string;
}

/** 单页渲染和解码的默认时限。 */
export const DEFAULT_RENDER_TIMEOUT_MS = 15000;

/** 打印界面的固定文案。 */
export const PRINT_LABELS: PrintLabels = Object.freeze({
  export: '\u5bfc\u51fa PDF',
  print: '\u6253\u5370',
  close: '\u5173\u95ed\u7a97\u53e3',
  pages: '\u9875',
  rendering: '\u6b63\u5728\u51c6\u5907\u6253\u5370\u9875\u2026',
  failed: '\u6253\u5370\u9875\u51c6\u5907\u5931\u8d25\u3002\u8bf7\u70b9\u51fb\u201c\u5bfc\u51fa PDF\u201d\uff0c\u5728 PDF \u9605\u8bfb\u5668\u4e2d\u6253\u5370\u3002'
});

/** 对渲染及解码一起限时，关闭窗口后立即停止等待并清理计时器。 */
function waitForPage<T>(task: Promise<T>, timeoutMs: number, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const finish = (callback: () => void): void => {
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      callback();
    };
    const abort = (): void => finish(() => reject(new Error('PRINT_CLOSED')));
    const timer = setTimeout(() => finish(() => reject(new Error('PRINT_PAGE_TIMEOUT'))), timeoutMs);
    signal.addEventListener('abort', abort, { once: true });
    task.then(value => finish(() => resolve(value)), error => finish(() => reject(error)));
    if (signal.aborted) abort();
  });
}

/** 使用图像所属窗口的实例方法解码，不从另一个窗口借用原型方法。 */
async function decodePageImage(image: HTMLImageElement, signal: AbortSignal): Promise<void> {
  if (typeof image.decode === 'function') {
    await image.decode();
  } else if (!image.complete) {
    // 兼容没有 decode 的浏览器，关闭窗口时同时移除负载事件监听。
    await new Promise<void>((resolve, reject) => {
      const clear = (): void => {
        image.removeEventListener('load', loaded);
        image.removeEventListener('error', failed);
        signal.removeEventListener('abort', aborted);
      };
      const loaded = (): void => { clear(); resolve(); };
      const failed = (): void => { clear(); reject(new Error('PRINT_IMAGE_FAILED')); };
      const aborted = (): void => { clear(); reject(new Error('PRINT_CLOSED')); };
      image.addEventListener('load', loaded, { once: true });
      image.addEventListener('error', failed, { once: true });
      signal.addEventListener('abort', aborted, { once: true });
      if (signal.aborted) aborted();
    });
  }
  // 解码完成但没有像素的图像也不可视为可打印页。
  if (!image.naturalWidth || !image.naturalHeight) throw new Error('PRINT_IMAGE_EMPTY');
}

/** 固定 A4 纸面，隐藏工具栏和状态，未就绪时不输出部分页图。 */
export function printPreviewStyle(): string {
  return '@page{size:A4 portrait;margin:0}*{box-sizing:border-box}'
    + 'body{margin:0;background:#edf1f7;font-family:sans-serif}'
    + '.print-actions{padding:16px;position:sticky;top:0;background:#fff;display:flex;gap:12px;align-items:center;flex-wrap:wrap;box-shadow:0 1px 4px #0000001a}'
    + 'a,button{display:inline-flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;padding:10px 20px;cursor:pointer;border:1px solid #d7dee8;border-radius:6px;background:#fff;color:#1f2937;font-size:14px;text-decoration:none}'
    + 'button.primary{background:#646cff;border-color:#646cff;color:#fff}'
    + 'button:disabled{opacity:.5;cursor:wait}'
    + '.page-summary{margin-left:auto;color:#7a8597;font-size:13px}'
    + '.print-status{padding:12px 16px;line-height:1.6}'
    + '.page{display:block;width:210mm;height:297mm;object-fit:contain;margin:16px auto;break-after:page;break-inside:avoid;page-break-inside:avoid;background:#fff}'
    + '.page:last-child{break-after:auto}'
    + '@media print{body{background:#fff}.print-actions,.print-status{display:none}.page{margin:0}body:not(.print-ready) .print-pages{display:none}}';
}

/** 窗口内部状态控制器，不向调用者暴露绕过就绪检查的入口。 */
interface PrintPreviewController {
  handle: PrintPreviewHandle;
  signal: AbortSignal;
  pages: HTMLElement;
  active: () => boolean;
  retainImageUrl: (url: string) => void;
  ready: () => void;
  fail: () => void;
}

/** 创建禁用打印的工具栏，只有完整页图准备管道可以解除禁用。 */
function createPrintPreview(target: PrintWindowLike, options: PrintPreviewOptions): PrintPreviewController {
  target.opener = null;
  const doc = target.document;
  doc.title = options.title;
  const style = doc.createElement('style');
  style.textContent = printPreviewStyle();
  doc.head.append(style);
  const toolbar = doc.createElement('div');
  toolbar.className = 'print-actions';
  const download = doc.createElement('a');
  download.textContent = PRINT_LABELS.export;
  download.href = options.pdfUrl;
  download.download = options.filename;
  download.setAttribute('role', 'button');
  const printButton = doc.createElement('button');
  printButton.className = 'primary';
  printButton.textContent = PRINT_LABELS.print;
  printButton.disabled = true;
  const closeButton = doc.createElement('button');
  closeButton.textContent = PRINT_LABELS.close;
  const summary = doc.createElement('span');
  summary.className = 'page-summary';
  summary.textContent = `${options.pageCount} ${PRINT_LABELS.pages}`;
  toolbar.append(download, printButton, closeButton, summary);
  const status = doc.createElement('p');
  status.className = 'print-status';
  status.setAttribute('role', 'status');
  status.textContent = options.busyLabel ?? PRINT_LABELS.rendering;
  const pages = doc.createElement('div');
  pages.className = 'print-pages';
  doc.body.append(toolbar, status, pages);

  const controller = new AbortController();
  const imageUrls = new Set<string>();
  let ready = false;
  let closed = false;
  let renderDisposed = false;
  const disposeRender = (): void => {
    if (renderDisposed) return;
    renderDisposed = true;
    options.onRenderDisposed?.();
  };
  const clearImages = (): void => {
    for (const image of [...pages.querySelectorAll('img.page')]) image.remove();
    for (const url of imageUrls) URL.revokeObjectURL(url);
    imageUrls.clear();
  };
  const disablePrint = (): void => {
    ready = false;
    printButton.disabled = true;
    doc.body.classList.remove('print-ready');
  };
  const dispose = (): void => {
    if (closed) return;
    closed = true;
    disablePrint();
    controller.abort();
    clearImages();
    URL.revokeObjectURL(options.pdfUrl);
    disposeRender();
  };
  const active = (): boolean => !closed && !target.closed && !controller.signal.aborted;
  const requestPrint = (): void => {
    if (!ready || !active()) return;
    target.focus();
    target.print();
    // 系统打印调用抛错时不会走到这里；取消打印也只计一次发起，不声称纸张已完成。
    options.onPrintRequested?.();
  };
  printButton.onclick = requestPrint;
  closeButton.onclick = () => { dispose(); target.close(); };
  target.addEventListener('pagehide', dispose);
  target.addEventListener('unload', dispose);
  return {
    handle: {
      opened: true,
      get ready() { return ready && active(); },
      print: requestPrint,
      close: () => { dispose(); target.close(); }
    },
    signal: controller.signal,
    pages,
    active,
    retainImageUrl: url => {
      if (!url.startsWith('blob:')) return;
      // 在关闭或超时之后才返回的图像也必须回收，不能重新插入关闭的窗口。
      if (!active()) URL.revokeObjectURL(url);
      else imageUrls.add(url);
    },
    ready: () => {
      if (!active()) { dispose(); return; }
      status.remove();
      ready = true;
      printButton.disabled = false;
      doc.body.classList.add('print-ready');
      disposeRender();
    },
    fail: () => {
      if (closed || target.closed) { dispose(); return; }
      disablePrint();
      controller.abort();
      clearImages();
      status.textContent = PRINT_LABELS.failed;
      status.setAttribute('role', 'alert');
      disposeRender();
    }
  };
}

/** 只搭建窗口结构，默认禁用打印；完整打印应通过 openPrintPreview 准备页图。 */
export function buildPrintPreview(target: PrintWindowLike, options: PrintPreviewOptions): PrintPreviewHandle {
  return createPrintPreview(target, options).handle;
}

/** 同步打开窗口后异步准备所有页图，失败时保留下载 PDF 入口且不打印半成品。 */
export async function openPrintPreview(
  openWindow: () => PrintWindowLike | null,
  options: PrintPreviewOptions
): Promise<PrintPreviewHandle> {
  // 首个 await 前打开窗口，保留按钮点击的浏览器用户激活状态。
  const target = openWindow();
  if (!target) {
    options.onRenderDisposed?.();
    URL.revokeObjectURL(options.pdfUrl);
    return { opened: false, ready: false, print: () => undefined, close: () => undefined };
  }
  const preview = createPrintPreview(target, options);
  const render = options.renderPageImage;
  if (!render || !Number.isInteger(options.pageCount) || options.pageCount < 1) {
    preview.fail();
    return preview.handle;
  }
  const timeoutMs = options.renderTimeoutMs ?? DEFAULT_RENDER_TIMEOUT_MS;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    preview.fail();
    return preview.handle;
  }
  try {
    for (let pageNumber = 1; pageNumber <= options.pageCount; pageNumber += 1) {
      if (!preview.active()) throw new Error('PRINT_CLOSED');
      await waitForPage((async () => {
        const source = await render(pageNumber, preview.signal);
        preview.retainImageUrl(source);
        if (!preview.active()) throw new Error('PRINT_CLOSED');
        const image = target.document.createElement('img');
        image.className = 'page';
        image.alt = `${pageNumber}`;
        image.src = source;
        preview.pages.append(image);
        await decodePageImage(image, preview.signal);
        if (!preview.active()) throw new Error('PRINT_CLOSED');
      })(), timeoutMs, preview.signal);
    }
    preview.ready();
  } catch {
    preview.fail();
  }
  return preview.handle;
}
