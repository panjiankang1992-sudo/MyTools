import { computed, ref, shallowRef } from 'vue';
import { IMAGE_LIMITS, validateImageFile, type CropRect, type ImageIssue, type Orientation } from '../lib/image-preprocess';
import { OcrError, type OcrLanguage, type WorksheetOcrService } from './worksheet-ocr';
import type { OcrVocabularyEntry } from '../domain/worksheet-ocr-pairs';
import type { EditableWorksheetPair } from '../domain/worksheet-editable-pairs';
import { isValidEnglishText } from '../domain/worksheet-processing';
import { tokenize } from '../domain/worksheet-input';

/** 旧引擎没有坐标时仅拆分同一行的明确中英边界，绝不把下一行中文配给上一行。 */
function legacyLinePair(value: string): OcrVocabularyEntry | null {
  const text = value.replace(/\s*[（(]\s*\d{1,4}\s*[)）]\s*$/u, '').trim();
  if (!text) return null;
  const han = /\p{Script=Han}/u.test(text);
  const latin = /\p{Script=Latin}/u.test(text);
  if (!han) return { text, meaning: '' };
  if (!latin) return { text: '', meaning: text };
  const englishFirst = text.match(/^([\p{Script=Latin}\p{Mark}\p{Number}\p{Punctuation}\s]+?)(\p{Script=Han}[\p{Script=Han}\p{Number}\p{Punctuation}\s]*)$/u);
  if (englishFirst && isValidEnglishText(englishFirst[1]!.trim())) {
    return { text: englishFirst[1]!.trim(), meaning: englishFirst[2]!.trim() };
  }
  const chineseFirst = text.match(/^(\p{Script=Han}[\p{Script=Han}\p{Number}\p{Punctuation}\s]*?)(\p{Script=Latin}[\p{Script=Latin}\p{Mark}\p{Number}\p{Punctuation}\s]*)$/u);
  if (chineseFirst && isValidEnglishText(chineseFirst[2]!.trim())) {
    return { text: chineseFirst[2]!.trim(), meaning: chineseFirst[1]!.trim() };
  }
  // 交错多组时保留整行供拆分，释义留空，不能把英文答案直接放入中文提示。
  return { text, meaning: '' };
}

/** 每张图拥有独立的方向、裁剪和结果，绝不复用另一张图的坐标。 */
export interface WorksheetImageItem {
  id: string;
  file: File;
  name: string;
  orientation: Orientation;
  crop: CropRect | null;
  status: 'waiting' | 'running' | 'done' | 'failed';
  /** 引擎全文与可编辑词表分开保存，过滤内容仍可回看。 */
  rawText: string;
  entries?: OcrVocabularyEntry[];
  text: string;
  confidence: number | null;
  message: string;
}

/** 图片会话依赖，解码与编码可注入以验证真正的异步交错。 */
export interface WorksheetImageDeps {
  decode: (file: File) => Promise<ImageBitmap>;
  encode: (bitmap: ImageBitmap, orientation: Orientation, crop: CropRect | null) => Promise<Blob>;
  createService: () => WorksheetOcrService;
  inputRevision: () => number;
  language: () => OcrLanguage;
}

/** 图片选择、整批识别及资源生命周期；界面只呈现本会话的状态。 */
export function createWorksheetImageSession(deps: WorksheetImageDeps) {
  const items = ref<WorksheetImageItem[]>([]);
  const selectedId = ref<string | null>(null);
  const preview = shallowRef<ImageBitmap | null>(null);
  const issues = ref<ImageIssue[]>([]);
  const candidate = ref('');
  const candidatePairs = ref<EditableWorksheetPair[] | null>(null);
  const status = ref('');
  const recognizing = ref(false);
  const picking = ref(false);
  const selected = computed(() => items.value.find(item => item.id === selectedId.value) ?? null);
  const queue = computed(() => ({
    active: items.value.find(item => item.status === 'running')?.id ?? null,
    pending: recognizing.value ? items.value.filter(item => item.status === 'waiting').length : 0
  }));
  let lifetime = 0;
  let runId = 0;
  let previewId = 0;
  let imageId = 0;
  let disposed = false;
  let service: WorksheetOcrService | null = null;

  /** 释放当前预览位图。 */
  function releasePreview(): void {
    preview.value?.close();
    preview.value = null;
  }

  /** 切换预览时只保留当前选择的位图，晚到解码立即关闭。 */
  async function select(id: string): Promise<void> {
    const item = items.value.find(value => value.id === id);
    if (!item || disposed) return;
    const request = ++previewId;
    selectedId.value = id;
    releasePreview();
    let bitmap: ImageBitmap;
    try { bitmap = await deps.decode(item.file); } catch {
      if (request === previewId) status.value = '图片预览加载失败，请重新选择。';
      return;
    }
    if (disposed || request !== previewId || !items.value.some(value => value.id === id)) {
      bitmap.close();
      return;
    }
    preview.value = bitmap;
  }

  /** 加入文件时计算实际剩余额度；移除和卸载会作废未完成的解码。 */
  async function addFiles(files: readonly File[]): Promise<void> {
    if (disposed || picking.value || !files.length) return;
    const current = lifetime;
    issues.value = [];
    if (items.value.length + files.length > IMAGE_LIMITS.fileCount) {
      issues.value = [{ code: 'WORKSHEET_IMAGE_TOO_MANY_FILES' }];
      return;
    }
    const total = [...items.value.map(item => item.file), ...files].reduce((sum, file) => sum + file.size, 0);
    if (total > IMAGE_LIMITS.totalBytes) {
      issues.value = [{ code: 'WORKSHEET_IMAGE_TOTAL_TOO_LARGE' }];
      return;
    }
    picking.value = true;
    const added: WorksheetImageItem[] = [];
    try {
      for (const file of files) {
        let bitmap: ImageBitmap | null = null;
        try {
          const header = new Uint8Array(await file.slice(0, 16).arrayBuffer());
          if (current !== lifetime || disposed) return;
          bitmap = await deps.decode(file);
          if (current !== lifetime || disposed) return;
          const errors = validateImageFile({ name: file.name, type: file.type, bytes: file.size, header, width: bitmap.width, height: bitmap.height });
          if (errors.length) { issues.value.push(...errors); continue; }
          added.push({ id: `image-${++imageId}`, file, name: file.name, orientation: 0, crop: null, status: 'waiting', rawText: '', text: '', confidence: null, message: '' });
        } catch {
          if (current === lifetime && !disposed) issues.value.push({ code: 'WORKSHEET_IMAGE_DECODE_FAILED', detail: file.name });
        } finally { bitmap?.close(); }
      }
      if (current !== lifetime || disposed || !added.length) return;
      items.value.push(...added);
      await select(added[0]!.id);
      if (current !== lifetime || disposed) return;
      candidate.value = '';
      candidatePairs.value = null;
      status.value = `已加入 ${added.length} 张，队列中共 ${items.value.length} 张。`;
    } finally {
      if (current === lifetime) picking.value = false;
    }
  }

  /** 取消整个批次；旧任务不能修改新批次的 busy、队列或候选。 */
  function cancel(): void {
    const wasRecognizing = recognizing.value;
    runId += 1;
    service?.cancelAll();
    for (const item of items.value) {
      if (item.status === 'running') {
        item.status = 'waiting';
        item.rawText = '';
        item.entries = undefined;
        item.text = '';
        item.confidence = null;
        item.message = '已取消';
      }
    }
    recognizing.value = false;
    // 空闲切换步骤仍作废旧任务，但不得把正常提示改成未发生的取消。
    if (wasRecognizing) status.value = '已取消本次识别，原文与图片都保留。';
  }

  /** 修改单张图片后只作废该图的结果，并停止使用旧图设置的批次。 */
  function changeSelection(orientation: Orientation, crop: CropRect | null): void {
    if (!selected.value) return;
    if (recognizing.value) cancel();
    Object.assign(selected.value, { orientation, crop, status: 'waiting', rawText: '', text: '', entries: undefined, confidence: null, message: '' });
    candidate.value = '';
    candidatePairs.value = null;
  }

  /** 串行识别冻结的图片集合，逐阶段核验批次与原文修订。 */
  async function recognize(): Promise<void> {
    if (disposed || recognizing.value || picking.value) return;
    const pending = items.value.filter(item => item.status === 'waiting' || item.status === 'failed');
    if (!pending.length) { status.value = '队列中没有待识别的图片。'; return; }
    const request = ++runId;
    const revision = deps.inputRevision();
    const language = deps.language();
    const current = () => !disposed && request === runId && revision === deps.inputRevision() && language === deps.language();
    recognizing.value = true;
    try {
      for (const item of pending) {
        if (!current()) break;
        item.status = 'running';
        item.message = '';
        let bitmap: ImageBitmap | null = null;
        try {
          bitmap = await deps.decode(item.file);
          if (!current()) break;
          const blob = await deps.encode(bitmap, item.orientation, item.crop ? { ...item.crop } : null);
          bitmap.close();
          bitmap = null;
          if (!current()) break;
          service ??= deps.createService();
          const outcome = await service.recognize({ jobId: `${request}:${item.id}`, imageId: item.id, inputRevision: revision, language, image: blob });
          if (!current()) break;
          item.status = 'done';
          // 原始全文不可被候选编辑覆盖；明确为空的候选也不能回退为带释义和页码的全文。
          item.rawText = outcome.rawRecognizedText;
          item.text = outcome.candidateText ?? outcome.rawRecognizedText;
          item.entries = outcome.entries?.map(entry => ({ ...entry }));
          item.confidence = outcome.confidence;
          item.message = outcome.enhancementIncomplete ? '已保留识别结果，增强未完成，请核对。' : !item.text.trim() ? item.rawText.trim() ? '未提取到可用词条，可查看原始识别文字。' : '没有识别到文字' : outcome.confidence === null ? '引擎未提供置信度，请逐字核对' : outcome.confidence < 80 ? `置信度 ${outcome.confidence.toFixed(0)}，较低，请逐字核对` : '';
        } catch (error) {
          if (!current()) break;
          const code = error instanceof OcrError ? error.code : 'WORKSHEET_OCR_FAILED';
          item.status = code === 'WORKSHEET_OCR_CANCELLED' ? 'waiting' : 'failed';
          item.message = code;
        } finally { bitmap?.close(); }
      }
      if (!current()) return;
      candidate.value = items.value.filter(item => item.status === 'done').map(item => item.text).join('\n');
      const done = items.value.filter(item => item.status === 'done');
      // 两个学科都交付可自动补全的配对表，只有中文的英语行仍可反向翻译。
      candidatePairs.value = language === 'english'
        ? done.flatMap(item => (item.entries ?? item.text.split(/\r?\n/u).map(legacyLinePair).filter((entry): entry is OcrVocabularyEntry => entry !== null)).map(entry => ({
          english: entry.text, meaning: entry.meaning,
          englishState: entry.text ? 'suggested' as const : 'missing' as const,
          meaningState: entry.meaning ? 'suggested' as const : 'missing' as const
        }))) : tokenize(candidate.value, 'whitespace').spans.map(span => span.text)
          .map(text => ({ text, pinyin: '', pinyinState: 'missing' as const }));
      const incomplete = items.value.some(item => item.message === '已保留识别结果，增强未完成，请核对。');
      status.value = `已识别 ${items.value.filter(item => item.status === 'done').length} / ${items.value.length} 张，${incomplete ? '部分图片增强未完成，已保留识别结果，请核对。' : '请核对结果后使用。'}`;
    } finally {
      if (request === runId) {
        recognizing.value = false;
        for (const item of items.value) if (item.status === 'running') item.status = 'waiting';
      }
    }
  }

  /** 完整清空队列、额度、预览、选区和候选，作废所有迟到任务。 */
  function reset(): void {
    lifetime += 1;
    previewId += 1;
    cancel();
    items.value = [];
    selectedId.value = null;
    releasePreview();
    picking.value = false;
    candidate.value = '';
    candidatePairs.value = null;
    issues.value = [];
    status.value = '';
  }

  /** 卸载时释放所有位图与 worker，之后不再接纳任何输入。 */
  function dispose(): void {
    reset();
    disposed = true;
    void service?.dispose();
    service = null;
  }

  return { items, selected, selectedId, preview, issues, candidate, candidatePairs, status, recognizing, picking, queue, select, addFiles, cancel, changeSelection, recognize, reset, dispose };
}
