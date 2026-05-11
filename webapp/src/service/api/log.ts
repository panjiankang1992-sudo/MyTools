import { request } from '../request';

/** 获取API日志统计信息 */
export function fetchGetLogStatistics(params: { startTime?: string; endTime?: string }) {
  return request<Api.Log.StatisticsResponse>({
    url: '/api/logs/statistics',
    method: 'get',
    params
  });
}
