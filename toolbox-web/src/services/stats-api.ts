import type { ToolCategory } from '../types';
import { publicRequest, type EventResultPayload, type UsageEventPayload, postEvents } from './public-api';

/** 全站聚合统计客户端。 */

/** 统计分类过滤值。 */
export type StatsCategory = 'all' | ToolCategory;

/** 折线图单日数据。 */
export interface StatsSeriesPoint {
  date: string;
  /** 聚合桶不完整时为 null，界面必须与真实 0 区分。 */
  clicks: number | null;
  success: number | null;
  isToday: boolean;
  complete: boolean;
}

/** 排行项：字段与服务端 StatsRankingEntry 一致，只含工具标识与成功次数。 */
export interface StatsRankingItem {
  toolId: string;
  success: number;
}

/** 排行视图行，包含由本地工具注册表推导的分类。 */
export interface StatsRankingRow {
  toolId: string;
  name: string;
  route: string;
  category: ToolCategory | null;
  count: number;
}

/**
 * 把接口排行转换为视图行。
 * 分类由本地工具注册表推导而不是依赖接口字段，成功次数按数字归一化，
 * 避免字段缺失时在渲染阶段抛错。
 */
export function toRankingRows(ranking: readonly StatsRankingItem[] | undefined, lookup: (toolId: string) => { category: ToolCategory; route: string; labelKey: string } | undefined, label: (key: string) => string): StatsRankingRow[] {
  return (ranking ?? []).map(item => {
    const tool = lookup(item.toolId);
    return {
      toolId: item.toolId,
      name: tool ? label(tool.labelKey) : item.toolId,
      route: tool?.route ?? '/',
      category: tool?.category ?? null,
      count: Number.isFinite(Number(item.success)) ? Number(item.success) : 0
    };
  });
}

/** 首页统计响应。 */
export interface StatsResponse {
  timezone: string;
  generatedAt: string;
  completeThrough: string | null;
  partial: boolean;
  today: string;
  totals: {
    todayClicks: number;
    todaySuccess: number;
    allTimeSuccess: number;
    todayPrintRequests: number;
  };
  series: StatsSeriesPoint[];
  ranking: StatsRankingItem[];
}

/** 读取全站聚合统计；失败时抛出错误，由界面显示局部的不可用状态。 */
export async function fetchStats(days: 7 | 30, category: StatsCategory): Promise<StatsResponse> {
  const params = new URLSearchParams({ days: String(days), category });
  return publicRequest<StatsResponse>(`/stats?${params.toString()}`);
}

/** 上报一批使用事件。 */
export async function reportEvents(events: readonly UsageEventPayload[]): Promise<EventResultPayload[]> {
  return postEvents(events);
}
