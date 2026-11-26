<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { NAlert, NButton, NTag } from 'naive-ui';
import { displaySize, normalizeCrop, type CropRect, type Orientation } from '../../lib/image-preprocess';
import { drawWorksheetImage, encodeWorksheetImage } from '../../lib/worksheet-image-render';
import { WorksheetOcrService } from '../../services/worksheet-ocr';
import { createOcrWorker, ocrAssetBase } from '../../services/ocr-engine';
import { createWorksheetImageSession } from '../../services/worksheet-image-session';
import WorksheetPairedEditor from './WorksheetPairedEditor.vue';
import type { EditableWorksheetPair } from '../../domain/worksheet-editable-pairs';

/** 图片识别只提供待校对候选；追加和替换始终由用户操作。 */
const props = withDefaults(defineProps<{ inputRevision: number; subject: 'chinese' | 'english'; hasExistingText: boolean; active?: boolean }>(), { active: true });
const emit = defineEmits<{ apply: [mode: 'append' | 'replace', text: string, pairs?: EditableWorksheetPair[]] }>();
const session = createWorksheetImageSession({
  decode: file => createImageBitmap(file),
  encode: encodeWorksheetImage,
  createService: () => new WorksheetOcrService({ assetBase: ocrAssetBase(import.meta.env?.BASE_URL ?? '/'), createWorker: createOcrWorker }),
  inputRevision: () => props.inputRevision,
  language: () => props.subject
});
const { items, selected, preview, queue, issues, candidate, candidatePairs, status, recognizing, picking } = session;
const hasCandidate = computed(() => candidatePairs.value !== null
  ? candidatePairs.value.some(row => props.subject === 'chinese' ? row.text?.trim() : row.english?.trim() || row.meaning?.trim()) : !!candidate.value.trim());
const candidateEditor = ref<{ cancelCompletion: () => void; flushCompletion: () => Promise<boolean> } | null>(null);
const applying = ref(false);
const input = ref<HTMLInputElement | null>(null);
const cropCanvas = ref<HTMLCanvasElement | null>(null);
const pendingReplace = ref(false);
const adjustmentsOpen = ref(false);
const dragStart = ref<{ x: number; y: number } | null>(null);
const selection = ref<CropRect | null>(null);
const fileName = computed(() => selected.value?.name ?? '');
const orientation = computed(() => selected.value?.orientation ?? 0);
const crop = computed(() => dragStart.value ? selection.value : selected.value?.crop ?? null);
const hasPendingImages = computed(() => items.value.some(item => item.status === 'waiting' || item.status === 'failed'));
const candidateEditorVisible = computed(() => candidate.value !== '' || items.value.some(item => item.status === 'done'));
const recognizedText = computed(() => items.value.filter(item => item.status === 'done').map(item => item.text).join('\n'));
const rawRecognizedText = computed(() => items.value.filter(item => item.status === 'done').map(item => item.rawText).join('\n\n'));
const activeImageNumber = computed(() => items.value.findIndex(item => item.id === queue.value.active) + 1);
const confidence = computed(() => {
  const done = items.value.filter(item => item.status === 'done');
  return done.length && done.every(item => item.confidence !== null)
    ? Math.min(...done.map(item => item.confidence!)) : null;
});

/** 清除原生文件值，以便移除后再次选择同一文件。 */
async function onPick(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement;
  const files = [...(target.files ?? [])];
  target.value = '';
  pendingReplace.value = false;
  await session.addFiles(files);
}

/** 按统一旋转函数绘制预览，非正方形图片不再被拉伸或裁掉。 */
function drawPreview(): void {
  const canvas = cropCanvas.value;
  const bitmap = preview.value;
  if (!canvas || !bitmap) return;
  const display = displaySize(bitmap, orientation.value);
  const scale = Math.min(1, 520 / display.width);
  canvas.width = Math.max(1, Math.round(display.width * scale));
  canvas.height = Math.max(1, Math.round(display.height * scale));
  const context = canvas.getContext('2d');
  if (!context) return;
  context.clearRect(0, 0, canvas.width, canvas.height);
  drawWorksheetImage(context, bitmap, bitmap, orientation.value, null, canvas);
  if (crop.value) {
    context.strokeStyle = '#646cff';
    context.lineWidth = 2;
    context.strokeRect(crop.value.x * scale, crop.value.y * scale, crop.value.width * scale, crop.value.height * scale);
  }
}

/** 指针坐标换算到已旋转的显示图坐标。 */
function displayPoint(event: PointerEvent): { x: number; y: number } {
  const rect = cropCanvas.value!.getBoundingClientRect();
  const display = displaySize(preview.value!, orientation.value);
  return { x: (event.clientX - rect.left) * display.width / rect.width, y: (event.clientY - rect.top) * display.height / rect.height };
}

/** 开始框选即取消旧设置的识别批次。 */
function onPointerDown(event: PointerEvent): void {
  if (!preview.value) return;
  if (recognizing.value) session.cancel();
  dragStart.value = displayPoint(event);
  selection.value = { ...dragStart.value, width: 0, height: 0 };
  cropCanvas.value?.setPointerCapture?.(event.pointerId);
}

/** 更新裁剪框，范围限定在当前显示图内。 */
function onPointerMove(event: PointerEvent): void {
  if (!dragStart.value || !preview.value) return;
  const point = displayPoint(event);
  selection.value = normalizeCrop({ ...dragStart.value, width: point.x - dragStart.value.x, height: point.y - dragStart.value.y }, preview.value, orientation.value).crop;
  drawPreview();
}

/** 将选区提交到当前图片，其他图片的方向与选区不受影响。 */
function onPointerUp(): void {
  if (!dragStart.value) return;
  const next = selection.value;
  session.changeSelection(orientation.value, next && next.width >= 8 && next.height >= 8 ? next : null);
  dragStart.value = null;
  selection.value = null;
  drawPreview();
}

/** 旋转当前图后废弃旧坐标选区。 */
function rotate(): void {
  session.changeSelection(((orientation.value + 90) % 360) as Orientation, null);
  status.value = '已旋转图片，请重新框选区域后识别。';
}

/** 当前图回到整图识别。 */
function clearCrop(): void { session.changeSelection(orientation.value, null); }

/** 整批串行识别。 */
async function recognize(): Promise<void> { pendingReplace.value = false; await session.recognize(); }

/** 只在候选为空时恢复已识别文字，不覆盖用户校对后的内容。 */
function restoreRecognizedText(): void {
  if (!candidate.value.trim()) candidate.value = recognizedText.value;
}

/** 取消活动任务及本批次的后续图片。 */
function cancel(): void { session.cancel(); candidateEditor.value?.cancelCompletion(); }

/** 仅在原文确实存在时二次确认替换；确认绑定当前候选。 */
async function applyCandidate(mode: 'append' | 'replace'): Promise<void> {
  if (!hasCandidate.value || recognizing.value || applying.value || !props.active) return;
  if (mode === 'replace' && props.hasExistingText && !pendingReplace.value) {
    pendingReplace.value = true;
    status.value = '替换会覆盖当前原文，请再点一次确认。';
    return;
  }
  pendingReplace.value = false;
  status.value = '';
  // 首次结果和初始化标记一并导入；等待期间编辑或离开会取消继续导航。
  applying.value = true;
  let unchanged = true;
  try { unchanged = await (candidateEditor.value?.flushCompletion() ?? Promise.resolve(true)); }
  finally { applying.value = false; }
  if (!unchanged || !props.active || !hasCandidate.value || recognizing.value) return;
  // 是否接纳候选和进入下一步由父页面校验决定。
  const pairs = candidatePairs.value?.map(row => ({ ...row }));
  emit('apply', mode, pairs ? pairs.map(row => props.subject === 'chinese' ? row.text || '' : row.english || row.meaning || '').join('\n') : candidate.value, pairs);
}

/** 完整释放图片队列、原生输入、指针与候选状态。 */
function reset(): void {
  candidateEditor.value?.cancelCompletion();
  session.reset();
  adjustmentsOpen.value = false;
  if (input.value) input.value.value = '';
  dragStart.value = null;
  selection.value = null;
  pendingReplace.value = false;
  if (cropCanvas.value) { cropCanvas.value.width = 0; cropCanvas.value.height = 0; }
}


/** 展开可选调整后重绘画布，折叠状态不影响已保存的图片设置。 */
async function toggleAdjustments(event: Event): Promise<void> {
  adjustmentsOpen.value = (event.target as HTMLDetailsElement).open;
  await nextTick();
  drawPreview();
}

watch([preview, () => selected.value?.orientation, () => selected.value?.crop], async () => {
  dragStart.value = null;
  await nextTick();
  drawPreview();
});
watch(() => props.inputRevision, () => {
  pendingReplace.value = false;
  if (recognizing.value) session.cancel();
});
watch(candidate, () => { pendingReplace.value = false; });
watch(candidatePairs, () => { pendingReplace.value = false; }, { deep: true });
watch(() => props.subject, reset);
onBeforeUnmount(() => session.dispose());
defineExpose({ reset, cancel });
</script>

<template>
  <section class="image-input" aria-label="图片识别">
    <div class="pick-row">
      <NButton
        v-if="items.length < 5"
        :type="items.length ? 'default' : 'primary'"
        :loading="picking"
        :disabled="picking || recognizing"
        @click="input?.click()"
      >{{ items.length ? '添加图片' : '选择图片' }}</NButton>
      <span v-if="items.length" class="selection-summary">已选 {{ items.length }} 张图片</span>
      <span v-else class="image-hint">最多 5 张，每张 10 MB</span>
    </div>
    <p v-if="!items.length" class="image-hint">支持 PNG、JPEG、WebP，图片仅在本机处理。</p>
    <p v-if="subject === 'english'" class="image-hint">识别单词表中的中文和英文，按行对应；识别后可直接修改。</p>
    <input
      ref="input"
      class="file-input"
      type="file"
      multiple
      :disabled="picking || recognizing"
      accept="image/png,image/jpeg,image/webp"
      aria-label="选择图片"
      tabindex="-1"
      @change="onPick"
    />

    <NAlert v-if="issues.length" type="error" class="image-issues">
      <ul class="issue-list">
        <li v-for="issue in issues" :key="issue.code">{{ issue.code }}<template v-if="issue.detail"> · {{ issue.detail }}</template></li>
      </ul>
    </NAlert>

    <details v-if="fileName" class="image-adjustments" :open="adjustmentsOpen" @toggle="toggleAdjustments">
      <summary>调整图片与查看进度 <span class="summary-hint">裁剪 · 旋转</span></summary>
      <div class="image-body">
        <ol class="queue-list" aria-label="识别队列">
          <li v-for="(item, index) in items" :key="item.id" class="queue-item">
            <button
              class="queue-name"
              type="button"
              :disabled="picking"
              :aria-pressed="selected?.id === item.id"
              @click="session.select(item.id)"
            >{{ index + 1 }}. {{ item.name }}</button>
            <span class="queue-state" :class="{ failed: item.status === 'failed' }">
              {{ item.status === 'done' ? '已完成' : item.status === 'failed' ? '失败' : item.status === 'running' ? '识别中' : '待识别' }}
            </span>
            <span v-if="item.message" class="queue-message">{{ item.message }}</span>
          </li>
        </ol>
        <canvas
          ref="cropCanvas"
          class="crop-canvas"
          aria-label="框选识别区域"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        />
        <p class="image-hint">拖动框选文字；不框选时识别整图。</p>
        <div class="image-actions">
          <NButton size="small" @click="rotate">旋转 90°</NButton>
          <NButton v-if="crop" size="small" @click="clearCrop">清除选区</NButton>
          <NTag v-if="orientation" size="small" :bordered="false">{{ orientation }}°</NTag>
          <NButton size="small" quaternary @click="reset">移除全部图片</NButton>
        </div>
        <p v-if="candidate" class="image-hint">
          {{ confidence === null ? '引擎未提供置信度，请核对识别结果。' : `最低置信度 ${confidence.toFixed(0)}%，请核对识别结果。` }}
        </p>
      </div>
    </details>

    <div v-if="items.length && (hasPendingImages || recognizing)" class="recognition-row">
      <NButton :type="candidate && !recognizing ? 'default' : 'primary'" :loading="recognizing" :disabled="picking || recognizing" @click="recognize">
        {{ recognizing ? '识别中…' : candidate ? '识别剩余图片' : '识别文字' }}
      </NButton>
      <NButton v-if="recognizing" @click="cancel">取消</NButton>
      <span v-if="recognizing && activeImageNumber" class="image-hint" role="status">第 {{ activeImageNumber }} / {{ items.length }} 张</span>
      <span v-else-if="!candidate" class="image-hint">首次识别可能需要稍等。</span>
    </div>

    <p v-if="status" class="image-status" role="status">{{ status }}</p>

    <div v-if="candidateEditorVisible" class="candidate">
      <WorksheetPairedEditor v-if="candidatePairs !== null" ref="candidateEditor" :rows="candidatePairs" :subject="subject" label="核对识别内容" :busy="recognizing" :auto-complete="active && !recognizing && !picking" @update:rows="candidatePairs = $event" />
      <label v-else class="candidate-label">核对识别文字
        <textarea
          class="candidate-area"
          :value="candidate"
          :disabled="recognizing"
          rows="5"
          aria-label="识别结果"
          @input="candidate = ($event.target as HTMLTextAreaElement).value"
        />
      </label>
      <p class="image-hint">{{ subject === 'english' ? '识别后首次补齐中文或英文，之后的修改和清空保持原样，请核对教材中的原意。' : '识别后首次填写拼音，之后的修改和清空保持原样。' }}</p>
      <details v-if="rawRecognizedText.trim()" class="more-actions">
        <summary>查看原始识别文字</summary>
        <pre class="raw-recognized-text" aria-label="原始识别文字">{{ rawRecognizedText }}</pre>
      </details>
      <p v-if="hasExistingText" class="image-hint">新文字将添加到已有内容后。</p>
      <div class="candidate-actions">
        <NButton
          :type="recognizing || !hasCandidate ? 'default' : 'primary'"
          :loading="applying" :disabled="recognizing || !hasCandidate || applying"
          @click="applyCandidate(hasExistingText ? 'append' : 'replace')"
        >下一步</NButton>
        <NButton
          v-if="!candidate.trim() && recognizedText.trim()"
          :disabled="recognizing"
          @click="restoreRecognizedText"
        >恢复识别文字</NButton>
      </div>
      <details v-if="hasExistingText" class="more-actions">
        <summary>更多操作</summary>
        <NButton
          size="small"
          :disabled="recognizing || !hasCandidate"
          @click="applyCandidate('replace')"
        >{{ pendingReplace ? '确认替换原文' : '替换原文' }}</NButton>
      </details>
    </div>
  </section>
</template>

<style scoped>
.image-input { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.pick-row, .recognition-row, .image-actions, .candidate-actions { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.selection-summary { font-size: 13px; }
.image-hint, .image-status { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.6; }
.file-input { display: none; }
.image-adjustments { border: 1px solid var(--border, #d7dee8); border-radius: 8px; padding: 10px 12px; }
.image-adjustments summary { cursor: pointer; font-size: 13px; line-height: 1.7; }
.summary-hint { margin-left: 8px; color: var(--muted); font-size: 12px; }
.image-body { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; min-width: 0; }
.crop-canvas { align-self: flex-start; max-width: 100%; max-height: 420px; width: auto; height: auto; border: 1px solid var(--border, #d7dee8); border-radius: 6px; touch-action: none; cursor: crosshair; }
.queue-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; max-height: 180px; overflow: auto; }
.queue-item { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; font-size: 12px; min-width: 0; }
.queue-name { max-width: 240px; min-width: 0; padding: 5px 8px; background: transparent; color: inherit; border: 1px solid transparent; border-radius: 5px; font: inherit; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; text-align: left; }
.queue-name[aria-pressed="true"] { border-color: var(--border, #d7dee8); font-weight: 600; }
.queue-state { color: var(--muted); flex-shrink: 0; }
.queue-state.failed, .queue-message { color: #b45309; }
.queue-message { flex-basis: 100%; padding-left: 8px; overflow-wrap: anywhere; }
.more-actions summary { cursor: pointer; color: var(--muted); font-size: 12px; margin-bottom: 8px; }
.raw-recognized-text { max-height: 240px; overflow: auto; margin: 0; padding: 10px; border: 1px solid var(--border, #d7dee8); border-radius: 8px; white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; font-size: 12px; line-height: 1.7; }
.candidate { display: flex; flex-direction: column; gap: 8px; }
.candidate-label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; font-weight: 600; }
.candidate-area { box-sizing: border-box; width: 100%; padding: 10px; border: 1px solid var(--border, #d7dee8); border-radius: 8px; font-family: inherit; font-size: 14px; font-weight: 400; line-height: 1.7; resize: vertical; }
.image-issues { font-size: 12px; }
.issue-list { margin: 0; padding-left: 18px; overflow-wrap: anywhere; }
@media (max-width: 480px) {
  .queue-name { max-width: 190px; }
  .summary-hint { display: block; margin-left: 16px; }
  .candidate-actions > :first-child { flex: 1; }
}
</style>
