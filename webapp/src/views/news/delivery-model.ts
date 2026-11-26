import { reactive } from 'vue';
import type {
  DeliveryBatch, DeliveryCapabilities, DeliveryDestination, DeliveryPart, DeliveryRetry,
  DeliveryTarget, DeliveryTargets, NewsDeliveryApi
} from '../../service/api/news-delivery.types';

const finished = new Set(['PROVIDER_ACCEPTED', 'CANCELLED', 'EXPIRED']);

/** 取消只表示停止请求，已开始的提供方调用仍须对账。 */
export function canStopDelivery(batch: DeliveryBatch): boolean {
  return !batch.cancelRequested && !finished.has(batch.status);
}

/** 重试受过期、停用、代数与待处理意图限制。 */
export function canRetryDelivery(batch: DeliveryBatch, part: DeliveryPart, now = Date.now()): boolean {
  return !batch.cancelRequested && Date.parse(batch.expiresAt) > now && !part.retryPending
    && part.generation < 100 && ['FAILED', 'UNKNOWN'].includes(part.status);
}

/** 只轮询可能自行变化或需要持续对账的批次。 */
export function needsDeliveryRefresh(batch: DeliveryBatch): boolean {
  return !['FAILED', 'REJECTED', ...finished].includes(batch.status)
    || batch.parts.some(part => part.retryPending);
}

function errorCode(error: unknown): string {
  return error instanceof Error && 'code' in error && typeof error.code === 'string' ? error.code : 'UNAVAILABLE';
}

/** 表单与后台轮询分离；刷新不会覆盖草稿，旧响应不能回退操作结果。 */
export function createNewsDeliveryWorkspace(api: NewsDeliveryApi) {
  const state = reactive({
    loading: false, refreshing: false, loadingTargets: false, saving: false, busy: '',
    capabilities: null as DeliveryCapabilities | null,
    destinations: [] as DeliveryDestination[],
    targets: null as DeliveryTargets | null,
    draft: [] as DeliveryTarget[],
    history: [] as DeliveryBatch[],
    issues: {} as Record<string, string>,
    conflict: false, saved: false,
    retryAttempts: {} as Record<string, DeliveryRetry>
  });
  let disposed = false;
  let historyVersion = 0;

  async function load<T>(name: string, work: () => Promise<T>, apply: (data: T) => void) {
    try {
      const data = await work();
      if (!disposed) { apply(data); delete state.issues[name]; }
    } catch (error) {
      if (!disposed) state.issues[name] = errorCode(error);
    }
  }

  function reconcileAttempts(batch: DeliveryBatch) {
    for (const part of batch.parts) {
      const attempt = state.retryAttempts[part.id];
      // 确认新代已建立或原意图已持久化后，才结束未知请求恢复。
      if (attempt && (part.generation > attempt.expectedGeneration || part.retryPending
        || batch.cancelRequested || finished.has(part.status))) delete state.retryAttempts[part.id];
    }
  }

  async function refreshHistory() {
    if (state.refreshing || disposed) return;
    const version = ++historyVersion;
    state.refreshing = true;
    try {
      const data = await api.history();
      if (!disposed && version === historyVersion) {
        state.history = data;
        data.forEach(reconcileAttempts);
        delete state.issues.history;
      }
    } catch (error) {
      if (!disposed && version === historyVersion) state.issues.history = errorCode(error);
    } finally { if (!disposed) state.refreshing = false; }
  }

  async function refreshOverview() {
    if (disposed) return;
    await load('capabilities', api.capabilities, value => { state.capabilities = value; });
    if (disposed) return;
    if (state.capabilities?.enabled) await load('destinations', api.destinations, value => { state.destinations = value; });
    await refreshHistory();
  }

  /** 只有显式重新载入才替换未保存草稿。 */
  async function reloadTargets() {
    if (state.loadingTargets || state.saving || disposed) return;
    state.loadingTargets = true;
    await load('targets', api.targets, value => {
      state.targets = value;
      state.draft = value.targets.map(target => ({ ...target }));
      state.saved = false;
      state.conflict = false;
      delete state.issues.save;
    });
    if (!disposed) state.loadingTargets = false;
  }

  async function initialize() {
    if (disposed || state.loading) return;
    state.loading = true;
    await Promise.all([reloadTargets(), refreshOverview()]);
    if (!disposed) state.loading = false;
  }

  function addTarget(id: string) {
    const target = state.destinations.find(item => item.id === id);
    if (disposed || state.saving || state.loadingTargets || !state.targets || !state.capabilities?.enabled
      || !target?.enabled || !state.capabilities.supportedChannels.includes(target.channel)
      || state.draft.length >= Math.min(5, state.capabilities.targetLimit)
      || state.draft.some(item => item.destinationId === id)) return;
    state.draft.push({ destinationId: id, channel: target.channel, mode: 'FULL', enabled: true });
    state.saved = false;
  }

  async function save() {
    if (disposed || state.saving || state.loadingTargets || state.conflict || !state.targets) return;
    state.saving = true;
    state.saved = false;
    const body = { expectedRevision: state.targets.revision, targets: state.draft.map(({ destinationId, mode, enabled }) => ({ destinationId, mode, enabled })) };
    try {
      const value = await api.saveTargets(body);
      if (disposed) return;
      state.targets = value;
      state.draft = value.targets.map(target => ({ ...target }));
      state.saved = true;
      delete state.issues.save;
      await refreshHistory();
    } catch (error) {
      if (!disposed) {
        state.issues.save = errorCode(error);
        state.conflict = state.issues.save === 'NEWS_022';
      }
    } finally { if (!disposed) state.saving = false; }
  }

  async function operate(key: string, action: () => Promise<DeliveryBatch>) {
    if (disposed || state.busy) return;
    state.busy = key;
    ++historyVersion;
    try {
      const batch = await action();
      if (disposed) return;
      // 操作期间启动的旧查询也必须作废。
      ++historyVersion;
      state.history = state.history.map(item => item.id === batch.id ? batch : item);
      reconcileAttempts(batch);
      delete state.issues.action;
    } catch (error) {
      if (!disposed) state.issues.action = errorCode(error);
    } finally { if (!disposed) state.busy = ''; }
  }

  async function cancel(batch: DeliveryBatch) {
    if (!canStopDelivery(batch)) return;
    await operate(`cancel:${batch.id}`, () => api.cancel(batch.id));
  }

  async function retry(batch: DeliveryBatch, part: DeliveryPart, acknowledge = false) {
    if (disposed || state.busy || !state.capabilities?.enabled || !canRetryDelivery(batch, part)) return;
    const original = state.retryAttempts[part.id];
    if (!original && part.status === 'UNKNOWN' && !acknowledge) return;
    // 请求失败后保留原代数和风险确认，不根据旧列表生成新的重试。
    const attempt = original || { expectedGeneration: part.generation, acknowledgeDuplicateRisk: acknowledge };
    state.retryAttempts[part.id] = { ...attempt };
    await operate(`retry:${part.id}`, () => api.retry(batch.id, part.id, { ...attempt }));
  }

  async function redrive(batch: DeliveryBatch, part: DeliveryPart) {
    if (batch.cancelRequested || part.status !== 'REJECTED' || Date.parse(batch.expiresAt) <= Date.now()) return;
    await operate(`redrive:${part.id}`, () => api.redrive(batch.id, part.id));
  }

  return { state, initialize, refreshOverview, refreshHistory, reloadTargets, addTarget, save, cancel, retry, redrive,
    dispose() { disposed = true; ++historyVersion; }
  };
}
