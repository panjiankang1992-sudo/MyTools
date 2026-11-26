import assert from 'node:assert/strict';
import test from 'node:test';
import { createWorksheetImageSession } from '../src/services/worksheet-image-session';
import { WorksheetOcrService, type OcrWorkerResult } from '../src/services/worksheet-ocr';

/** 可控异步阶段。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

/** 带真实 PNG 签名的文件，由注入解码器提供可追踪的位图。 */
function file(name: string): File {
  return new File([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]), name], name, { type: 'image/png' });
}

/** 持有资源与实际送入引擎的图像记录。 */
function harness() {
  const bitmaps: Array<ImageBitmap & { name: string; closed: number }> = [];
  const encoded: Array<{ name: string; orientation: number; crop: unknown }> = [];
  let revision = 1;
  const deps = {
    decode: async (image: File) => {
      const bitmap = { name: image.name, width: 30, height: 20, closed: 0, close() { this.closed += 1; } } as ImageBitmap & { name: string; closed: number };
      bitmaps.push(bitmap);
      return bitmap;
    },
    encode: async (bitmap: ImageBitmap, orientation: number, crop: unknown) => {
      const record = { name: (bitmap as ImageBitmap & { name: string }).name, orientation, crop };
      encoded.push(record);
      return new Blob([record.name]);
    },
    createService: () => new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => ({ recognize: async image => ({ data: { text: await image.text() } }), terminate: () => undefined }) }),
    inputRevision: () => revision,
    language: () => 'chinese' as const
  };
  return { deps, bitmaps, encoded, changeRevision: () => { revision += 1; } };
}

test('选满五张后移除：队列和额度清空，位图释放，相同五张可重新选择', async () => {
  const h = harness();
  const session = createWorksheetImageSession(h.deps);
  const files = Array.from({ length: 5 }, (_, index) => file(`${index}.png`));
  await session.addFiles(files);
  assert.equal(session.items.value.length, 5);
  session.changeSelection(90, { x: 0, y: 0, width: 10, height: 10 });
  session.reset();
  assert.equal(session.items.value.length, 0);
  assert.equal(session.preview.value, null);
  assert.equal(session.selected.value, null);
  assert.equal(session.candidate.value, '');
  assert.equal(session.recognizing.value, false);
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
  await session.addFiles(files);
  assert.equal(session.items.value.length, 5);
  // 异步动作会更改计算值，用数组快照验证所选项，避免沿用 reset 后的空值收窄。
  const restoredItem = session.items.value[0]!;
  assert.equal(session.selected.value, restoredItem);
  assert.equal(restoredItem.orientation, 0);
  assert.equal(restoredItem.crop, null);
  session.dispose();
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
});

test('多图分别保持方向和选区，重试首图也必须读取首图而非当前预览图', async () => {
  const h = harness();
  const session = createWorksheetImageSession(h.deps);
  await session.addFiles([file('first.png'), file('second.png')]);
  const first = session.items.value[0]!.id;
  const second = session.items.value[1]!.id;
  session.changeSelection(90, { x: 1, y: 2, width: 10, height: 11 });
  await session.select(second);
  session.changeSelection(270, { x: 3, y: 4, width: 12, height: 13 });
  await session.recognize();
  assert.deepEqual(h.encoded, [
    { name: 'first.png', orientation: 90, crop: { x: 1, y: 2, width: 10, height: 11 } },
    { name: 'second.png', orientation: 270, crop: { x: 3, y: 4, width: 12, height: 13 } }
  ]);
  assert.equal(session.candidate.value, 'first.png\nsecond.png');
  assert.deepEqual(session.items.value.map(item => item.rawText), ['first.png', 'second.png'], '未提供候选时兼容旧引擎全文');
  await session.select(first);
  session.changeSelection(180, null);
  assert.equal(session.items.value[0]!.rawText, '', '旋转后清除旧全文');
  assert.equal(session.items.value[0]!.text, '', '旋转后同时清除旧候选');
  await session.select(second);
  await session.recognize();
  assert.equal(h.encoded.at(-1)?.name, 'first.png');
  assert.equal(h.encoded.at(-1)?.orientation, 180);
  session.dispose();
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
});

test('取消编码中的批次：不启动 OCR、不继续后续图，晚到编码不能追加候选', async () => {
  const h = harness();
  const encoding = deferred<Blob>();
  const entered = deferred<void>();
  let services = 0;
  const session = createWorksheetImageSession({ ...h.deps, encode: async () => { entered.resolve(); return encoding.promise; }, createService: () => { services += 1; return h.deps.createService(); } });
  await session.addFiles([file('first.png'), file('second.png')]);
  const running = session.recognize();
  await entered.promise;
  session.cancel();
  assert.equal(session.status.value, '已取消本次识别，原文与图片都保留。');
  assert.equal(session.items.value.length, 2, '暂停保留来源，供返回编辑');
  encoding.resolve(new Blob(['stale']));
  await running;
  assert.equal(services, 0);
  assert.equal(session.candidate.value, '');
  assert.ok(session.items.value.every(item => item.status === 'waiting'));
  assert.equal(session.recognizing.value, false);
  session.dispose();
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
});

test('识别中移除后选择新图：旧引擎结果不能污染新队列或候选', async () => {
  const h = harness();
  const result = deferred<{ data: { text: string } }>();
  const entered = deferred<void>();
  let calls = 0;
  let terminated = 0;
  const session = createWorksheetImageSession({ ...h.deps, createService: () => new WorksheetOcrService({ assetBase: '/ocr/', createWorker: async () => ({
    recognize: async image => { calls += 1; if (calls === 1) { entered.resolve(); return result.promise; } return { data: { text: await image.text() } }; },
    terminate: () => { terminated += 1; }
  }) }) });
  await session.addFiles([file('old.png'), file('never.png')]);
  const running = session.recognize();
  await entered.promise;
  session.reset();
  await session.addFiles([file('new.png')]);
  await session.recognize();
  result.resolve({ data: { text: 'stale' } });
  await running;
  assert.equal(session.candidate.value, 'new.png');
  assert.equal(session.items.value[0]!.rawText, 'new.png', '晚到全文不得污染新图片');
  assert.deepEqual(session.items.value.map(item => item.name), ['new.png']);
  assert.equal(calls, 2);
  assert.equal(terminated, 1);
  assert.equal(session.recognizing.value, false);
  session.dispose();
});

test('移除发生在文件解码中：晚到位图立即释放，不恢复队列和预览', async () => {
  const h = harness();
  const decoding = deferred<ImageBitmap>();
  const entered = deferred<void>();
  const bitmap = await h.deps.decode(file('late.png'));
  const session = createWorksheetImageSession({ ...h.deps, decode: () => { entered.resolve(); return decoding.promise; } });
  const adding = session.addFiles([file('late.png')]);
  await entered.promise;
  session.reset();
  decoding.resolve(bitmap);
  await adding;
  assert.equal(h.bitmaps[0]!.closed, 1);
  assert.equal(session.items.value.length, 0);
  assert.equal(session.preview.value, null);
  assert.equal(session.picking.value, false);
});

test('原文修订变化会丢弃未完成识别，并保留可重试图像', async () => {
  const h = harness();
  const encoding = deferred<Blob>();
  const entered = deferred<void>();
  const session = createWorksheetImageSession({ ...h.deps, encode: () => { entered.resolve(); return encoding.promise; } });
  await session.addFiles([file('source.png')]);
  const running = session.recognize();
  await entered.promise;
  h.changeRevision();
  encoding.resolve(new Blob(['stale']));
  await running;
  assert.equal(session.candidate.value, '');
  assert.equal(session.items.value[0]!.status, 'waiting');
  assert.equal(session.recognizing.value, false);
  session.dispose();
});

test('先切后到的预览立即关闭，不得覆盖当前选择的图片', async () => {
  const h = harness();
  const delayed = deferred<ImageBitmap>();
  let shouldDelay = false;
  const session = createWorksheetImageSession({ ...h.deps, decode: image => shouldDelay && image.name === 'second.png' ? delayed.promise : h.deps.decode(image) });
  await session.addFiles([file('first.png'), file('second.png')]);
  const first = session.items.value[0]!.id;
  const second = session.items.value[1]!.id;
  const lateBitmap = await h.deps.decode(file('second.png'));
  shouldDelay = true;
  const latePreview = session.select(second);
  await session.select(first);
  delayed.resolve(lateBitmap);
  await latePreview;
  assert.equal(session.selected.value?.id, first);
  assert.equal((session.preview.value as ImageBitmap & { name: string }).name, 'first.png');
  assert.equal((lateBitmap as ImageBitmap & { closed: number }).closed, 1);
  session.dispose();
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
});

test('多图提取词条按行汇总，编辑候选与离开步骤不改变每张图片的原始全文', async () => {
  const h = harness();
  const sources = {
    'first.png': { text: 'Monday 星期一 (24)\nTuesday 星期二 (24)', candidateText: 'Monday\nTuesday', confidence: 95 },
    'second.png': { text: 'after school 放学后 (28)', candidateText: 'after school', confidence: 91 }
  };
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({
      recognize: async image => ({ data: sources[await image.text() as keyof typeof sources] }),
      terminate: () => undefined
    })
  }) });
  await session.addFiles([file('first.png'), file('second.png')]);
  await session.recognize();
  assert.equal(session.candidate.value, 'Monday\nTuesday\nafter school', '只有词条进入可编辑候选，短语内部空格保留');
  assert.deepEqual(session.items.value.map(item => item.rawText), [sources['first.png'].text, sources['second.png'].text]);
  session.candidate.value = 'Monday\nafter school';
  session.cancel();
  assert.equal(session.candidate.value, 'Monday\nafter school');
  assert.deepEqual(session.items.value.map(item => item.rawText), [sources['first.png'].text, sources['second.png'].text], '编辑与空闲取消都不得覆盖只读全文');
  session.reset();
  assert.equal(session.items.value.length, 0);
  assert.equal(session.candidate.value, '');
  session.dispose();
});

test('过滤未得到词条时保留全文供核对，不把释义页码重新混入候选', async () => {
  const h = harness();
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({
      recognize: async () => ({ data: { text: '星期一 (24)', candidateText: '', confidence: 40 } }),
      terminate: () => undefined
    })
  }) });
  await session.addFiles([file('source.png')]);
  await session.recognize();
  assert.equal(session.candidate.value, '');
  assert.equal(session.items.value[0]!.status, 'done');
  assert.equal(session.items.value[0]!.rawText, '星期一 (24)');
  assert.match(session.items.value[0]!.message, /原始识别文字/);
  session.changeSelection(90, null);
  assert.equal(session.items.value[0]!.rawText, '');
  assert.equal(session.items.value[0]!.text, '');
  session.dispose();
});

test('旧批次的 finally 不得取消后启动批次的 busy 状态', async () => {
  const h = harness();
  const first = deferred<Blob>();
  const second = deferred<Blob>();
  const firstStarted = deferred<void>();
  const secondStarted = deferred<void>();
  let calls = 0;
  const session = createWorksheetImageSession({ ...h.deps, encode: () => {
    calls += 1;
    if (calls === 1) { firstStarted.resolve(); return first.promise; }
    secondStarted.resolve();
    return second.promise;
  } });
  await session.addFiles([file('one.png')]);
  const firstRun = session.recognize();
  await firstStarted.promise;
  session.cancel();
  const secondRun = session.recognize();
  await secondStarted.promise;
  first.resolve(new Blob(['old']));
  await firstRun;
  assert.equal(session.recognizing.value, true);
  assert.equal(session.items.value[0]!.status, 'running');
  second.resolve(new Blob(['current']));
  await secondRun;
  assert.equal(session.candidate.value, 'current');
  assert.equal(session.recognizing.value, false);
  session.dispose();
});

test('暂离来源步骤只取消任务，已校对候选与图片设置均保留', async () => {
  const h = harness();
  const session = createWorksheetImageSession(h.deps);
  await session.addFiles([file('source.png')]);
  session.changeSelection(270, { x: 1, y: 1, width: 10, height: 10 });
  await session.recognize();
  session.candidate.value = 'edited candidate';
  session.cancel();
  assert.equal(session.candidate.value, 'edited candidate');
  assert.equal(session.items.value[0]!.orientation, 270);
  assert.deepEqual(session.items.value[0]!.crop, { x: 1, y: 1, width: 10, height: 10 });
  assert.ok(session.preview.value);
  session.dispose();
  assert.equal(session.items.value.length, 0);
  assert.equal(session.preview.value, null);
  assert.equal(session.candidate.value, '');
  assert.ok(h.bitmaps.every(bitmap => bitmap.closed === 1));
});


test('空闲切换来源或步骤不会伪报取消，也不会覆盖已有正常提示', async () => {
  const h = harness();
  const session = createWorksheetImageSession(h.deps);
  session.cancel();
  assert.equal(session.status.value, '', '首次进入图片来源前没有任务，不显示取消提示');
  await session.addFiles([file('source.png')]);
  const selectedNotice = session.status.value;
  session.cancel();
  assert.equal(session.status.value, selectedNotice, '选图后尚未识别，保留加入图片的提示');
  await session.recognize();
  const finishedNotice = session.status.value;
  session.cancel();
  assert.equal(session.status.value, finishedNotice, '已完成识别的提示不能被空闲取消覆盖');
  assert.equal(session.candidate.value, 'source.png');
  session.dispose();
});

test('增强超时的成功阶段仍可进入候选，同时提示用户核对', async () => {
  const h = harness();
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', recognizeTimeoutMs: 10, createWorker: async () => ({
      recognize: async () => new Promise(() => undefined),
      getFallbackResult: () => ({ data: { text: 'Monday 星期一 (24)', candidateText: 'Monday', confidence: 90 } }),
      terminate: () => undefined
    })
  }) });
  await session.addFiles([file('source.png')]);
  await session.recognize();
  assert.equal(session.candidate.value, 'Monday');
  assert.equal(session.items.value[0]!.rawText, 'Monday 星期一 (24)');
  assert.equal(session.items.value[0]!.status, 'done');
  assert.equal(session.items.value[0]!.message, '已保留识别结果，增强未完成，请核对。');
  assert.equal(session.recognizing.value, false);
  session.dispose();
});

test('多张词表图片中英按原始顺序逐行配对，重复词与空释义不向下一项借值', async () => {
  const h = harness();
  const first: OcrWorkerResult['data'] = { text: 'walk 牵着走\nwalk 散步\ndog', candidateText: 'walk\nwalk\ndog', entries: [
    { text: 'walk', meaning: '牵着走', confidence: 92 }, { text: 'walk', meaning: '散步' }, { text: 'dog', meaning: '' }
  ] };
  const second: OcrWorkerResult['data'] = { text: 'free 空闲的\nSee you tomorrow! 明天见！', candidateText: 'free\nSee you tomorrow!', entries: [
    { text: 'free', meaning: '空闲的' }, { text: 'See you tomorrow!', meaning: '明天见！' }
  ] };
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async image => ({ data: await image.text() === 'first.png' ? first : second }), terminate: () => undefined })
  }) });
  await session.addFiles([file('first.png'), file('second.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.english, row.meaning, row.meaningState]), [
    ['walk', '牵着走', 'suggested'], ['walk', '散步', 'suggested'], ['dog', '', 'missing'], ['free', '空闲的', 'suggested'], ['See you tomorrow!', '明天见！', 'suggested']
  ]);
  assert.ok(session.candidatePairs.value?.every(row => row.englishState === 'suggested'));
  assert.deepEqual(session.items.value[0]!.entries, first.entries);
  assert.notEqual(session.items.value[0]!.entries![0], first.entries![0], '候选编辑不能反向修改引擎原始记录');
  assert.deepEqual(session.items.value.map(item => item.rawText), [first.text, second.text]);
  session.dispose();
});

test('配对候选手工校对与空闲取消均保留，重选选区或清空才清除候选', async () => {
  const h = harness();
  const original: OcrWorkerResult['data'] = { text: 'Weak 周，星期', candidateText: 'Weak', entries: [{ text: 'Weak', meaning: '周，星期' }] };
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => ({ data: original }), terminate: () => undefined })
  }) });
  await session.addFiles([file('source.png')]);
  await session.recognize();
  session.candidatePairs.value![0] = { ...session.candidatePairs.value![0], english: 'week', englishState: 'confirmed' };
  session.cancel();
  assert.equal(session.candidatePairs.value![0]!.english, 'week');
  assert.equal(session.candidatePairs.value![0]!.meaning, '周，星期');
  assert.equal(session.candidatePairs.value![0]!.meaningState, 'suggested');
  assert.equal(session.items.value[0]!.entries![0]!.text, 'Weak');
  assert.equal(session.items.value[0]!.rawText, 'Weak 周，星期');
  session.changeSelection(90, { x: 1, y: 1, width: 20, height: 12 });
  assert.equal(session.candidatePairs.value, null);
  assert.equal(session.items.value[0]!.entries, undefined);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value, [{ english: 'Weak', meaning: '周，星期', englishState: 'suggested', meaningState: 'suggested' }]);
  session.reset();
  assert.equal(session.candidatePairs.value, null);
  assert.equal(session.candidate.value, '');
  assert.equal(session.items.value.length, 0);
  session.dispose();
});

test('有配对与旧引擎无配对的图片混合时保留单边行，不错位拼接下一行', async () => {
  const h = harness();
  const sources: Record<string, OcrWorkerResult['data']> = {
    'paired.png': { text: 'Monday 星期一', candidateText: 'Monday', entries: [{ text: 'Monday', meaning: '星期一' }] },
    'legacy.png': { text: 'after school\n\n星期二\nWednesday', candidateText: 'after school\n\n星期二\nWednesday' }
  };
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async image => ({ data: sources[await image.text()]! }), terminate: () => undefined })
  }) });
  await session.addFiles([file('paired.png'), file('legacy.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.english, row.meaning]), [
    ['Monday', '星期一'], ['after school', ''], ['', '星期二'], ['Wednesday', '']
  ]);
  session.dispose();
});

test('旧引擎中英混排行只拆分明确同一行边界，中文提示不夹入英文答案或页码', async () => {
  const h = harness();
  const sources: Record<string, OcrWorkerResult['data']> = {
    'paired.png': { text: 'free 空闲的', entries: [{ text: 'free', meaning: '空闲的' }] },
    'legacy.png': { text: 'Monday 星期一 (24)\nat 在（某时间） (26)\nSee you tomorrow! 明天见！\n星期二 Tuesday (24)\nWednesday 星期三 Thursday 星期四\n(28)' }
  };
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async image => ({ data: sources[await image.text()]! }), terminate: () => undefined })
  }) });
  await session.addFiles([file('paired.png'), file('legacy.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.english, row.meaning]), [
    ['free', '空闲的'], ['Monday', '星期一'], ['at', '在（某时间）'], ['See you tomorrow!', '明天见！'], ['Tuesday', '星期二'], ['Wednesday 星期三 Thursday 星期四', '']
  ]);
  assert.ok(session.candidatePairs.value?.every(row => !/[A-Za-z]/u.test(row.meaning ?? '')));
  assert.equal(session.items.value[1]!.rawText, sources['legacy.png']!.text, '无法可靠拆分的内容仍保留在原始全文');
  session.dispose();
});

test('明确空候选不退回带页码的全文，旧引擎也统一使用空配对表', async () => {
  const h = harness();
  let entries: OcrWorkerResult['data']['entries'] = [];
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => ({ data: { text: 'Unit 2 星期一 (24)', candidateText: '', entries } }), terminate: () => undefined })
  }) });
  await session.addFiles([file('source.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value, []);
  assert.equal(session.candidate.value, '');
  assert.equal(session.items.value[0]!.rawText, 'Unit 2 星期一 (24)');
  session.changeSelection(90, null);
  entries = undefined;
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value, []);
  session.dispose();
});

test('语文图片识别直接转为汉字拼音配对，拼音空栏交由同一编辑器自动补齐', async () => {
  const h = harness();
  const raw = '春天 学校\n花朵';
  const session = createWorksheetImageSession({ ...h.deps, createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => ({ data: { text: raw } }), terminate() {} })
  }) });
  await session.addFiles([file('chinese.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value, ['春天', '学校', '花朵'].map(text => ({ text, pinyin: '', pinyinState: 'missing' })));
  assert.equal(session.items.value[0]!.rawText, raw, '自动补全入口不覆盖原始OCR文字');
  session.dispose();
});

test('英语图片只识别出中文时也保留中文空英文配对，支持自动反向翻译', async () => {
  const h = harness();
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => ({ data: { text: '星期一 (24)\n星期二 (24)' } }), terminate() {} })
  }) });
  await session.addFiles([file('meaning-only.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.meaning, row.english]), [['星期一', ''], ['星期二', '']]);
  assert.ok(session.candidatePairs.value?.every(row => row.englishState === 'missing'));
  session.dispose();
});

test('取消后迟到的词表识别不覆盖手动候选，重新识别仍能保留原始行关系', async () => {
  const h = harness();
  const late = deferred<OcrWorkerResult>();
  const entered = deferred<void>();
  let calls = 0;
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => {
      calls += 1;
      if (calls === 1) { entered.resolve(); return late.promise; }
      return { data: { text: 'Monday 星期一', candidateText: 'Monday', entries: [{ text: 'Monday', meaning: '星期一' }] } };
    }, terminate: () => undefined })
  }) });
  await session.addFiles([file('source.png')]);
  const running = session.recognize();
  await entered.promise;
  session.candidatePairs.value = [{ english: 'week', meaning: '周，星期', englishState: 'confirmed', meaningState: 'confirmed' }];
  session.cancel();
  late.resolve({ data: { text: 'stale 旧数据', entries: [{ text: 'stale', meaning: '旧数据' }] } });
  await running;
  assert.deepEqual(session.candidatePairs.value.map(row => [row.english, row.meaning]), [['week', '周，星期']]);
  assert.equal(session.items.value[0]!.entries, undefined);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.english, row.meaning]), [['Monday', '星期一']]);
  session.dispose();
});

test('原文修订变化使迟到配对识别作废，不覆盖先前人工候选', async () => {
  const h = harness();
  const late = deferred<OcrWorkerResult>();
  const entered = deferred<void>();
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', createWorker: async () => ({ recognize: async () => { entered.resolve(); return late.promise; }, terminate: () => undefined })
  }) });
  await session.addFiles([file('source.png')]);
  session.candidatePairs.value = [{ english: 'dog', meaning: '狗' }];
  const running = session.recognize();
  await entered.promise;
  h.changeRevision();
  late.resolve({ data: { text: 'cat 猫', entries: [{ text: 'cat', meaning: '猫' }] } });
  await running;
  assert.deepEqual(session.candidatePairs.value.map(row => [row.english, row.meaning]), [['dog', '狗']]);
  assert.equal(session.items.value[0]!.status, 'waiting');
  assert.equal(session.items.value[0]!.entries, undefined);
  session.dispose();
});

test('增强超时保留成功阶段的中英配对而非退回仅英文', async () => {
  const h = harness();
  const session = createWorksheetImageSession({ ...h.deps, language: () => 'english', createService: () => new WorksheetOcrService({
    assetBase: '/ocr/', recognizeTimeoutMs: 10, createWorker: async () => ({
      recognize: async () => new Promise(() => undefined),
      getFallbackResult: () => ({ data: { text: 'Monday 星期一 (24)', candidateText: 'Monday', entries: [{ text: 'Monday', meaning: '星期一' }] } }),
      terminate: () => undefined
    })
  }) });
  await session.addFiles([file('source.png')]);
  await session.recognize();
  assert.deepEqual(session.candidatePairs.value?.map(row => [row.english, row.meaning]), [['Monday', '星期一']]);
  assert.equal(session.items.value[0]!.message, '已保留识别结果，增强未完成，请核对。');
  session.dispose();
});
