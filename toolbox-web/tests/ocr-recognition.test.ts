import assert from 'node:assert/strict';
import test from 'node:test';
import type { OcrTextLayout, OcrTextLine } from '../src/domain/worksheet-ocr-text';
import type { PreparedOcrInkImage } from '../src/lib/ocr-ink';
import { createLayoutOcrWorker, type LayoutOcrWorker } from '../src/services/ocr-recognition';
import { OcrError, WorksheetOcrService } from '../src/services/worksheet-ocr';

/** 精确控制引擎结果与取消的先后，避免依赖睡眠计时。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

/** 构造带独立词坐标的行，以验证增强图恢复后的几何匹配。 */
function line(text: string, y: number, confidence: number, scaleX = 1, scaleY = 1): OcrTextLine {
  const parts = text.split(' ');
  const words = parts.map((word, index) => ({
    text: word, confidence,
    bbox: { x0: (20 + index * 70) * scaleX, x1: (80 + index * 70) * scaleX, y0: y * scaleY, y1: (y + 24) * scaleY }
  }));
  return {
    text, confidence, words,
    bbox: { x0: 20 * scaleX, x1: (80 + (parts.length - 1) * 70) * scaleX, y0: y * scaleY, y1: (y + 24) * scaleY }
  };
}

/** 每次识别的原图全文都保留，即使词条候选后来由增强图修正。 */
test('原图与缩放增强图按词坐标合并，保留黑色英文、短语和原始全文', async () => {
  const original = new Blob(['original']);
  const ink = new Blob(['ink']);
  const originalText = 'Monclay 星期一 (24)\nhomework 家庭作业 (26)\nafter school 放学后 (28)';
  const calls: Array<{ image: Blob; mode: string; output: unknown }> = [];
  let mode = '';
  const engine: LayoutOcrWorker = {
    setParameters: async params => { mode = params.tessedit_pageseg_mode; },
    recognize: async (image, _options, output) => {
      calls.push({ image, mode, output });
      return { data: image === original ? {
        text: originalText, confidence: 77,
        lines: [line('Monclay', 20, 60), line('homework', 80, 96), line('after school', 140, 70)]
      } : {
        text: 'Monday\nafter school', confidence: 98,
        lines: [line('Monday', 20, 95, 0.5, 0.25), line('after school', 140, 93, 0.5, 0.25)]
      } };
    },
    terminate: () => undefined
  };
  const worker = createLayoutOcrWorker(engine, true, async image => {
    assert.equal(image, original);
    return [{ image: ink, scaleX: 0.5, scaleY: 0.25 }];
  });
  const result = await worker.recognize(original);
  assert.equal(result.data.text, originalText, '增强的局部文本不能替换原图全文');
  assert.equal(result.data.candidateText, 'Monday\nhomework\nafter school', '缩放回原图后同一词只出现一次，黑色词不丢失，短语保留空格');
  assert.equal(result.data.confidence, 93, '整体置信度取候选最低行，而非包含中文的原图均分');
  assert.deepEqual(calls, [
    { image: original, mode: '6', output: { text: true, blocks: true } },
    { image: ink, mode: '3', output: { text: true, blocks: true } }
  ]);
});

test('语文原样返回全文与置信度，不运行英文版面设置和墨迹增强', async () => {
  const original = new Blob(['chinese']);
  let enhanced = 0;
  let configured = 0;
  let called = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => { configured += 1; },
    recognize: async (image, options, output) => {
      called += 1;
      assert.equal(image, original);
      assert.equal(options, undefined);
      assert.equal(output, undefined);
      return { data: { text: '山川 河流\n风景', confidence: 92 } };
    },
    terminate: () => undefined
  }, false, async () => { enhanced += 1; return []; });
  assert.deepEqual((await worker.recognize(original)).data, { text: '山川 河流\n风景', confidence: 92 });
  assert.equal(called, 1);
  assert.equal(configured, 0);
  assert.equal(enhanced, 0);
});

test('增强预处理失败仍返回原图提取结果，不伪造或丢失原始全文', async () => {
  let calls = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => { calls += 1; return { data: { text: 'Monday 星期一 (24)\nafter school 放学后 (28)', confidence: 86 } }; },
    terminate: () => undefined
  }, true, async () => { throw new Error('Canvas unavailable'); });
  const result = await worker.recognize(new Blob(['original']));
  assert.equal(result.data.text, 'Monday 星期一 (24)\nafter school 放学后 (28)');
  assert.equal(result.data.candidateText, 'Monday\nafter school');
  assert.equal(result.data.confidence, 86);
  assert.equal(calls, 1);
});

test('同一引擎连续识别多图时，每张原图重新设置 PSM 6，增强图使用 PSM 3', async () => {
  const modes: string[] = [];
  const observed: Array<{ text: string; mode: string }> = [];
  let mode = '';
  const worker = createLayoutOcrWorker({
    setParameters: async params => { mode = params.tessedit_pageseg_mode; modes.push(mode); },
    recognize: async image => {
      const text = await image.text();
      observed.push({ text, mode });
      return { data: { text: 'apple 苹果', confidence: 90, lines: [line('apple', 20, 90)] } };
    },
    terminate: () => undefined
  }, true, async image => [{ image: new Blob([`${await image.text()}-ink`]), scaleX: 1, scaleY: 1 }]);
  await worker.recognize(new Blob(['first']));
  await worker.recognize(new Blob(['second']));
  assert.deepEqual(modes, ['6', '3', '6', '3']);
  assert.deepEqual(observed, [
    { text: 'first', mode: '6' }, { text: 'first-ink', mode: '3' },
    { text: 'second', mode: '6' }, { text: 'second-ink', mode: '3' }
  ]);
});

test('原图识别期间 terminate 后丢弃迟到结果，不能进入增强阶段', async () => {
  const pending = deferred<{ data: OcrTextLayout }>();
  const started = deferred<void>();
  let calls = 0;
  let prepared = 0;
  let terminated = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => { calls += 1; started.resolve(); return pending.promise; },
    terminate: () => { terminated += 1; }
  }, true, async () => { prepared += 1; return []; });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  await worker.terminate();
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  pending.resolve({ data: { text: 'late original', confidence: 90 } });
  await rejected;
  assert.equal(calls, 1);
  assert.equal(prepared, 0);
  assert.equal(terminated, 1);
  await assert.rejects(worker.recognize(new Blob(['next'])), (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  assert.equal(calls, 1, '已释放适配器拒绝后续图像');
});

test('增强识别期间 terminate 后丢弃迟到结果，不启动后续增强图或回退成功', async () => {
  const pending = deferred<{ data: OcrTextLayout }>();
  const started = deferred<void>();
  const calls: string[] = [];
  let isCancelled: (() => boolean) | undefined;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async image => {
      const input = await image.text();
      calls.push(input);
      if (input === 'original') return { data: { text: 'apple 苹果', confidence: 80, lines: [line('apple', 20, 80)] } };
      started.resolve();
      return pending.promise;
    },
    terminate: () => undefined
  }, true, async (_image, cancelled) => {
    isCancelled = cancelled;
    return [
      { image: new Blob(['first-ink']), scaleX: 1, scaleY: 1 },
      { image: new Blob(['never-ink']), scaleX: 1, scaleY: 1 }
    ];
  });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  assert.equal(isCancelled?.(), false);
  await worker.terminate();
  assert.equal(isCancelled?.(), true, '预处理收到的取消回调反映释放状态');
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  pending.resolve({ data: { text: 'late enhanced', confidence: 99 } });
  await rejected;
  assert.deepEqual(calls, ['original', 'first-ink']);
});

test('预处理等待期间 terminate 后返回的增强图不能启动识别', async () => {
  const pending = deferred<PreparedOcrInkImage[]>();
  const started = deferred<void>();
  const modes: string[] = [];
  let calls = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async params => { modes.push(params.tessedit_pageseg_mode); },
    recognize: async () => { calls += 1; return { data: { text: 'apple 苹果', confidence: 90 } }; },
    terminate: () => undefined
  }, true, () => { started.resolve(); return pending.promise; });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  await worker.terminate();
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  pending.resolve([{ image: new Blob(['late ink']), scaleX: 1, scaleY: 1 }]);
  await rejected;
  assert.equal(calls, 1);
  assert.deepEqual(modes, ['6']);
});

/** 原图词行包含可用于首阶段配对的中文词框。 */
function bilingualLine(text = 'Monday', meaning = '星期一', confidence = 92): OcrTextLine {
  const english = line(text, 20, confidence);
  const chinese = { text: meaning, confidence, bbox: { x0: 160, y0: 20, x1: 260, y1: 44 } };
  return { ...english, text: `${text} ${meaning}`, bbox: { ...english.bbox, x1: 260 }, words: [...english.words!, chinese] };
}

test('中文专用阶段按缩放坐标补齐释义，保留增强后的英文短语', async () => {
  const calls: Array<{ image: string; language: string; mode: string }> = [];
  let language = 'english';
  let mode = '';
  const worker = createLayoutOcrWorker({
    setParameters: async params => { mode = params.tessedit_pageseg_mode; },
    recognize: async image => {
      const input = await image.text();
      calls.push({ image: input, language, mode });
      if (input === 'original') return { data: { text: 'Monclay 星期一', lines: [line('Monclay', 20, 62)] } };
      if (input === 'ink') return { data: { text: 'Monday', lines: [line('Monday', 20, 95)] } };
      return { data: { text: '星期一 (24)', lines: [{ text: '星期一 (24)', confidence: 94, bbox: { x0: 80, y0: 5, x1: 130, y1: 11 } }] } };
    },
    terminate: () => undefined
  }, true, async () => [{ image: new Blob(['ink']), scaleX: 1, scaleY: 1 }], {
    prepareImage: async (image, english, cancelled) => {
      assert.equal(await image.text(), 'original');
      assert.equal(cancelled(), false);
      assert.equal(english[0]!.text, 'Monday', '中文去字掩膜应使用已经增强的英文框');
      return { image: new Blob(['meaning']), scaleX: 0.5, scaleY: 0.25 };
    },
    selectLanguage: async selected => { language = selected; }
  });
  const result = await worker.recognize(new Blob(['original']));
  assert.equal(result.data.text, 'Monclay 星期一');
  assert.equal(result.data.candidateText, 'Monday');
  assert.deepEqual(result.data.entries, [{ text: 'Monday', meaning: '星期一', confidence: 94 }]);
  assert.deepEqual(calls, [
    { image: 'original', language: 'english', mode: '6' },
    { image: 'ink', language: 'english', mode: '3' },
    { image: 'meaning', language: 'chinese', mode: '6' }
  ]);
});

test('连续两张图片恢复英文语言，中文阶段仍只识别一遍并复用同一引擎', async () => {
  const switches: string[] = [];
  const calls: string[] = [];
  let language = 'english';
  let mode = '';
  let terminated = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async params => { mode = params.tessedit_pageseg_mode; },
    recognize: async image => {
      const input = await image.text();
      calls.push(`${input}:${language}:${mode}`);
      return input.endsWith('-meaning')
        ? { data: { text: '星期一', lines: [{ text: '星期一', confidence: 93, bbox: { x0: 150, y0: 20, x1: 250, y1: 44 } }] } }
        : { data: { text: 'Monday', lines: [line('Monday', 20, 95)] } };
    },
    terminate: () => { terminated += 1; }
  }, true, async () => [], {
    prepareImage: async image => ({ image: new Blob([`${await image.text()}-meaning`]), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => { switches.push(selected); language = selected; }
  });
  for (const image of ['first', 'second']) {
    const result = await worker.recognize(new Blob([image]));
    assert.equal(result.data.entries?.[0]!.meaning, '星期一');
  }
  assert.deepEqual(switches, ['chinese', 'english', 'chinese']);
  assert.deepEqual(calls, ['first:english:6', 'first-meaning:chinese:6', 'second:english:6', 'second-meaning:chinese:6']);
  await Promise.all([worker.terminate(), worker.terminate()]);
  assert.equal(terminated, 1, '取消和清理都调用释放时，底层引擎仍只释放一次');
  assert.equal(worker.getFallbackResult?.(), null);
});

test('中文识别异常仍保留原图中英对应，下一张恢复英文后正常识别', async () => {
  const switches: string[] = [];
  let language = 'english';
  let originalCalls = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => {
      if (language === 'chinese') throw new Error('Meaning recognition failed');
      originalCalls += 1;
      return { data: { text: 'Monday 星期一', lines: [bilingualLine()] } };
    },
    terminate: () => undefined
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => { switches.push(selected); language = selected; }
  });
  const first = await worker.recognize(new Blob(['first']));
  assert.deepEqual(first.data.entries, [{ text: 'Monday', meaning: '星期一', confidence: 92 }]);
  await worker.recognize(new Blob(['second']));
  assert.equal(originalCalls, 2);
  assert.deepEqual(switches, ['chinese', 'english', 'chinese']);
});

test('中文语言初始化失败后状态视为未知，下一张先恢复英文再识别', async () => {
  const calls: string[] = [];
  let fail = true;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async image => { calls.push(`recognize:${await image.text()}`); return { data: { text: 'Monday 星期一', lines: [bilingualLine()] } }; },
    terminate: () => undefined
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => {
      calls.push(`language:${selected}`);
      if (selected === 'chinese' && fail) { fail = false; throw new Error('Initialization failed'); }
    }
  });
  assert.equal((await worker.recognize(new Blob(['first']))).data.entries?.[0]!.meaning, '星期一');
  await worker.recognize(new Blob(['second']));
  assert.deepEqual(calls, [
    'recognize:first', 'language:chinese', 'language:english', 'recognize:second', 'language:chinese', 'recognize:meaning'
  ]);
});

test('中文预处理期间取消后，不再切语言或启动识别，且清空过期阶段缓存', async () => {
  const pending = deferred<PreparedOcrInkImage | null>();
  const started = deferred<void>();
  let cancelled: (() => boolean) | undefined;
  let switches = 0;
  let calls = 0;
  let terminated = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => { calls += 1; return { data: { text: 'Monday 星期一', lines: [bilingualLine()] } }; },
    terminate: () => { terminated += 1; }
  }, true, async () => [], {
    prepareImage: async (_image, _english, isCancelled) => { cancelled = isCancelled; started.resolve(); return pending.promise; },
    selectLanguage: async () => { switches += 1; }
  });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  assert.equal(worker.getFallbackResult?.()?.data.entries?.[0]!.meaning, '星期一');
  await worker.terminate();
  assert.equal(cancelled?.(), true);
  assert.equal(worker.getFallbackResult?.(), null);
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  pending.resolve({ image: new Blob(['late']), scaleX: 1, scaleY: 1 });
  await rejected;
  assert.equal(switches, 0);
  assert.equal(calls, 1);
  assert.equal(terminated, 1);
});

test('切换中文期间取消后，不设置版面或识别迟到图像', async () => {
  const switching = deferred<void>();
  const started = deferred<void>();
  const modes: string[] = [];
  let calls = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async params => { modes.push(params.tessedit_pageseg_mode); },
    recognize: async () => { calls += 1; return { data: { text: 'Monday', lines: [line('Monday', 20, 92)] } }; },
    terminate: () => undefined
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: () => { started.resolve(); return switching.promise; }
  });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  await worker.terminate();
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  switching.resolve();
  await rejected;
  assert.equal(calls, 1);
  assert.deepEqual(modes, ['6']);
});

test('中文识别期间取消后丢弃迟到配对，不留下可复用的缓存', async () => {
  const pending = deferred<{ data: OcrTextLayout }>();
  const started = deferred<void>();
  let language = 'english';
  let terminated = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => {
      if (language === 'english') return { data: { text: 'Monday 星期一', lines: [bilingualLine()] } };
      started.resolve();
      return pending.promise;
    },
    terminate: () => { terminated += 1; }
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => { language = selected; }
  });
  const running = worker.recognize(new Blob(['original']));
  await started.promise;
  await worker.terminate();
  const rejected = assert.rejects(running, (error: unknown) => error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED');
  pending.resolve({ data: { text: '迟到的中文', lines: [{ text: '迟到的中文', confidence: 99, bbox: { x0: 160, y0: 20, x1: 280, y1: 44 } }] } });
  await rejected;
  assert.equal(terminated, 1);
  assert.equal(worker.getFallbackResult?.(), null);
});

test('中文阶段沿用服务总预算，超时返回已成功的中英配对并释放引擎', async () => {
  let language = 'english';
  let terminated = 0;
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => language === 'english'
      ? { data: { text: 'Monday 星期一', lines: [bilingualLine()] } }
      : new Promise(() => undefined),
    terminate: () => { terminated += 1; }
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => { language = selected; }
  });
  const service = new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => worker, recognizeTimeoutMs: 15 });
  const result = await service.recognize({ jobId: 'one-budget', imageId: 'image', inputRevision: 1, language: 'english', image: new Blob(['original']) });
  assert.equal(result.enhancementIncomplete, true);
  assert.deepEqual(result.entries, [{ text: 'Monday', meaning: '星期一', confidence: 92 }]);
  assert.equal(terminated, 1);
  assert.equal(service.loaded, false);
  assert.equal(worker.getFallbackResult?.(), null);
  await service.dispose();
});

test('原图和中文阶段合并后保留中间漏识别英文的中文行，供后续自动翻译而不串行', async () => {
  let language = 'english';
  const english = [line('Monday', 20, 94), line('Wednesday', 120, 94), line('Thursday', 170, 94)];
  const chinese = ['星期一', '星期二', '星期三', '星期四'].map((text, index) => ({ text, confidence: 94, bbox: { x0: 160, y0: 20 + index * 50, x1: 240, y1: 44 + index * 50 } }));
  const worker = createLayoutOcrWorker({
    setParameters: async () => undefined,
    recognize: async () => language === 'english'
      ? { data: { text: 'Monday\nWednesday\nThursday', lines: english } }
      : { data: { text: chinese.map(item => item.text).join('\n'), lines: chinese } },
    terminate: () => undefined
  }, true, async () => [], {
    prepareImage: async () => ({ image: new Blob(['meaning']), scaleX: 1, scaleY: 1 }),
    selectLanguage: async selected => { language = selected; }
  });
  const result = await worker.recognize(new Blob(['original']));
  assert.deepEqual(result.data.entries?.map(({ text, meaning }) => ({ text, meaning })), [
    { text: 'Monday', meaning: '星期一' }, { text: '', meaning: '星期二' },
    { text: 'Wednesday', meaning: '星期三' }, { text: 'Thursday', meaning: '星期四' }
  ]);
  assert.equal(result.data.text, 'Monday\nWednesday\nThursday', '原图原始文本不能被自动补全伪造');
  assert.deepEqual(worker.getFallbackResult?.()?.data.entries, result.data.entries);
  await worker.terminate();
  assert.equal(worker.getFallbackResult?.(), null);
});
