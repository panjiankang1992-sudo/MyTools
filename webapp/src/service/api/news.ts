import type { CustomAxiosRequestConfig } from '@sa/axios';
import { request } from '../request';
import type { NewsApi } from './news.types';
import type { NewsDeliveryApi } from './news-delivery.types';
import type { NewsEvidenceApi } from './news-evidence.types';

/** 只传递稳定错误码，界面不渲染服务端异常正文。 */
export class NewsRequestError extends Error {
  readonly code: string;

  /** 保存稳定业务错误码供界面解释。 */
  constructor(code: string) {
    super(code);
    this.code = code;
  }
}

/** 新闻请求复用登录令牌及失效处理，接口始终经 Gateway。 */
async function call<T>(path: string, config: CustomAxiosRequestConfig = {}): Promise<T> {
  const result = await request<T>({
    ...config,
    url: `/api/v1/news${path}`,
    // 默认使用网页同源的 Gateway 路由，开发时通过专属代理连接。
    baseURL: '/',
    timeout: 30000,
    'axios-retry': { retries: 0 }
  });
  if (result.error || result.data === null) {
    const code = result.response?.data?.code;
    throw new NewsRequestError(typeof code === 'string' ? code : 'UNAVAILABLE');
  }
  return result.data;
}

export const newsApi: NewsApi = {
  capabilities: () => call('/capabilities'),
  region: () => call('/local-region'),
  subscription: () => call('/subscription'),
  saveSubscription: data => call('/subscription', { method: 'put', data }),
  history: () => call('/editions', { params: { limit: 100 } }),
  edition: id => call(`/editions/${encodeURIComponent(id)}`),
  runs: () => call('/runs', { params: { limit: 30 } }),
  preview: data => call('/previews', { method: 'post', data }),
  cancel: id => call(`/runs/${encodeURIComponent(id)}/cancel`, { method: 'post' })
};

/** 投递接口仅使用不透明目标标识，所有者来自登录态。 */
export const newsDeliveryApi: NewsDeliveryApi = {
  capabilities: () => call('/delivery-capabilities'),
  destinations: () => call('/destinations'),
  targets: () => call('/delivery-targets'),
  saveTargets: data => call('/delivery-targets', { method: 'put', data }),
  history: () => call('/deliveries'),
  cancel: id => call(`/deliveries/${encodeURIComponent(id)}/cancel`, { method: 'post' }),
  retry: (id, part, data) => call(`/deliveries/${encodeURIComponent(id)}/parts/${encodeURIComponent(part)}/retry`, { method: 'post', data }),
  redrive: (id, part) => call(`/deliveries/${encodeURIComponent(id)}/parts/${encodeURIComponent(part)}/redrive`, { method: 'post' })
};

/** 引用状态单独查询，历史日报正文保持原样。 */
export const newsEvidenceApi: NewsEvidenceApi = {
  inspect: id => call(`/editions/${encodeURIComponent(id)}/evidence`)
};
