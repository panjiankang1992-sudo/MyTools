declare namespace Api {
  namespace Log {
    /** API日志统计响应 */
    interface StatisticsResponse {
      totalRequests: number;
      successRequests: number;
      failedRequests: number;
      avgDurationMs: number;
      hourlyStats: HourlyStat[];
      moduleStats: ModuleStat[];
    }

    interface HourlyStat {
      hour: string;
      count: number;
      successCount: number;
    }

    interface ModuleStat {
      module: string;
      count: number;
      percentage: number;
    }
  }
}
