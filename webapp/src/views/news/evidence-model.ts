import { reactive } from 'vue';
import type { NewsEvidenceApi, NewsEvidenceItem, NewsEvidenceSnapshot } from '../../service/api/news-evidence.types';

/** 引用查询失败不影响历史正文，切换日报或卸载后旧响应不能覆盖当前状态。 */
export function createEvidenceWorkspace(api: NewsEvidenceApi) {
  const state = reactive({
    editionId: '',
    checkedAt: '',
    items: {} as Record<string, NewsEvidenceItem>,
    loading: false,
    failed: false
  });
  let version = 0;
  let disposed = false;

  function valid(snapshot: NewsEvidenceSnapshot, id: string) {
    if (!snapshot || snapshot.editionId !== id || !Number.isFinite(Date.parse(snapshot.checkedAt)) || !Array.isArray(snapshot.items)) return false;
    const ids = new Set<string>();
    return snapshot.items.every(item => {
      if (!item || !item.articleId || ids.has(item.articleId) || !['AVAILABLE', 'RETENTION_EXPIRED', 'UNAVAILABLE'].includes(item.status)) return false;
      ids.add(item.articleId);
      return item.expiresAt === null || Number.isFinite(Date.parse(item.expiresAt));
    });
  }

  async function load(id: string) {
    if (disposed) return;
    const ticket = ++version;
    // 重新核对失败时不能继续将旧的可用状态显示为当前结果。
    Object.assign(state, { editionId: id, checkedAt: '', items: {}, loading: true, failed: false });
    try {
      const snapshot = await api.inspect(id);
      if (disposed || ticket !== version) return;
      if (!valid(snapshot, id)) throw new Error('INVALID_EVIDENCE_STATUS');
      state.items = Object.fromEntries(snapshot.items.map(item => [item.articleId, item]));
      state.checkedAt = snapshot.checkedAt;
    } catch {
      if (!disposed && ticket === version) state.failed = true;
    } finally {
      if (!disposed && ticket === version) state.loading = false;
    }
  }

  function dispose() {
    disposed = true;
    version += 1;
  }

  return { state, load, dispose };
}
