import assert from 'node:assert/strict';
import test from 'node:test';
import { buildPrintPreview, openPrintPreview, PRINT_LABELS, printPreviewStyle, type PrintWindowLike } from '../src/lib/worksheet-print';

/** 可控的异步关卡，用于复现渲染或图片解码尚未完成时的用户动作。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}

/** 与浏览器无关的 DOM 边界，只实现打印模块真正使用的成员。 */
interface FakeElement {
  tagName: string;
  children: FakeElement[];
  parent: FakeElement | null;
  textContent: string;
  className: string;
  attributes: Record<string, string>;
  disabled: boolean;
  onclick: (() => void) | null;
  href: string;
  download: string;
  src: string;
  alt: string;
  complete: boolean;
  naturalWidth: number;
  naturalHeight: number;
  decode?: () => Promise<void>;
  classList: { add(value: string): void; remove(value: string): void; contains(value: string): boolean };
  append(...nodes: FakeElement[]): void;
  remove(): void;
  querySelectorAll(selector: string): FakeElement[];
  setAttribute(name: string, value: string): void;
  addEventListener(name: string, listener: () => void): void;
  removeEventListener(name: string, listener: () => void): void;
  dispatch(name: string): void;
}

/** 构造保留父子关系与事件监听的元素，移除节点会真正影响后续查询。 */
function makeElement(tag: string): FakeElement {
  const listeners = new Map<string, Set<() => void>>();
  const classes = new Set<string>();
  const element: FakeElement = {
    tagName: tag, children: [], parent: null, textContent: '', className: '', attributes: {},
    disabled: false, onclick: null, href: '', download: '', src: '', alt: '',
    complete: false, naturalWidth: 2100, naturalHeight: 2970,
    classList: { add: value => { classes.add(value); }, remove: value => { classes.delete(value); }, contains: value => classes.has(value) },
    append: (...nodes) => { for (const node of nodes) { node.parent = element; element.children.push(node); } },
    remove: () => {
      if (element.parent) element.parent.children = element.parent.children.filter(child => child !== element);
      element.parent = null;
    },
    querySelectorAll: selector => {
      const [name, className] = selector.split('.');
      const found: FakeElement[] = [];
      const walk = (node: FakeElement): void => {
        for (const child of node.children) {
          if ((!name || child.tagName === name) && (!className || child.className.split(/\s+/).includes(className))) found.push(child);
          walk(child);
        }
      };
      walk(element);
      return found;
    },
    setAttribute: (name, value) => { element.attributes[name] = value; },
    addEventListener: (name, listener) => {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name)!.add(listener);
    },
    removeEventListener: (name, listener) => { listeners.get(name)?.delete(listener); },
    dispatch: name => { for (const listener of [...(listeners.get(name) ?? [])]) listener(); }
  };
  return element;
}

/** 每张图的解码方法属于该实例；借用主窗口原型或提前打印都会被断言发现。 */
function makeWindow(decode: (image: FakeElement, index: number) => Promise<void> = async () => {}) {
  const calls = { print: 0, focus: 0, close: 0, decode: [] as number[] };
  const listeners = new Map<string, Set<() => void>>();
  let imageIndex = 0;
  const doc = {
    title: '', head: makeElement('head'), body: makeElement('body'),
    createElement: (tag: string): FakeElement => {
      const image = makeElement(tag);
      if (tag === 'img') {
        const index = ++imageIndex;
        image.decode = async function (this: FakeElement): Promise<void> {
          assert.equal(this, image, 'Decode must run with the target document image as its receiver');
          calls.decode.push(index);
          await decode(image, index);
          image.complete = true;
        };
      }
      return image;
    }
  };
  const dispatch = (name: string): void => { for (const listener of [...(listeners.get(name) ?? [])]) listener(); };
  const target = {
    document: doc as unknown as Document,
    closed: false as boolean,
    opener: 'opener' as unknown,
    focus: () => { calls.focus += 1; },
    print: () => { calls.print += 1; },
    close: () => { calls.close += 1; target.closed = true; dispatch('pagehide'); dispatch('unload'); },
    addEventListener: (name: string, listener: () => void) => {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name)!.add(listener);
    }
  } satisfies PrintWindowLike;
  return { target, doc, calls, dispatch };
}

/** 按工具栏的可见文字查找控件。 */
function control(doc: ReturnType<typeof makeWindow>['doc'], label: string): FakeElement {
  const result = [...doc.body.querySelectorAll('button'), ...doc.body.querySelectorAll('a')].find(node => node.textContent === label);
  assert.ok(result, `Missing control: ${label}`);
  return result;
}

const options = { pdfUrl: 'blob:local/pdf', filename: 'student.pdf', pageCount: 2, title: 'Practice' };
const render = async (page: number): Promise<string> => `blob:local/page-${page}`;
const tick = (): Promise<void> => new Promise(resolve => setImmediate(resolve));

test('toolbar and handle cannot print before pages are ready, and opening never prints automatically', () => {
  const { target, doc, calls } = makeWindow();
  const handle = buildPrintPreview(target, options);
  assert.equal(target.opener, null);
  assert.equal(handle.ready, false);
  assert.equal(control(doc, PRINT_LABELS.print).disabled, true);
  control(doc, PRINT_LABELS.print).onclick!();
  handle.print();
  assert.deepEqual([calls.print, calls.focus], [0, 0]);
  const download = control(doc, PRINT_LABELS.export);
  assert.equal(download.href, options.pdfUrl);
  assert.equal(download.download, options.filename);
  assert.equal(doc.body.querySelectorAll('span.page-summary')[0]!.textContent, `2 ${PRINT_LABELS.pages}`);
  handle.close();
});

test('popup opens synchronously and printing stays blocked until every page has decoded', async () => {
  const first = deferred<void>();
  const second = deferred<void>();
  const { target, doc, calls } = makeWindow(async (_image, index) => index === 1 ? first.promise : second.promise);
  const pages: number[] = [];
  let opened = false;
  let requests = 0;
  let disposed = 0;
  const pending = openPrintPreview(() => { opened = true; return target; }, {
    ...options,
    renderPageImage: async page => { pages.push(page); return render(page); },
    onPrintRequested: () => { requests += 1; }, onRenderDisposed: () => { disposed += 1; }
  });
  assert.equal(opened, true, 'Window must open before the first asynchronous yield');
  await tick();
  assert.deepEqual(pages, [1]);
  control(doc, PRINT_LABELS.print).onclick!();
  assert.equal(calls.print, 0);
  assert.equal(control(doc, PRINT_LABELS.print).disabled, true);
  first.resolve();
  await tick();
  assert.deepEqual(pages, [1, 2]);
  assert.deepEqual(calls.decode, [1, 2]);
  control(doc, PRINT_LABELS.print).onclick!();
  assert.equal(calls.print, 0, 'One decoded page is insufficient');
  second.resolve();
  const handle = await pending;
  assert.equal(handle.ready, true);
  assert.equal(control(doc, PRINT_LABELS.print).disabled, false);
  assert.equal(doc.body.classList.contains('print-ready'), true);
  assert.equal(doc.body.querySelectorAll('p.print-status').length, 0);
  assert.deepEqual(doc.body.querySelectorAll('img.page').map(image => image.src), ['blob:local/page-1', 'blob:local/page-2']);
  assert.equal(disposed, 1, 'Dedicated renderer resources are released after all pages decode');
  assert.equal(requests, 0);
  handle.print();
  control(doc, PRINT_LABELS.print).onclick!();
  assert.deepEqual([calls.print, calls.focus, requests], [2, 2, 2]);
  control(doc, PRINT_LABELS.close).onclick!();
  handle.print();
  assert.equal(handle.ready, false);
  assert.equal(calls.print, 2, 'Closed windows cannot print via a retained handle');
  assert.equal(disposed, 1);
});

test('blocked popup releases the PDF and render resources without triggering print', async t => {
  const revoked: string[] = [];
  t.mock.method(URL, 'revokeObjectURL', (url: string) => { revoked.push(url); });
  let disposed = 0;
  const handle = await openPrintPreview(() => null, { ...options, onRenderDisposed: () => { disposed += 1; } });
  assert.equal(handle.opened, false);
  assert.equal(handle.ready, false);
  handle.print(); handle.close();
  assert.deepEqual(revoked, [options.pdfUrl]);
  assert.equal(disposed, 1);
});

test('missing renderer leaves explicit PDF download guidance and never embeds or prints the outer page', async () => {
  const { target, doc, calls } = makeWindow();
  const handle = await openPrintPreview(() => target, options);
  assert.equal(handle.ready, false);
  assert.equal(control(doc, PRINT_LABELS.print).disabled, true);
  assert.equal(doc.body.querySelectorAll('embed').length, 0);
  assert.equal(doc.body.querySelectorAll('p.print-status')[0]!.textContent, PRINT_LABELS.failed);
  assert.equal(doc.body.querySelectorAll('p.print-status')[0]!.attributes.role, 'alert');
  assert.equal(control(doc, PRINT_LABELS.export).href, options.pdfUrl);
  control(doc, PRINT_LABELS.print).onclick!(); handle.print();
  assert.equal(calls.print, 0);
  handle.close();
});

test('render failure removes decoded partial pages while preserving the current PDF download', async t => {
  const revoked: string[] = [];
  t.mock.method(URL, 'revokeObjectURL', (url: string) => { revoked.push(url); });
  const { target, doc, calls } = makeWindow();
  let disposed = 0;
  const handle = await openPrintPreview(() => target, {
    ...options, renderPageImage: async page => { if (page === 2) throw new Error('RENDER_FAILED'); return render(page); },
    onRenderDisposed: () => { disposed += 1; }
  });
  assert.equal(handle.ready, false);
  assert.equal(doc.body.querySelectorAll('img.page').length, 0);
  assert.equal(doc.body.querySelectorAll('embed').length, 0);
  assert.deepEqual(revoked, ['blob:local/page-1']);
  assert.equal(control(doc, PRINT_LABELS.export).href, options.pdfUrl);
  handle.print();
  assert.equal(calls.print, 0);
  assert.equal(disposed, 1);
  handle.close();
  assert.deepEqual(revoked, ['blob:local/page-1', options.pdfUrl]);
});

test('image decode failure blocks all printing and removes the partial images', async () => {
  const { target, doc, calls } = makeWindow(async (_image, index) => { if (index === 2) throw new Error('DECODE_FAILED'); });
  const handle = await openPrintPreview(() => target, { ...options, renderPageImage: render });
  assert.deepEqual(calls.decode, [1, 2]);
  assert.equal(handle.ready, false);
  assert.equal(doc.body.querySelectorAll('img.page').length, 0);
  handle.print(); control(doc, PRINT_LABELS.print).onclick!();
  assert.equal(calls.print, 0);
  handle.close();
});

test('an empty decoded image cannot become a printable page', async () => {
  const { target } = makeWindow(async image => { image.naturalWidth = 0; });
  const handle = await openPrintPreview(() => target, { ...options, renderPageImage: render });
  assert.equal(handle.ready, false);
  handle.close();
});

for (const hangingStage of ['render', 'decode'] as const) {
  test(`a hung ${hangingStage} times out, aborts the pipeline and preserves PDF download`, async () => {
    const { target, doc, calls } = makeWindow(async () => {
      if (hangingStage === 'decode') await new Promise<void>(() => {});
    });
    let signal: AbortSignal | undefined;
    const requested: number[] = [];
    const handle = await openPrintPreview(() => target, {
      ...options, renderTimeoutMs: 30,
      renderPageImage: async (page, currentSignal) => {
        signal = currentSignal; requested.push(page);
        return hangingStage === 'render' ? new Promise<string>(() => {}) : render(page);
      }
    });
    assert.deepEqual(requested, [1]);
    assert.equal(signal?.aborted, true);
    assert.equal(handle.ready, false);
    assert.equal(doc.body.querySelectorAll('img.page').length, 0);
    assert.equal(doc.body.querySelectorAll('p.print-status')[0]!.textContent, PRINT_LABELS.failed);
    handle.print();
    assert.equal(calls.print, 0);
    handle.close();
  });
}

test('closing during render cancels waiting and releases even a late image URL without starting another page', async t => {
  const revoked: string[] = [];
  t.mock.method(URL, 'revokeObjectURL', (url: string) => { revoked.push(url); });
  const late = deferred<string>();
  const { target, doc, calls } = makeWindow();
  let signal: AbortSignal | undefined;
  let disposed = 0;
  const pages: number[] = [];
  const pending = openPrintPreview(() => target, {
    ...options, renderPageImage: (page, currentSignal) => { signal = currentSignal; pages.push(page); return late.promise; },
    onRenderDisposed: () => { disposed += 1; }
  });
  control(doc, PRINT_LABELS.close).onclick!();
  const handle = await pending;
  assert.equal(signal?.aborted, true);
  assert.equal(handle.ready, false);
  assert.equal(disposed, 1);
  assert.deepEqual(revoked, [options.pdfUrl]);
  late.resolve('blob:local/late-page');
  await tick();
  assert.deepEqual(pages, [1]);
  assert.deepEqual(revoked, [options.pdfUrl, 'blob:local/late-page']);
  assert.equal(doc.body.querySelectorAll('img.page').length, 0);
  handle.print();
  assert.equal(calls.print, 0);
});

test('native window close during decoding aborts promptly and cleans up exactly once', async t => {
  const revoked: string[] = [];
  t.mock.method(URL, 'revokeObjectURL', (url: string) => { revoked.push(url); });
  const gate = deferred<void>();
  const { target, doc, dispatch } = makeWindow(async () => gate.promise);
  let disposed = 0;
  const requested: number[] = [];
  const pending = openPrintPreview(() => target, {
    ...options, renderPageImage: async page => { requested.push(page); return render(page); },
    onRenderDisposed: () => { disposed += 1; }
  });
  await tick();
  target.closed = true;
  dispatch('pagehide'); dispatch('unload');
  const handle = await pending;
  assert.equal(handle.ready, false);
  assert.equal(disposed, 1);
  assert.deepEqual(revoked.sort(), ['blob:local/page-1', options.pdfUrl].sort());
  gate.resolve(); await tick();
  assert.deepEqual(requested, [1]);
  assert.equal(doc.body.querySelectorAll('img.page').length, 0);
});

test('system print errors do not report a successful request callback', async () => {
  const { target, doc } = makeWindow();
  let requested = 0;
  target.print = () => { throw new Error('PRINT_UNAVAILABLE'); };
  const handle = await openPrintPreview(() => target, { ...options, renderPageImage: render, onPrintRequested: () => { requested += 1; } });
  assert.throws(() => handle.print(), /PRINT_UNAVAILABLE/);
  assert.throws(() => control(doc, PRINT_LABELS.print).onclick!(), /PRINT_UNAVAILABLE/);
  assert.equal(requested, 0);
  handle.close();
});

test('invalid page counts cannot enable printing', async () => {
  for (const pageCount of [0, -1, 1.5, Number.NaN]) {
    const { target } = makeWindow();
    let rendered = 0;
    const handle = await openPrintPreview(() => target, { ...options, pageCount, renderPageImage: async () => { rendered += 1; return ''; } });
    assert.equal(handle.ready, false);
    assert.equal(rendered, 0);
    handle.close();
  }
});

test('print geometry is A4 and unfinished pages and toolbar are hidden from native printing', () => {
  const css = printPreviewStyle();
  assert.match(css, /@page\{size:A4 portrait;margin:0\}/);
  assert.match(css, /width:210mm;height:297mm/);
  assert.match(css, /break-inside:avoid/);
  assert.match(css, /\.print-actions,\.print-status\{display:none\}/);
  assert.match(css, /body:not\(\.print-ready\) \.print-pages\{display:none\}/);
});
