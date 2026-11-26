<script setup lang="ts">
import { NAlert, NButton, NCheckbox, NInput, NSpin, NTag } from 'naive-ui';
import { computed, ref } from 'vue';
import { AdminApiError } from '../../services/admin-api';
import { adminErrorMessage } from '../../lib/admin-error-messages';
import {
  fetchExportManifest,
  restorePackage,
  type AdminExportManifest,
  type AdminExportPackage,
  type AdminRestoreReport
} from '../../services/admin-curriculum-api';
import {
  buildRestorePreview,
  parseExportPackage,
  type RestorePreview,
  type RestoreVerdict
} from '../../lib/admin-restore-preview';

/**
 * 恢复导入包（D02 的"回退入口"）。
 *
 * 流程刻意分成三步，中间不让用户"一键到底"：
 * 1. **解析并预览**——先算出册数、版本数与已知冲突，让用户看清要动什么；
 * 2. **二次确认**——勾选确认后"确认恢复"按钮才可用；
 * 3. **展示对账结果**——逐册列出指针／正文哈希／来源快照是否一致，
 *    并把服务端报的每一条冲突**原样显示**（绝不静默跳过）。
 *
 * 为什么不做"覆盖"开关：服务端对"库内已有同版本不同内容"的处理是**报冲突、不写入**。
 * 界面若提供"强制覆盖"就是在诱导数据丢失，因此这里没有这个选项。
 */

const rawText = ref('');
const fileName = ref('');
const parseError = ref('');
const pkg = ref<AdminExportPackage | null>(null);
const preview = ref<RestorePreview | null>(null);
const manifest = ref<AdminExportManifest | null>(null);
const manifestNote = ref('');
const confirmed = ref(false);
const analyzing = ref(false);
const restoring = ref(false);
const errorText = ref('');
const report = ref<AdminRestoreReport | null>(null);

/** 是否可以提交恢复：必须已经预览过、并且用户明确勾选确认。 */
const canSubmit = computed(() => Boolean(pkg.value && preview.value && confirmed.value && !restoring.value));

/** 判定标签的颜色。 */
function verdictType(verdict: RestoreVerdict): 'default' | 'info' | 'error' {
  if (verdict === 'conflict') return 'error';
  if (verdict === 'skip') return 'info';
  return 'default';
}

/** 判定标签的文字。 */
function verdictLabel(verdict: RestoreVerdict): string {
  if (verdict === 'conflict') return '冲突';
  if (verdict === 'skip') return '将跳过';
  return '将新增';
}

/** 把异常翻成用户可读的一句话。 */
function describeError(cause: unknown): string {
  return cause instanceof AdminApiError ? adminErrorMessage(cause.messageKey) : '请求失败，请重试。';
}

/** 清空全部输入与结果。 */
function clearAll(): void {
  rawText.value = '';
  fileName.value = '';
  parseError.value = '';
  pkg.value = null;
  preview.value = null;
  manifestNote.value = '';
  confirmed.value = false;
  errorText.value = '';
  report.value = null;
}

/** 读取库内现状；失败不阻塞预览，但必须如实标注。 */
async function loadManifest(): Promise<void> {
  if (manifest.value) return;
  try {
    manifest.value = await fetchExportManifest();
    manifestNote.value = '';
  } catch (cause) {
    manifest.value = null;
    manifestNote.value = describeError(cause);
  }
}

/** 解析并预览：不写库、不弹确认。 */
async function analyzePackage(): Promise<void> {
  resetForNewPreview();
  const parsed = parseExportPackage(rawText.value);
  if (!parsed.ok) {
    parseError.value = parsed.error;
    return;
  }
  analyzing.value = true;
  try {
    pkg.value = parsed.pkg;
    await loadManifest();
    preview.value = buildRestorePreview(parsed.pkg, manifest.value);
  } finally {
    analyzing.value = false;
  }
}

/** 开始新一轮预览前重置旧的结论；旧的恢复结果不能与新包混在一起看。 */
function resetForNewPreview(): void {
  parseError.value = '';
  pkg.value = null;
  preview.value = null;
  confirmed.value = false;
  errorText.value = '';
  report.value = null;
}

/** 选择本地文件；只读取文本，不上传。 */
async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  fileName.value = file.name;
  try {
    rawText.value = await file.text();
    parseError.value = '';
  } catch {
    parseError.value = '无法读取所选文件，请改用粘贴。';
  }
}

/**
 * 确认后执行恢复。
 *
 * 这里再判一次 `canSubmit` 的前置条件：按钮的 `disabled` 只是界面提示，
 * 真正的闸门必须写在会发请求的地方——否则将来某处改动让按钮可点，
 * 就会在没有二次确认的情况下发出破坏性请求。
 */
async function confirmRestore(): Promise<void> {
  if (!pkg.value || !preview.value || !confirmed.value) return;
  restoring.value = true;
  errorText.value = '';
  report.value = null;
  try {
    report.value = await restorePackage(pkg.value);
  } catch (cause) {
    errorText.value = describeError(cause);
  } finally {
    restoring.value = false;
  }
}
</script>

<template>
  <section class="admin-restore" aria-label="恢复导入包">
    <header class="restore-header">
      <div>
        <h2>恢复导入包</h2>
        <p class="admin-note">从导出包把教材版本恢复到当前数据库。恢复是破坏性操作，只在确认后执行。</p>
      </div>
    </header>

    <NAlert type="warning" :show-icon="false" class="restore-warning">
      恢复只会<strong>新增或跳过</strong>版本；库内已有同版本但内容不同时会被判为冲突并列出，<strong>不会被静默覆盖</strong>。
    </NAlert>

    <div class="restore-input">
      <label class="restore-label" for="restore-file">选择导出包文件（.json）</label>
      <input id="restore-file" class="restore-file" type="file" accept="application/json,.json" aria-label="选择导出包文件" @change="onFileChange" />
      <span v-if="fileName" class="admin-note">已选择：{{ fileName }}</span>
      <NInput
        v-model:value="rawText"
        type="textarea"
        :rows="6"
        aria-label="导出包 JSON"
        placeholder="或直接粘贴导出包的 JSON（GET /admin/v1/curriculum/export-package 返回的 data）"
      />
      <div class="restore-actions">
        <NButton type="primary" :loading="analyzing" data-testid="restore-analyze" @click="analyzePackage">解析并预览</NButton>
        <NButton quaternary @click="clearAll">清空</NButton>
      </div>
    </div>

    <NAlert v-if="parseError" type="error" :show-icon="false" class="restore-error" data-testid="restore-parse-error">
      {{ parseError }}
    </NAlert>

    <template v-if="preview && pkg">
      <section class="restore-preview" aria-label="恢复内容摘要" data-testid="restore-preview">
        <h3>将要恢复的内容</h3>
        <ul class="restore-stats">
          <li>册数：<strong data-testid="preview-editions">{{ preview.editionCount }}</strong></li>
          <li>版本数：<strong data-testid="preview-versions">{{ preview.versionCount }}</strong></li>
          <li>当前指针版本：<strong>{{ preview.currentVersionCount }}</strong></li>
          <li>冲突：<strong data-testid="preview-conflicts">{{ preview.conflicts.length }}</strong></li>
        </ul>
        <p class="admin-note">
          导出人：{{ pkg.exportedBy || '（未记录）' }} · 导出时刻：{{ pkg.exportedAt || '（未记录）' }} ·
          包内版本计数：{{ pkg.versionCount }}
        </p>

        <NAlert v-if="manifestNote" type="warning" :show-icon="false" data-testid="preview-manifest-note">
          未能读取库内现状（{{ manifestNote }}），下面的冲突判定只覆盖包内自查；库内冲突会在恢复结果里逐条列出。
        </NAlert>

        <table class="restore-table">
          <thead>
            <tr><th>教材</th><th>版本</th><th>当前</th><th>判定</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr v-for="entry in preview.entries" :key="`${entry.editionId}@${entry.contentVersion}`">
              <td>{{ entry.editionId }}</td>
              <td>{{ entry.contentVersion }}</td>
              <td>{{ entry.current ? '是' : '' }}</td>
              <td>
                <NTag size="small" :type="verdictType(entry.verdict)" :bordered="false">{{ verdictLabel(entry.verdict) }}</NTag>
              </td>
              <td class="sub">{{ entry.reason }}</td>
            </tr>
          </tbody>
        </table>

        <NAlert v-if="preview.conflicts.length" type="error" :show-icon="false" data-testid="preview-conflict-list">
          <p>预计冲突（这些版本不会被覆盖）：</p>
          <ul>
            <li v-for="item in preview.conflicts" :key="`c-${item.editionId}@${item.contentVersion}`">
              {{ item.editionId }}@{{ item.contentVersion }} —— {{ item.reason }}
            </li>
          </ul>
        </NAlert>
        <NAlert v-else type="success" :show-icon="false">包内自查与库内比对都没有发现冲突。</NAlert>
      </section>

      <section class="restore-confirm" aria-label="二次确认">
        <NCheckbox v-model:checked="confirmed" data-testid="restore-confirm-checkbox">
          我确认这是破坏性操作，将把包内版本写入当前数据库。
        </NCheckbox>
        <NButton
          type="error"
          :disabled="!canSubmit"
          :loading="restoring"
          data-testid="restore-submit"
          @click="confirmRestore"
        >
          确认恢复
        </NButton>
        <span v-if="!confirmed" class="admin-note">需先勾选上面的确认项，才会调用恢复接口。</span>
      </section>
    </template>

    <NAlert v-if="errorText" type="error" :show-icon="false" class="restore-error" data-testid="restore-error">
      恢复失败：{{ errorText }}
    </NAlert>

    <NSpin :show="restoring">
      <section v-if="report" class="restore-report" aria-label="恢复对账结果" data-testid="restore-report">
        <h3>恢复结果</h3>
        <ul class="restore-stats">
          <li>处理册数：<strong>{{ report.restoredEditions }}</strong></li>
          <li>新增版本：<strong data-testid="report-restored">{{ report.restoredVersions }}</strong></li>
          <li>跳过（完全相同）：<strong data-testid="report-skipped">{{ report.skippedVersions }}</strong></li>
          <li>冲突：<strong data-testid="report-conflicts">{{ report.conflicts.length }}</strong></li>
        </ul>

        <NAlert v-if="report.conflicts.length" type="error" :show-icon="false" data-testid="report-conflict-list">
          <p>以下版本发生冲突，<strong>没有被写入</strong>：</p>
          <ul>
            <li v-for="item in report.conflicts" :key="item">{{ item }}</li>
          </ul>
        </NAlert>
        <NAlert v-else type="success" :show-icon="false">恢复完成，服务端没有报告冲突。</NAlert>

        <table v-if="report.reconciliations.length" class="restore-table">
          <thead>
            <tr>
              <th>教材</th><th>当前版本</th><th>指针</th><th>正文哈希</th><th>来源快照</th><th>历史版本数</th><th>目录基线</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in report.reconciliations" :key="row.editionId">
              <td>{{ row.editionId }}</td>
              <td>{{ row.currentVersionId }}</td>
              <td>
                <NTag size="small" :type="row.currentPointerRestored ? 'success' : 'error'" :bordered="false">
                  {{ row.currentPointerRestored ? '一致' : '不一致' }}
                </NTag>
              </td>
              <td>
                <NTag size="small" :type="row.contentSha256Matches ? 'success' : 'error'" :bordered="false">
                  {{ row.contentSha256Matches ? '一致' : '不一致' }}
                </NTag>
              </td>
              <td>
                <NTag size="small" :type="row.sourceSnapshotMatches ? 'success' : 'error'" :bordered="false">
                  {{ row.sourceSnapshotMatches ? '一致' : '不一致' }}
                </NTag>
              </td>
              <td>{{ row.historyCount }}</td>
              <td class="sub">{{ row.catalogVersionBaseline || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </NSpin>
  </section>
</template>

<style scoped>
.admin-restore {
  padding: 20px;
  color: var(--ink);
  max-width: 1080px;
}
.restore-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.admin-note {
  color: var(--ink-muted, #666);
  margin: 4px 0 0;
}
.restore-warning,
.restore-error {
  margin: 12px 0;
}
.restore-input {
  display: grid;
  gap: 8px;
  margin: 16px 0;
}
.restore-file {
  font: inherit;
}
.restore-actions {
  display: flex;
  gap: 8px;
}
.restore-preview,
.restore-report,
.restore-confirm {
  margin-top: 20px;
}
.restore-confirm {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px;
  border: 1px solid var(--border, #e5e5e5);
  border-radius: 8px;
}
.restore-stats {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  margin: 8px 0;
  padding: 0;
  list-style: none;
}
.restore-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 12px;
}
.restore-table th,
.restore-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid var(--border, #e5e5e5);
  vertical-align: top;
}
.restore-table .sub {
  display: block;
  color: var(--ink-muted, #666);
  font-size: 13px;
}
@media (max-width: 720px) {

  /* 移动端命中区：设计第 176 行要求 44px。
     实测（2026-10-07，375×812）：按钮普遍高 40px —— 209 个可点击元素中 174 个 < 44px。
     这里在窄屏下把可点击控件的最小高度抬到 44px；**桌面尺寸不受影响**。 */
  /* 注意必须用 :deep()：这些是 Naive UI 的子组件，作用域样式默认选不中。
     另外图标类按钮（如「编辑」「打开目录」）高度够了但**宽度不足**，所以同时给 min-width。 */
  :deep(.n-button),
  button,
  :deep(.n-base-selection),
  :deep(.n-input),
  input,
  select,
  a[href] {
    min-height: 44px;
  }
  :deep(.n-button),
  button,
  [class*='drawer'],
  [aria-label*='目录'],
  [aria-label*='菜单'] {
    min-width: 44px;
  }
  /* Naive UI 的输入类组件**内部**才是真正的尺寸元素：
     外层 `.n-input` 只是容器，高度由内部 input/选择器标签决定。
     所以这里要选到内部元素，只给外层加 min-height 是选不中的（第 128 轮实测）。 */
  :deep(.n-input__input-el),
  :deep(.n-input__textarea-el),
  :deep(.n-base-selection-label),
  :deep(.n-base-selection-input) {
    min-height: 44px;
  }
  :deep(.n-input),
  :deep(.n-base-selection) {
    --n-height: 44px;
  }
  .restore-table thead {
    display: none;
  }
  .restore-table td {
    display: block;
    border: none;
    padding: 4px 0;
  }
  .restore-table tr {
    display: block;
    border: 1px solid var(--border, #e5e5e5);
    border-radius: 8px;
    padding: 10px;
    margin-bottom: 10px;
  }
}
</style>
