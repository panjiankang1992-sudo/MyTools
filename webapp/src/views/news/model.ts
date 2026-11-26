import { reactive } from 'vue';
import type {
  NewsApi,
  NewsCapabilities,
  NewsEdition,
  NewsEditionHeader,
  NewsPreview,
  NewsRegion,
  NewsRun,
  NewsSubscriptionState
} from '../../service/api/news.types';

export const newsCategories = ['TECH', 'WORLD', 'DOMESTIC', 'LOCAL'] as const;

/** 外部文章链接仅允许显式网页协议，正文始终作为文本渲染。 */
export function safeNewsUrl(value: string | null | undefined): string | undefined {
  try {
    const url = new URL(value || '');
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) return undefined;
    return url.href;
  } catch {
    return undefined;
  }
}

/** 提交前校验时区和分钟格式，服务端仍保留最终校验权。 */
export function validNewsSchedule(zone: string, time: string): boolean {
  if (!zone || zone.length > 64 || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time)) return false;
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone }).format();
    return true;
  } catch {
    return false;
  }
}

/** 只有尚未结束的运行允许请求取消。 */
export function canCancelNewsRun(run: NewsRun): boolean {
  return ['QUEUED', 'DISPATCHED'].includes(run.status);
}

/** 时间始终使用明确时区，错误日期不猜测成当前时间。 */
export function newsTime(value: string | null | undefined, zone = 'Asia/Shanghai', locale = 'zh-CN'): string {
  if (!value) return '—';
  try {
    return new Intl.DateTimeFormat(locale, {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23'
    }).format(new Date(value));
  } catch {
    return value;
  }
}

function errorCode(error: unknown): string {
  return error instanceof Error && 'code' in error && typeof error.code === 'string' ? error.code : 'UNAVAILABLE';
}

/** 管理页面状态与异步竞争，不缓存跨用户的正文或订阅。 */
export function createNewsWorkspace(
  api: NewsApi,
  key: () => string = () =>
    Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
) {
  const state = reactive({
    loading: false,
    saving: false,
    previewing: false,
    reading: false,
    refreshing: false,
    capabilities: null as NewsCapabilities | null,
    region: null as NewsRegion | null,
    subscription: null as NewsSubscriptionState | null,
    draft: { enabled: false, zoneId: 'Asia/Shanghai', cutoffTime: '07:30' },
    history: [] as NewsEditionHeader[],
    runs: [] as NewsRun[],
    edition: null as NewsEdition | null,
    selectedId: '',
    cancelId: '',
    conflict: false,
    saved: false,
    issues: {} as Record<string, string>,
    previewAttempt: null as NewsPreview | null
  });
  let disposed = false;
  let selection = 0;

  async function load<T>(name: string, task: () => Promise<T>, apply: (data: T) => void) {
    try {
      const data = await task();
      if (!disposed) {
        apply(data);
        delete state.issues[name];
      }
    } catch (error) {
      if (!disposed) state.issues[name] = errorCode(error);
    }
  }

  /** 版本冲突后必须显式载入新版本，不能自动覆盖用户正在编辑的草稿。 */
  async function reloadSubscription() {
    await load('subscription', api.subscription, data => {
      state.subscription = data;
      const value = data.subscription;
      Object.assign(
        state.draft,
        value
          ? { enabled: value.enabled, zoneId: value.zoneId, cutoffTime: value.cutoffTime }
          : { enabled: false, zoneId: 'Asia/Shanghai', cutoffTime: '07:30' }
      );
      state.conflict = false;
      state.saved = false;
      delete state.issues.save;
    });
  }

  async function refreshActivities() {
    if (state.refreshing || disposed) return;
    state.refreshing = true;
    await Promise.all([
      load('history', api.history, data => {
        state.history = data;
      }),
      load('runs', api.runs, data => {
        state.runs = data;
      })
    ]);
    state.refreshing = false;
  }

  async function refreshOverview() {
    await Promise.all([
      load('capabilities', api.capabilities, data => {
        state.capabilities = data;
      }),
      load('region', api.region, data => {
        state.region = data;
      }),
      refreshActivities()
    ]);
  }

  async function initialize() {
    if (state.loading || disposed) return;
    state.loading = true;
    await Promise.all([refreshOverview(), reloadSubscription()]);
    state.loading = false;
  }

  async function selectEdition(id: string) {
    const ticket = ++selection;
    state.selectedId = id;
    state.edition = null;
    delete state.issues.edition;
    if (!id) {
      state.reading = false;
      return;
    }
    state.reading = true;
    try {
      const data = await api.edition(id);
      // 快速切换时只呈现最后一次选择，旧请求不能替换标题或正文。
      if (!disposed && ticket === selection) state.edition = data;
    } catch (error) {
      if (!disposed && ticket === selection) state.issues.edition = errorCode(error);
    } finally {
      if (ticket === selection) state.reading = false;
    }
  }

  async function save() {
    if (state.saving || state.conflict || !state.subscription || disposed) return;
    state.saved = false;
    if (!validNewsSchedule(state.draft.zoneId.trim(), state.draft.cutoffTime)) {
      state.issues.save = 'INVALID_SCHEDULE';
      return;
    }
    state.saving = true;
    try {
      const value = await api.saveSubscription({
        expectedRevision: state.subscription.subscription?.revision ?? 0,
        enabled: state.draft.enabled,
        zoneId: state.draft.zoneId.trim(),
        cutoffTime: state.draft.cutoffTime
      });
      if (disposed) return;
      state.subscription.subscription = value;
      Object.assign(state.draft, { enabled: value.enabled, zoneId: value.zoneId, cutoffTime: value.cutoffTime });
      state.saved = true;
      delete state.issues.save;
      await refreshActivities();
    } catch (error) {
      if (!disposed) {
        state.issues.save = errorCode(error);
        state.conflict = state.issues.save === 'NEWS_014';
      }
    } finally {
      state.saving = false;
    }
  }

  async function preview() {
    if (state.previewing || !state.capabilities || disposed) return;
    state.previewing = true;
    const ticket = ++selection;
    state.reading = false;
    try {
      // 请求结果未知时沿用同一键和截止时刻，避免重试生成第二份日报。
      state.previewAttempt ??= { idempotencyKey: `web-preview:${key()}`, cutoff: state.capabilities.latestCutoff };
      const data = await api.preview({ ...state.previewAttempt });
      if (disposed) return;
      if (ticket === selection) {
        state.edition = data;
        state.selectedId = data.id;
        state.reading = false;
      }
      state.previewAttempt = null;
      delete state.issues.preview;
      await refreshActivities();
    } catch (error) {
      if (!disposed) state.issues.preview = errorCode(error);
    } finally {
      state.previewing = false;
    }
  }

  async function cancel(run: NewsRun) {
    if (state.cancelId || !canCancelNewsRun(run) || disposed) return;
    state.cancelId = run.id;
    try {
      const value = await api.cancel(run.id);
      if (disposed) return;
      state.runs = state.runs.map(item => (item.id === value.id ? value : item));
      delete state.issues.cancel;
      await refreshActivities();
    } catch (error) {
      if (!disposed) state.issues.cancel = errorCode(error);
    } finally {
      state.cancelId = '';
    }
  }

  function dispose() {
    disposed = true;
    selection++;
  }
  return {
    state,
    initialize,
    refreshOverview,
    reloadSubscription,
    refreshActivities,
    selectEdition,
    save,
    preview,
    cancel,
    dispose
  };
}
