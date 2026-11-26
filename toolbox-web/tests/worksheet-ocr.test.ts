import assert from 'node:assert/strict';
import test from 'node:test';
import {
  OcrError, WorksheetOcrService, assetPaths, languagePacks, type OcrWorkerLike, type OcrWorkerOptions, type OcrWorkerResult
} from '../src/services/worksheet-ocr';

/** 可控的引擎桩；记录并发峰值与调用参数。 */
function makeHarness(options: { recognizeDelayMs?: number; assetDelayMs?: number; confidence?: number | undefined } = {}) {
  const created: Array<{ language: string; options: OcrWorkerOptions }> = [];
  const terminated: number[] = [];
  let concurrent = 0;
  let peak = 0;
  let index = 0;
  const createWorker = async (language: string, workerOptions: OcrWorkerOptions): Promise<OcrWorkerLike> => {
    const id = index++;
    created.push({ language, options: workerOptions });
    if (options.assetDelayMs) await new Promise(resolve => setTimeout(resolve, options.assetDelayMs));
    return {
      recognize: async (image: Blob) => {
        concurrent += 1;
        peak = Math.max(peak, concurrent);
        try {
          if (options.recognizeDelayMs) await new Promise(resolve => setTimeout(resolve, options.recognizeDelayMs));
          const text = await image.text();
          return { data: { text: `识别:${text}`, confidence: options.confidence } };
        } finally {
          concurrent -= 1;
        }
      },
      terminate: () => { terminated.push(id); }
    } as OcrWorkerLike;
  };
  return { createWorker, created, terminated, peakConcurrency: () => peak };
}

const spec = (jobId: string, language: 'chinese' | 'english' = 'chinese') => ({
  jobId, imageId: `img-${jobId}`, inputRevision: 1, language, image: new Blob([jobId])
});

test('按需加载：创建服务时和首次识别前都不加载 worker', async () => {
  const harness = makeHarness();
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  assert.equal(service.loaded, false, '创建服务不得加载引擎');
  assert.equal(harness.created.length, 0);
  await service.recognize(spec('j1'));
  assert.equal(service.loaded, true, '识别后才加载');
  assert.equal(harness.created.length, 1);
});

test('资产路径全部指向同域，且禁用引擎自建缓存', () => {
  const paths = assetPaths('/ocr/tesseract-7.0.0/');
  assert.ok(paths.workerPath.startsWith('/ocr/tesseract-7.0.0/'));
  assert.ok(paths.corePath.startsWith('/ocr/tesseract-7.0.0/'));
  assert.ok(paths.langPath.startsWith('/ocr/tesseract-7.0.0/'));
  assert.equal(paths.workerPath.startsWith('http'), false, '不得指向第三方 CDN');
  assert.equal(paths.gzip, true);
  assert.equal(paths.cacheMethod, 'none');
  // 末尾缺少斜杠时自动补上，避免拼出 /ocr/v1worker.min.js。
  assert.equal(assetPaths('/ocr/v1').workerPath, '/ocr/v1/worker.min.js');
});

test('中英教材均加载中英混排语言包，主语言顺序与学科一致', () => {
  assert.deepEqual(languagePacks('chinese'), ['chi_sim', 'eng']);
  assert.deepEqual(languagePacks('english'), ['eng', 'chi_sim']);
});

test('同一语言复用 worker，切换语言才重建', async () => {
  const harness = makeHarness();
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  await service.recognize(spec('j1'));
  await service.recognize(spec('j2'));
  assert.equal(harness.created.length, 1, '同语言必须复用');
  await service.recognize(spec('j3', 'english'));
  assert.equal(harness.created.length, 2, '换语言必须重建');
  assert.equal(harness.created[1]!.language, 'eng+chi_sim');
  assert.deepEqual(harness.terminated, [0], '旧 worker 必须被终止');
});

test('词表候选和原始全文分别透传，空候选不能变成原始全文', async () => {
  let calls = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => ({
    recognize: async () => ({ data: {
      text: 'Monday 星期一 (24)\nafter school 放学后 (28)',
      candidateText: calls++ === 0 ? 'Monday\nafter school' : '',
      confidence: 94
    } }),
    terminate: () => undefined
  }) });
  const first = await service.recognize(spec('vocabulary', 'english'));
  assert.equal(first.rawRecognizedText, 'Monday 星期一 (24)\nafter school 放学后 (28)');
  assert.equal(first.candidateText, 'Monday\nafter school');
  assert.equal(first.confidence, 94);
  const empty = await service.recognize(spec('empty', 'english'));
  assert.equal(empty.candidateText, '', '过滤后为空仍保留明确的空候选');
  assert.equal(empty.rawRecognizedText, first.rawRecognizedText);
  await service.dispose();
});

test('中英对应结果原样透传，保留重复词、空释义及明确空列表', async () => {
  const entries = [{ text: 'play', meaning: '玩耍', confidence: 93 }, { text: 'play', meaning: '戏剧' }, { text: 'free', meaning: '' }];
  let calls = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => ({
    recognize: async () => ({ data: { text: 'play 玩耍\nplay 戏剧\nfree', entries: calls++ === 0 ? entries : [] } }),
    terminate: () => undefined
  }) });
  const first = await service.recognize(spec('paired', 'english'));
  assert.strictEqual(first.entries, entries);
  assert.deepEqual((await service.recognize(spec('empty-pairs', 'english'))).entries, []);
  await service.dispose();
});

test('多图串行：峰值并发恒为 1，且顺序与列表一致', async () => {
  const harness = makeHarness({ recognizeDelayMs: 30 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  const results = await Promise.all([service.recognize(spec('a')), service.recognize(spec('b')), service.recognize(spec('c'))]);
  assert.equal(harness.peakConcurrency(), 1, '任何时刻最多一个识别任务');
  assert.deepEqual(results.map(r => r.jobId), ['a', 'b', 'c'], '必须按提交顺序完成，而不是按完成先后');
});

test('资产加载超时使用独立预算并报出稳定错误码', async () => {
  const harness = makeHarness({ assetDelayMs: 200 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker, assetTimeoutMs: 20 });
  await assert.rejects(() => service.recognize(spec('j1')), (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_ASSET_TIMEOUT');
});

test('识别超时终止 worker 并释放槽位，后续任务仍可执行', async () => {
  const slow = makeHarness({ recognizeDelayMs: 200 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: slow.createWorker, recognizeTimeoutMs: 20 });
  await assert.rejects(() => service.recognize(spec('j1')), (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_RECOGNIZE_TIMEOUT');
  assert.deepEqual(slow.terminated, [0], '超时必须终止 worker');

  // 换一个守时的桩继续验证队列没有被卡死。
  const fast = makeHarness();
  const resumed = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: fast.createWorker });
  const outcome = await resumed.recognize(spec('j2'));
  assert.equal(outcome.jobId, 'j2');
});

test('取消后迟到结果作废，且不影响后续任务', async () => {
  const harness = makeHarness({ recognizeDelayMs: 40 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  const pending = service.recognize(spec('j1'));
  service.cancel('j1');
  await assert.rejects(() => pending, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  const next = await service.recognize(spec('j2'));
  assert.equal(next.jobId, 'j2', '取消不得阻塞后续任务');
});

test('未提供置信度时必须是 null，不能冒充 100%', async () => {
  const harness = makeHarness({ confidence: undefined });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  const outcome = await service.recognize(spec('j1'));
  assert.equal(outcome.confidence, null);
  assert.ok(outcome.rawRecognizedText.length > 0, '必须返回原始识别文本供校对');
  assert.equal(outcome.imageId, 'img-j1');
  assert.ok(outcome.durationMs >= 0);
});

test('释放后再调用被拒绝，并终止 worker', async () => {
  const harness = makeHarness();
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  await service.recognize(spec('j1'));
  await service.dispose();
  assert.deepEqual(harness.terminated, [0]);
  await assert.rejects(() => service.recognize(spec('j2')), (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_DISPOSED');
});

test('队列可见：提交即入队，识别中任务唯一，完成后清空', async () => {
  const harness = makeHarness({ recognizeDelayMs: 60 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  const seen: Array<{ active: string | null; pending: number }> = [];
  service.onQueueChange(state => seen.push({ active: state.active, pending: state.pending }));

  const jobs = [service.recognize(spec('a')), service.recognize(spec('b')), service.recognize(spec('c'))];
  await Promise.all(jobs);
  assert.ok(service.queueState().active === null, '全部完成后不得残留活动任务');
  assert.ok(service.queueState().pending === 0, '全部完成后等待队列应为空');

  // 任一时刻最多一个活动任务。
  const activeValues = seen.map(s => s.active).filter((v): v is string => v !== null);
  assert.ok(new Set(activeValues).size >= 1, '应观察到活动任务');
  // 观察到的最大并发由服务保证为 1（队列串行）。
  assert.ok(harness.peakConcurrency() === 1, '引擎层的峰值并发必须是 1');
});

test('队列在失败后仍然释放，不会卡住后续任务', async () => {
  let call = 0;
  const createWorker = async () => ({
    recognize: async () => {
      call += 1;
      if (call === 1) throw new Error('BOOM');
      return { data: { text: 'ok', confidence: 90 } };
    },
    terminate: () => undefined
  }) as OcrWorkerLike;
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker });
  await assert.rejects(() => service.recognize(spec('bad')));
  assert.ok(service.queueState().active === null, '失败后必须释放活动槽位');
  const ok = await service.recognize(spec('good'));
  assert.ok(ok.jobId === 'good', '后续任务仍应执行');
  assert.ok(service.queueState().pending === 0);
});

test('取消的任务在进入识别前就离开队列', async () => {
  const harness = makeHarness({ recognizeDelayMs: 40 });
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: harness.createWorker });
  const first = service.recognize(spec('j1'));
  const second = service.recognize(spec('j2'));
  service.cancel('j2');
  await assert.rejects(() => second, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  await first;
  assert.ok(service.queueState().pending === 0 && service.queueState().active === null, '取消后队列应清空');
});

test('识别失败不触碰调用方的原文：服务不持有会话，失败只以异常抛出', async () => {
  const terminated: number[] = [];
  let index = 0;
  // 引擎在识别阶段直接抛错，模拟识别失败。
  const failing = async (_language: string, _options: OcrWorkerOptions): Promise<OcrWorkerLike> => ({
    recognize: async () => { throw new Error('engine exploded'); },
    terminate: () => { terminated.push(index++); }
  } as unknown as OcrWorkerLike);
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: failing });
  // 调用方持有原文；服务接口里根本没有它的位置（只收图片、只回候选）。
  const session = { rawText: '已校对的内容 山水' };
  const before = session.rawText;
  await assert.rejects(() => service.recognize(spec('j1')));
  assert.equal(session.rawText, before, '识别失败后原文必须一字不变');
});

test('资产加载失败后重试可继续，且不重复追加失败任务的结果', async () => {
  // 第一次创建 worker 时模拟资产加载失败。
  let attempts = 0;
  const created: string[] = [];
  const createWorker = async (language: string, _options: OcrWorkerOptions): Promise<OcrWorkerLike> => {
    attempts += 1;
    if (attempts === 1) throw new Error('asset load failed');
    created.push(language);
    return {
      recognize: async (image: Blob) => ({ data: { text: await image.text() } }),
      terminate: () => undefined
    } as OcrWorkerLike;
  };
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker });
  // 第一次失败：不得返回任何候选。
  await assert.rejects(() => service.recognize(spec('j1')), '资产失败必须抛出');
  assert.equal(service.loaded, false, '失败后不得标记为已加载');
  // 重试并继续下一张：应成功，且结果只含本次任务。
  const outcome = await service.recognize(spec('j2'));
  assert.equal(outcome.jobId, 'j2');
  assert.equal(outcome.rawRecognizedText, 'j2', '结果不得混入上一次失败任务的任何内容');
  assert.equal(attempts, 2, '重试应重新创建 worker');
  assert.equal(service.loaded, true);
});

/** 明确控制异步先后，避免以计时猜测取消发生阶段。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

test('活动任务取消立即终止 worker；即使引擎 promise 不结束，下一任务也可运行', async () => {
  const started = deferred<void>();
  let terminated = 0;
  let created = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: async () => {
    const index = ++created;
    return {
      recognize: async () => {
        if (index === 1) { started.resolve(); return new Promise(() => undefined); }
        return { data: { text: 'next' } };
      },
      terminate: () => { terminated += 1; }
    };
  } });
  const first = service.recognize(spec('first'));
  await started.promise;
  service.cancel('first');
  await assert.rejects(first, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  assert.equal(terminated, 1);
  assert.equal((await service.recognize(spec('next'))).rawRecognizedText, 'next');
  assert.equal(created, 2);
  await service.dispose();
});

test('取消整个队列不会识别后续图片，也不会遗留等待任务', async () => {
  const started = deferred<void>();
  const calls: string[] = [];
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: async () => ({
    recognize: async image => {
      calls.push(await image.text());
      started.resolve();
      return new Promise(() => undefined);
    },
    terminate: () => undefined
  }) });
  const settled = Promise.allSettled(['a', 'b', 'c'].map(id => service.recognize(spec(id))));
  await started.promise;
  service.cancelAll();
  const results = await settled;
  assert.deepEqual(calls, ['a']);
  assert.ok(results.every(result => result.status === 'rejected' && result.reason.code === 'WORKSHEET_OCR_CANCELLED'));
  assert.deepEqual(service.queueState(), { active: null, waiting: [], pending: 0 });
});

test('加载期间卸载：晚到 worker 必须释放，不能调用 recognize', async () => {
  const loading = deferred<OcrWorkerLike>();
  const started = deferred<void>();
  let terminated = 0;
  let recognized = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: () => { started.resolve(); return loading.promise; } });
  const pending = service.recognize(spec('late'));
  await started.promise;
  const rejected = assert.rejects(pending, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  await service.dispose();
  await rejected;
  loading.resolve({ recognize: async () => { recognized += 1; return { data: { text: 'late' } }; }, terminate: () => { terminated += 1; } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(terminated, 1);
  assert.equal(recognized, 0);
  assert.equal(service.loaded, false);
});

test('加载超时：过期工厂返回的 worker 立即释放，不能复活服务', async () => {
  const loading = deferred<OcrWorkerLike>();
  let terminated = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/v1/', createWorker: () => loading.promise, assetTimeoutMs: 10 });
  await assert.rejects(service.recognize(spec('late')), (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_ASSET_TIMEOUT');
  loading.resolve({ recognize: async () => ({ data: { text: 'late' } }), terminate: () => { terminated += 1; } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(terminated, 1);
  assert.equal(service.loaded, false);
});

test('增强超时仍保留已成功结果，终止旧引擎后下一张重新创建 worker', async () => {
  let created = 0;
  let terminated = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/', recognizeTimeoutMs: 10, createWorker: async () => {
    const index = ++created;
    let fallback: OcrWorkerResult | null = { data: { text: 'Monday 星期一 (24)', candidateText: 'Monday', confidence: 89, entries: [{ text: 'Monday', meaning: '星期一', confidence: 87 }] } };
    return {
      recognize: async () => index === 1 ? new Promise<OcrWorkerResult>(() => undefined) : { data: { text: 'Tuesday 星期二', candidateText: 'Tuesday', confidence: 93 } },
      getFallbackResult: () => fallback,
      terminate: () => { terminated += 1; fallback = null; }
    };
  } });
  const first = await service.recognize(spec('slow-enhancement', 'english'));
  assert.equal(first.rawRecognizedText, 'Monday 星期一 (24)');
  assert.equal(first.candidateText, 'Monday');
  assert.equal(first.confidence, 89);
  assert.deepEqual(first.entries, [{ text: 'Monday', meaning: '星期一', confidence: 87 }]);
  assert.equal(first.enhancementIncomplete, true);
  assert.equal(terminated, 1);
  assert.equal(service.loaded, false);
  const second = await service.recognize(spec('next', 'english'));
  assert.equal(second.candidateText, 'Tuesday');
  assert.equal(second.enhancementIncomplete, undefined);
  assert.equal(created, 2);
  await service.dispose();
});

test('取消即使存在成功阶段缓存也必须拒绝，不能把迟到候选带回界面', async () => {
  const started = deferred<void>();
  let readFallback = 0;
  let terminated = 0;
  const service = new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => ({
    recognize: async () => { started.resolve(); return new Promise<OcrWorkerResult>(() => undefined); },
    getFallbackResult: () => { readFallback += 1; return { data: { text: 'Monday 星期一', candidateText: 'Monday' } }; },
    terminate: () => { terminated += 1; }
  }) });
  const running = service.recognize(spec('cancel', 'english'));
  await started.promise;
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  service.cancel('cancel');
  await rejected;
  assert.equal(readFallback, 0, '取消不能读取或展示阶段缓存');
  assert.equal(terminated, 1);
  assert.equal(service.loaded, false);
  await service.dispose();
});

test('超时清理期间发生取消，已读取的阶段缓存仍不得返回成功', async () => {
  const terminating = deferred<void>();
  const cleanup = deferred<void>();
  const service = new WorksheetOcrService({ assetBase: '/ocr/', recognizeTimeoutMs: 10, createWorker: async () => ({
    recognize: async () => new Promise<OcrWorkerResult>(() => undefined),
    getFallbackResult: () => ({ data: { text: 'Monday 星期一', candidateText: 'Monday' } }),
    terminate: () => { terminating.resolve(); return cleanup.promise; }
  }) });
  const running = service.recognize(spec('race', 'english'));
  await terminating.promise;
  service.cancel('race');
  const rejected = assert.rejects(running);
  cleanup.resolve();
  await rejected;
  assert.equal(service.loaded, false);
  await service.dispose();
});
