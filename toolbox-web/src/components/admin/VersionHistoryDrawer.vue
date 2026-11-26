<script setup lang="ts">
import { NAlert, NButton, NCheckbox, NDrawer, NDrawerContent, NInput, NSpin, NTag } from 'naive-ui';
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { AdminApiError } from '../../services/admin-api';
import {
  buildVersionBytesUrl, fetchVersionHistory, fetchVersionSourceSnapshot, repairVersionSourceSnapshot,
  type AdminSourceSnapshotRepairResult, type AdminVersionSummary
} from '../../services/admin-curriculum-api';

/**
 * 版本记录抽屉（设计文档 3.6 第 166 行）。
 *
 * 设计要求：「版本记录放右侧抽屉：版本号、发布人、时间、修改摘要和内容对比。
 * 首版提供只读历史；恢复某版将来应复制为新草稿发布，不回写旧版本。」
 *
 * 因此本组件**不做恢复类写操作**：没有"恢复该版本为新草稿"、没有下架、没有回滚。
 * 这里只提供四个动作：看历史、看修改摘要、下载该版原始字节、看该版来源快照。
 *
 * **唯一例外是"补录来源快照"**（设计第 297 行的来源元数据随不可变版本保存）：
 * 只对**没有快照**的版本出现，只补空、不覆盖、不回写原正文，并要求管理员填写补录依据
 * 与一次二次确认。有快照的版本**不显示**补录入口——设计要求事后不可修改。
 * 本组件也不提供"覆盖已有快照"的路径。
 *
 * **如实记录的缺口**（不编造）：
 * 1. 修改摘要：后端已把发布时填写的 `changeNote` 写入发布审计的 `summary`，
 *    并由 `VersionSummary.changeNote` 返回（`(edition_id, action='textbook.publish', to_version)` 关联）。
 *    本次改造之前发布的版本、以及发布时**没有填写**的版本仍然是"未记录"——
 *    界面照实显示，绝不编造一条摘要。
 * 2. 内容对比（设计 166 行与"修改摘要"并列的"内容对比"）：**本期不做**，
 *    抽屉底部如实标注，不做一个看起来像对比、实际只是并列展示的东西。
 */

const props = defineProps<{
  /** 当前教材标识；空串表示"新增教材"，此时没有历史可列。 */
  editionId: string;
}>();

/**
 * 抽屉是否可见。
 *
 * 用 `defineModel` 而不是"手写 prop + emit"：抽屉内部有**多条**关闭路径
 * （`VFocusTrap` 的 Esc、遮罩点击、右上角 ×），它们都通过 `update:show` 回来。
 * 手写时只要漏掉一处，就会出现"Esc 关不掉"的真缺陷。
 * 编辑页仍用 `@update:show` 接管"关闭后焦点回触发按钮"。
 */
const show = defineModel<boolean>('show', { required: true });

/** 历史列表；请求失败时保持为空，并由 `errorMessage` 负责说明。 */
const versions = ref<AdminVersionSummary[]>([]);
const loading = ref(false);
/** 加载失败的原文；**必须显示**，不能静默成空列表（那会谎称"没有历史"）。 */
const loadError = ref<unknown>(null);

/** 已展开的来源快照 JSON，按版本号存放。 */
const snapshotJson = ref<Record<string, string>>({});
/** 正在拉取快照的版本号。 */
const snapshotLoading = ref<string | null>(null);
/** 拉取快照的失败信息，按版本号存放。 */
const snapshotError = ref<Record<string, string>>({});

/** 当前展开补录表单的版本号；同一时刻只允许一个，避免两份依据串味。 */
const repairOpenFor = ref<string | null>(null);
/** 管理员填写的补录依据（必填）。 */
const repairBasis = ref('');
/** 二次确认勾选状态；写操作必须有这一道。 */
const repairConfirmed = ref(false);
/** 依据为空的提示；**闸门不依赖按钮的 `disabled`**，提示只是让用户知道为什么没发出去。 */
const repairBasisError = ref('');
/** 正在提交补录的版本号；null 表示没有在途补录。 */
const repairSubmitting = ref<string | null>(null);
/** 补录失败的原文；**必须显示**，不能静默。 */
const repairError = ref('');
/** 最近一次补录结果；含 `applied` / `overwroteOriginal` / `reason`。 */
const repairResult = ref<AdminSourceSnapshotRepairResult | null>(null);

/**
 * 把错误转成可显示的文案。
 *
 * `AdminApiError` 带稳定错误码；其它异常（例如网络层抛出）也要显示出来，
 * 不能只处理认识的那一种。
 */
function describeError(cause: unknown): string {
  if (cause instanceof AdminApiError) {
    return `${cause.code}（${cause.status}）：${cause.messageKey}`;
  }
  if (cause instanceof Error) return cause.message;
  return String(cause);
}

const errorMessage = computed(() => (loadError.value === null ? '' : describeError(loadError.value)));
const hasError = computed(() => loadError.value !== null);
/** 加载成功但一条历史都没有：必须给出空状态，不能是一片空白。 */
const isEmpty = computed(() => !loading.value && !hasError.value && versions.value.length === 0);

/**
 * 把版本号解析成可排序的数字三元组。
 *
 * 直接用字符串比较会让 `1.0.10` 排在 `1.0.9` 前面；版本号是点分数字，按段比较才对。
 *
 * @param version 版本号，如 `1.0.2`
 * @returns 数字段数组；非法段按 0 处理（不抛错，界面不能因为一个怪版本号整页失败）
 */
function versionParts(version: string): number[] {
  return version.split('.').map(part => {
    const parsed = Number(part);
    return Number.isFinite(parsed) ? parsed : 0;
  });
}

/**
 * 按发布时间**倒序**（设计：最新在前）；同一时刻发布的按版本号倒序，保证顺序稳定。
 *
 * 后端返回的顺序不保证（`listAllVersions` 按版本号排），因此排序放在界面这一层，
 * 并且**不改动服务端的返回值**。
 */
const orderedVersions = computed(() => [...versions.value].sort((left, right) => {
  if (left.publishedAt !== right.publishedAt) return right.publishedAt - left.publishedAt;
  const a = versionParts(left.contentVersion);
  const b = versionParts(right.contentVersion);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const diff = (b[index] ?? 0) - (a[index] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}));

/**
 * 格式化发布时刻。
 *
 * 服务端用 `0` 表示"未知"，**不能**把它渲染成 1970 年——那是一个具体的假时间。
 *
 * @param timestamp 毫秒时间戳；0 表示未知
 * @returns 可显示文本
 */
function formatPublishedAt(timestamp: number): string {
  if (!timestamp) return '时间未记录';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '时间未记录';
  return `${date.toLocaleString('zh-CN', { hour12: false })}（${timestamp}）`;
}

/** 修改摘要：服务端返回了就显示，没返回就如实说"未记录"。 */
function changeNoteOf(version: AdminVersionSummary): string {
  const note = version.changeNote;
  return typeof note === 'string' && note.trim() ? note.trim() : '未记录';
}

/** 正文长度；0 表示服务端没给字节（无法下载时要能看出来）。 */
function byteLengthText(version: AdminVersionSummary): string {
  return version.byteLength ? `${version.byteLength} 字节` : '字节数未记录';
}

/** 该版本的原始字节下载地址（同源 Cookie 会话，浏览器直接导航下载）。 */
function bytesUrl(version: AdminVersionSummary): string {
  return buildVersionBytesUrl(props.editionId, version.contentVersion);
}

/**
 * 拉取并展开某版本的来源快照。
 *
 * 再次点击收起；展开后再点也重新拉取（避免快照被补录后界面停留在旧内容）。
 *
 * @param version 目标版本
 */
async function toggleSnapshot(version: AdminVersionSummary): Promise<void> {
  const key = version.contentVersion;
  if (snapshotJson.value[key] !== undefined && snapshotLoading.value !== key) {
    const next = { ...snapshotJson.value };
    delete next[key];
    snapshotJson.value = next;
    return;
  }
  snapshotLoading.value = key;
  const nextErrors = { ...snapshotError.value };
  delete nextErrors[key];
  snapshotError.value = nextErrors;
  try {
    const snapshot = await fetchVersionSourceSnapshot(props.editionId, key);
    let formatted = snapshot.snapshotJson;
    try {
      formatted = JSON.stringify(JSON.parse(snapshot.snapshotJson), null, 2);
    } catch {
      // 快照不是合法 JSON 时**原样显示**：宁可显示原始文本，也不能假装没有快照。
      formatted = snapshot.snapshotJson;
    }
    snapshotJson.value = { ...snapshotJson.value, [key]: formatted };
  } catch (cause) {
    snapshotError.value = { ...snapshotError.value, [key]: describeError(cause) };
  } finally {
    if (snapshotLoading.value === key) snapshotLoading.value = null;
  }
}

/**
 * 展开／收起某版本的补录表单。
 *
 * 换版本时清空上一个版本的输入与结果：两份依据混在一起会让人以为在给另一个版本补录。
 * 只有**没有快照**的版本会渲染这个入口（模板里的 `v-if="!version.hasSourceSnapshot"`）。
 *
 * @param version 目标版本
 */
function toggleRepair(version: AdminVersionSummary): void {
  const key = version.contentVersion;
  if (repairOpenFor.value === key) {
    repairOpenFor.value = null;
    return;
  }
  repairOpenFor.value = key;
  repairBasis.value = '';
  repairConfirmed.value = false;
  repairBasisError.value = '';
  repairError.value = '';
  repairResult.value = null;
}

/**
 * 提交补录（**写入性操作**）。
 *
 * 三道闸门全部写在**这里**，也就是真正会发请求的函数里，而不是按钮的 `disabled` 上：
 * 1. **只补空**：有快照的版本即使被程序化调用也直接拒绝，不发请求；
 * 2. **依据必填**：空白依据一律拒绝——没有依据的补录就是一次谎称；
 * 3. **二次确认**：未勾选确认时拒绝。
 *
 * 为什么不能只靠 `disabled`：`disabled` 只是一个渲染属性，将来任何一处改动
 * （换个按钮、加个快捷键、脚本触发）都可能绕过它。闸门写在这里，绕过界面也发不出去。
 *
 * 成功后展示 `applied` / `overwroteOriginal` / `reason`，并**重新拉取历史**，
 * 让该版本的 `hasSourceSnapshot` 与真实状态一致（补录成功后入口随之消失）。
 *
 * @param version 目标版本（必须是 `hasSourceSnapshot === false` 的那一版）
 */
async function submitRepair(version: AdminVersionSummary): Promise<void> {
  const key = version.contentVersion;
  // 闸门一：只补空；有快照的版本不走补录，本界面也不提供覆盖路径。
  if (version.hasSourceSnapshot) {
    repairError.value = `版本 ${key} 已有来源快照：补录只补空，不覆盖、不改写既有快照。`;
    return;
  }
  const basis = repairBasis.value.trim();
  // 闸门二：依据必填。
  if (!basis) {
    repairBasisError.value = '补录依据必填：请写明可核实的同版留存来源（例如 sources/xx.json）。';
    return;
  }
  // 闸门三：二次确认。
  if (!repairConfirmed.value) {
    repairError.value = '需先勾选二次确认，才会调用补录接口。';
    return;
  }
  repairBasisError.value = '';
  repairError.value = '';
  repairResult.value = null;
  repairSubmitting.value = key;
  try {
    const result = await repairVersionSourceSnapshot(props.editionId, key, basis);
    repairResult.value = result;
    // 成功后收起表单，避免同一依据被再提交一次。
    repairOpenFor.value = null;
    // 刷新历史：该版本应变成"有快照"，不能停留在旧状态。
    await load();
  } catch (cause) {
    repairError.value = describeError(cause);
  } finally {
    if (repairSubmitting.value === key) repairSubmitting.value = null;
  }
}

/**
 * 把补录提交函数暴露给父组件引用（生产上父组件不使用它）。
 *
 * 暴露的唯一理由是**可验证性**：界面根本不为"有快照"的版本渲染补录入口，
 * 因此"有快照的版本即使被程序化调用也必须拒绝、绝不发请求"这条闸门
 * 无法通过点击触发来证明，只能直接调用该函数。
 * 它不会扩大任何真实可达路径——调用它的前提是拿到组件引用。
 */
defineExpose({ submitRepair });

/** 在途的历史／快照请求；抽屉关闭或切册时取消，避免过期响应写回界面。 */
let inFlight: AbortController | null = null;

/** 抽屉根节点（`NDrawerContent` 内部那个 `role="dialog"` 的容器）。 */
const drawerRootRef = ref<{ $el: HTMLElement } | null>(null);

/**
 * 打开抽屉后，把键盘焦点**显式送进抽屉**。
 *
 * 这一句是为了**确定性**，不是为了让测试通过：
 * `NDrawer` 自带的 `VFocusTrap`（`autoFocus`）在**真实浏览器**里确实会把焦点放到关闭按钮上
 * （实测见 `version-history-and-keyboard.md`，16/16 通过），
 * 但它在下面这种情形会退化——把焦点放到自己的**隐藏哨兵** `<div aria-hidden="true" tabindex="0">` 上：
 * `vueuc` 的 `isFocusable` 只认带 `tabindex` 属性的元素，而它靠"start 哨兵之后的第一个 DIV"
 * 定位对话框；当 `Transition` 被替换成占位节点时（`@vue/test-utils` 的默认 stub、
 * 也就是 jsdom 组件测试里），那个 DIV 变成 `focusableEnd` 哨兵本身，
 * 于是判定"对话框里没有可聚焦元素"，回落到哨兵。
 *
 * 那条哨兵在 `.n-drawer` 里、却在 `role="dialog"` 容器**外面**，
 * 键盘用户随后按 Tab 的落点就不可预期。
 * 所以这里自己找第一个**真的能用**的控件（关闭按钮，或抽屉里第一个按钮／链接），
 * 找不到时退回对话框容器本身；两条路径在真实浏览器里都落在抽屉内部。
 */
async function focusIntoDrawer(): Promise<void> {
  await nextTick();
  // `drawerRootRef` 是 `NDrawerContent` 的**组件实例**，真实 DOM 在 `$el` 上。
  const root = drawerRootRef.value?.$el;
  if (!root) return;
  const target = root.querySelector<HTMLElement>('.n-drawer-header__close')
    ?? root.querySelector<HTMLElement>('button:not([disabled]), a[href]')
    ?? root;
  target.focus({ preventScroll: true });
}

/** 拉取当前教材的历史；**错误必须落到 `loadError` 上**。 */
async function load(): Promise<void> {
  if (!props.editionId) {
    versions.value = [];
    loadError.value = null;
    return;
  }
  inFlight?.abort();
  const controller = new AbortController();
  inFlight = controller;
  loading.value = true;
  loadError.value = null;
  try {
    versions.value = await fetchVersionHistory(props.editionId, controller.signal);
  } catch (cause) {
    // 被取消的请求不算失败：抽屉已经关了，不该在界面上留一条错误。
    if (!controller.signal.aborted) {
      versions.value = [];
      loadError.value = cause;
    }
  } finally {
    if (inFlight === controller) {
      inFlight = null;
      loading.value = false;
    }
  }
}

/** 打开抽屉或换册时重新加载；关闭时取消在途请求。 */
watch([show, () => props.editionId], ([visible]) => {
  if (!visible) {
    inFlight?.abort();
    inFlight = null;
    loading.value = false;
    return;
  }
  snapshotJson.value = {};
  snapshotError.value = {};
  // 换册或重开时清掉半份补录表单与上一次的结果：不能把 A 册的依据留在 B 册上。
  repairOpenFor.value = null;
  repairBasis.value = '';
  repairConfirmed.value = false;
  repairBasisError.value = '';
  repairError.value = '';
  repairResult.value = null;
  void load();
  void focusIntoDrawer();
}, { immediate: true });

onUnmounted(() => {
  inFlight?.abort();
  inFlight = null;
});
</script>

<template>
  <NDrawer
    :show="show"
    placement="right"
    :width="520"
    @update:show="value => (show = value)"
  >
    <NDrawerContent ref="drawerRootRef" title="版本记录" closable :native-scrollbar="false">
      <div class="version-drawer" data-testid="version-drawer">
        <p class="admin-note">
          该册的全部已发布版本，按发布时间倒序。本抽屉是只读的：不提供恢复旧版、下架或回滚。
        </p>

        <NSpin :show="loading">
          <div v-if="loading" class="version-state" data-testid="version-loading">正在读取版本记录…</div>

          <NAlert
            v-else-if="hasError"
            type="error"
            :show-icon="false"
            data-testid="version-error"
            class="version-error"
          >
            读取版本记录失败：{{ errorMessage }}
          </NAlert>

          <div v-else-if="isEmpty" class="version-state" data-testid="version-empty">
            暂无版本记录（本册尚未发布过）。
          </div>

          <ul v-else class="version-list" data-testid="version-list">
            <li
              v-for="version in orderedVersions"
              :key="version.contentVersion"
              class="version-item"
              :data-version="version.contentVersion"
            >
              <div class="version-head">
                <NTag size="small" type="info" :bordered="false">{{ version.contentVersion }}</NTag>
                <span class="version-time">{{ formatPublishedAt(version.publishedAt) }}</span>
                <span class="version-by">发布人：{{ version.publishedBy || '未记录' }}</span>
              </div>
              <dl class="version-meta">
                <dt>修改摘要</dt>
                <dd :data-change-note="version.contentVersion">{{ changeNoteOf(version) }}</dd>
                <dt>原始字节</dt>
                <dd>{{ byteLengthText(version) }}</dd>
                <dt>来源快照</dt>
                <dd :data-has-snapshot="String(version.hasSourceSnapshot)">
                  <template v-if="version.hasSourceSnapshot">
                    有（SHA-256 {{ version.sourceSnapshotSha256 ?? '未记录' }}）
                  </template>
                  <template v-else>无</template>
                </dd>
              </dl>
              <div class="version-actions">
                <a
                  class="version-download"
                  :href="bytesUrl(version)"
                  :data-download-version="version.contentVersion"
                  download
                >下载该版本原始字节</a>
                <NButton
                  v-if="version.hasSourceSnapshot"
                  size="tiny"
                  :loading="snapshotLoading === version.contentVersion"
                  :data-snapshot-toggle="version.contentVersion"
                  @click="toggleSnapshot(version)"
                >
                  {{ snapshotJson[version.contentVersion] === undefined ? '查看来源快照' : '收起来源快照' }}
                </NButton>
                <template v-else>
                  <span class="admin-note">该版本没有来源快照</span>
                  <!-- 只有"没有快照"的版本才出现补录入口：设计要求来源元数据事后不可修改。 -->
                  <NButton
                    size="tiny"
                    :loading="repairSubmitting === version.contentVersion"
                    :data-repair-open="version.contentVersion"
                    @click="toggleRepair(version)"
                  >
                    {{ repairOpenFor === version.contentVersion ? '收起补录' : '补录来源快照' }}
                  </NButton>
                </template>
              </div>
              <section
                v-if="repairOpenFor === version.contentVersion"
                class="version-repair"
                :data-repair-form="version.contentVersion"
                aria-label="补录来源快照"
              >
                <p class="admin-note">
                  补录只补空：不覆盖既有快照、不回写原正文、也不声称原始发布时已保存；
                  补录时间与依据会写进快照（<code>origin=repair</code>）。
                </p>
                <div class="version-repair-field" data-testid="repair-basis">
                  <span class="version-repair-label">补录依据（必填）</span>
                  <NInput
                    v-model:value="repairBasis"
                    size="small"
                    placeholder="例如：同版留存文件 sources/cn-1-first-2024.json"
                    :status="repairBasisError ? 'error' : undefined"
                  />
                </div>
                <NCheckbox v-model:checked="repairConfirmed" data-testid="repair-confirm">
                  我确认这是写入性操作，且上面的补录依据是可核实的同版来源。
                </NCheckbox>
                <div class="version-repair-actions">
                  <NButton
                    type="primary"
                    size="small"
                    :disabled="!repairConfirmed || !repairBasis.trim()"
                    :loading="repairSubmitting === version.contentVersion"
                    data-testid="repair-submit"
                    :data-repair-submit-for="version.contentVersion"
                    @click="submitRepair(version)"
                  >
                    确认补录
                  </NButton>
                  <NButton size="small" data-testid="repair-cancel" @click="toggleRepair(version)">取消</NButton>
                </div>
                <NAlert
                  v-if="repairBasisError"
                  type="warning"
                  :show-icon="false"
                  data-testid="repair-basis-error"
                >
                  {{ repairBasisError }}
                </NAlert>
              </section>
              <NAlert
                v-if="snapshotError[version.contentVersion]"
                type="error"
                :show-icon="false"
                class="version-error"
              >
                读取来源快照失败：{{ snapshotError[version.contentVersion] }}
              </NAlert>
              <pre
                v-if="snapshotJson[version.contentVersion] !== undefined"
                class="version-snapshot"
                :data-snapshot-json="version.contentVersion"
              >{{ snapshotJson[version.contentVersion] }}</pre>
            </li>
          </ul>
        </NSpin>

        <NAlert
          v-if="repairError"
          type="error"
          :show-icon="false"
          class="version-error"
          data-testid="repair-error"
        >
          补录失败：{{ repairError }}
        </NAlert>

        <section v-if="repairResult" class="version-repair-result" data-testid="repair-result">
          <h3 class="version-repair-title">
            补录结果（{{ repairResult.contentVersion }}）
          </h3>
          <!-- `overwroteOriginal` 服务端恒为 false。为 true 即发生了本界面不提供的覆盖，
               必须显示为异常，绝不能当成成功。 -->
          <NAlert
            v-if="repairResult.overwroteOriginal"
            type="error"
            :show-icon="false"
            data-testid="repair-overwrite-anomaly"
          >
            异常：后端报告 overwroteOriginal=true，即既有快照被覆盖。
            本界面只补空、不提供任何覆盖路径，这不应发生；请人工核查该版本快照。
          </NAlert>
          <NAlert
            v-else-if="repairResult.applied"
            type="success"
            :show-icon="false"
            data-testid="repair-success"
          >
            已补录来源快照（未覆盖既有快照，原正文未改动）。
          </NAlert>
          <NAlert v-else type="warning" :show-icon="false" data-testid="repair-not-applied">
            未应用：本次补录没有写入任何内容（原因见下）。
          </NAlert>
          <dl class="version-meta">
            <dt>applied</dt>
            <dd data-testid="repair-applied">{{ String(repairResult.applied) }}</dd>
            <dt>overwroteOriginal</dt>
            <dd data-testid="repair-overwrote">{{ String(repairResult.overwroteOriginal) }}</dd>
            <dt>reason</dt>
            <dd data-testid="repair-reason">{{ repairResult.reason }}</dd>
            <dt>快照 SHA-256</dt>
            <dd data-testid="repair-snapshot-sha256">{{ repairResult.snapshot?.sha256 ?? '未返回' }}</dd>
          </dl>
        </section>

        <p class="admin-note version-gap">
          修改摘要：发布时填写的 <code>changeNote</code> 会随发布写入审计并显示在这里；
          发布时未填写、或本次改造之前发布的版本显示"未记录"——这是如实记录，不是界面漏读。
          内容对比（设计 166 行）本期不做。
        </p>
      </div>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.version-drawer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.version-state {
  padding: 16px 4px;
  color: var(--ink-soft, #666);
}

.version-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.version-item {
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 8px;
  padding: 10px 12px;
}

.version-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.version-by {
  font-size: 13px;
}

.version-meta {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 2px 8px;
  margin: 8px 0;
  font-size: 13px;
}

.version-meta dt {
  color: var(--ink-soft, #666);
}

.version-meta dd {
  margin: 0;
  word-break: break-all;
}

.version-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.version-snapshot {
  margin: 8px 0 0;
  padding: 8px;
  max-height: 260px;
  overflow: auto;
  background: rgba(0, 0, 0, 0.04);
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.version-error {
  margin-top: 8px;
}

.version-repair {
  margin-top: 10px;
  padding: 10px;
  border: 1px dashed var(--line, #e0e0e0);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.version-repair-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.version-repair-label {
  font-size: 13px;
  color: var(--ink-soft, #666);
}

.version-repair-actions {
  display: flex;
  gap: 10px;
}

.version-repair-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.version-repair-title {
  margin: 0;
  font-size: 14px;
}

.version-gap {
  border-top: 1px solid var(--line, #e0e0e0);
  padding-top: 8px;
}
</style>
