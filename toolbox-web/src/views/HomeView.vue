<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { NAlert, NButton, NCard, NDataTable, NIcon, NRadioButton, NRadioGroup, NSelect, NSkeleton, NTag, useMessage } from 'naive-ui';
import { ArrowForwardOutline, CheckmarkCircleOutline, HandLeftOutline, PrintOutline, StatsChartOutline } from '@vicons/ionicons5';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { commonLabel, text } from '../locales/common';
import { toolLabel } from '../locales/tools';
import { clearLocalUsage, dateKey, recordEntryClick, useUsageEvents } from '../lib/analytics';
import { fetchStats, toRankingRows, type StatsCategory, type StatsResponse } from '../services/stats-api';
import { allowsDemoData } from '../config/env';
import { CATEGORY_DEFINITIONS, findTool, toolsByCategory } from '../config/tools';
import { toolIcon } from '../config/icons';
import type { ToolCategory } from '../types';

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);
const router = useRouter();
const message = useMessage();

/** 统计范围：全站为默认，开发构建额外提供本机统计。 */
const scope = ref<'global' | 'local'>('global');
const period = ref<7 | 30>(7);
const category = ref<StatsCategory>('all');
const showTable = ref(false);
const stats = ref<StatsResponse | null>(null);
const statsState = ref<'loading' | 'ready' | 'unavailable'>('loading');
const statsError = ref('');
const chartEl = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;
let observer: ResizeObserver | undefined;
let midnightTimer: ReturnType<typeof setTimeout> | undefined;
const events = useUsageEvents();
const today = ref(dateKey(new Date()));

const categories = [
  { label: text.all, value: 'all' },
  { label: commonLabel('pdf'), value: 'pdf' },
  { label: commonLabel('copybook'), value: 'copybook' },
  { label: commonLabel('words'), value: 'words' }
];

/** 开发构建在接口不可用时展示明确标识的演示数据，正式构建不回退。 */
const demoStats = computed<StatsResponse>(() => {
  const points = Array.from({ length: period.value }, (_, index) => {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() - period.value + index + 1);
    const key = dateKey(date);
    const weight = category.value === 'all' ? 1 : category.value === 'pdf' ? 0.5 : 0.25;
    return {
      date: key,
      clicks: Math.round((220 + index * 24 + Math.sin(index * 1.3) * 36) * weight),
      success: Math.round((146 + index * 16 + Math.sin(index * 1.3) * 17) * weight),
      isToday: index === period.value - 1,
      complete: true
    };
  });
  return {
    timezone: 'Asia/Shanghai',
    generatedAt: new Date().toISOString(),
    completeThrough: new Date().toISOString(),
    partial: false,
    today: today.value,
    totals: { todayClicks: 286, todaySuccess: 128, allTimeSuccess: 12680, todayPrintRequests: 76 },
    series: points,
    ranking: [
      { toolId: 'pdf.crop', success: 2436 },
      { toolId: 'worksheet.chinese-dictation', success: 1892 },
      { toolId: 'worksheet.english-dictation', success: 1206 },
      { toolId: 'pdf.merge', success: 892 },
      { toolId: 'pdf.images', success: 688 }
    ]
  };
});

/** 本机统计由浏览器事件计算，仅作开发辅助。 */
const localStats = computed<StatsResponse>(() => {
  const scoped = events.value.filter(event => category.value === 'all' || event.category === category.value);
  const dayOf = (value: string) => dateKey(new Date(value));
  const series = Array.from({ length: period.value }, (_, index) => {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() - period.value + index + 1);
    const key = dateKey(date);
    const matching = scoped.filter(event => dayOf(event.occurredAt) === key);
    return {
      date: key,
      clicks: matching.filter(event => event.eventType === 'entry_click').length,
      success: matching.filter(event => event.eventType === 'worksheet_success').length,
      isToday: key === today.value,
      complete: true
    };
  });
  const current = scoped.filter(event => dayOf(event.occurredAt) === today.value);
  return {
    timezone: 'Asia/Shanghai',
    generatedAt: new Date().toISOString(),
    completeThrough: new Date().toISOString(),
    partial: false,
    today: today.value,
    totals: {
      todayClicks: current.filter(event => event.eventType === 'entry_click').length,
      todaySuccess: current.filter(event => event.eventType === 'worksheet_success').length,
      allTimeSuccess: scoped.filter(event => event.eventType === 'worksheet_success').length,
      todayPrintRequests: current.filter(event => event.eventType === 'print_request').length
    },
    series,
    ranking: []
  };
});

/** 当前实际展示的统计来源。 */
const view = computed<StatsResponse | null>(() => {
  if (scope.value === 'local') return localStats.value;
  if (statsState.value === 'ready') return stats.value;
  if (statsState.value === 'unavailable' && allowsDemoData()) return demoStats.value;
  return null;
});

const isDemo = computed(() => scope.value === 'global' && statsState.value === 'unavailable' && allowsDemoData());
const isLocal = computed(() => scope.value === 'local');

const series = computed(() => view.value?.series ?? []);
const metrics = computed(() => {
  const totals = view.value?.totals;
  return [
    { label: text.todayClicks, value: totals?.todayClicks ?? 0, icon: HandLeftOutline, color: 'blue' },
    { label: text.todayUse, value: totals?.todaySuccess ?? 0, icon: CheckmarkCircleOutline, color: 'green' },
    { label: text.allUse, value: totals?.allTimeSuccess ?? 0, icon: StatsChartOutline, color: 'purple' },
    { label: text.todayPrint, value: totals?.todayPrintRequests ?? 0, icon: PrintOutline, color: 'orange' }
  ];
});

/** 排行使用与图表相同的周期与分类，点击名称进入对应工具。 */
const rankingRows = computed(() => toRankingRows(view.value?.ranking, findTool, toolLabel));
const rankings = computed(() => rankingRows.value);

/** 首页快捷入口展示当前范围内的成功使用次数。 */
const quickTools = computed(() => CATEGORY_DEFINITIONS
  .filter(item => item.id !== 'home')
  .map(item => {
    const categoryId = item.id as ToolCategory;
    const count = rankingRows.value.filter(entry => entry.category === categoryId).reduce((sum, entry) => sum + entry.count, 0);
    return {
      id: categoryId,
      title: commonLabel(item.labelKey),
      description: commonLabel(item.descriptionKey),
      icon: toolIcon(item.icon),
      route: item.route,
      count,
      enabled: toolsByCategory(categoryId).length > 0
    };
  }));

/** 图表的可访问文字摘要，图表不是唯一的信息入口。 */
const summary = computed(() => series.value
  .map(point => `${point.date}: ${commonLabel('clicks')} ${point.clicks ?? '—'} / ${commonLabel('success')} ${point.success ?? '—'}`)
  .join('；'));

const columns = [
  { title: text.date, key: 'date' },
  { title: text.clicks, key: 'clicks' },
  { title: text.success, key: 'success' }
];

/** 加载全站统计，失败时保留工具入口可用。 */
async function loadStats(): Promise<void> {
  statsState.value = 'loading';
  statsError.value = '';
  try {
    stats.value = await fetchStats(period.value, category.value);
    statsState.value = 'ready';
  } catch (cause) {
    stats.value = null;
    statsError.value = cause instanceof Error ? cause.message : 'request.failed';
    statsState.value = 'unavailable';
  }
}

/** 跨零点或页面重新可见时刷新日期与数据。 */
function scheduleMidnightRefresh(): void {
  clearTimeout(midnightTimer);
  const now = new Date();
  const next = new Date(now);
  next.setHours(24, 0, 5, 0);
  midnightTimer = setTimeout(() => {
    today.value = dateKey(new Date());
    void loadStats();
    scheduleMidnightRefresh();
  }, Math.max(1000, next.getTime() - now.getTime()));
}

/** 从首页入口进入工具并记录一次点击。 */
function openTool(route: string, toolId: string | null): void {
  const category = route.split('/')[1] as ToolCategory;
  if (['pdf', 'copybook', 'words'].includes(category)) recordEntryClick(category, toolId);
  void router.push(route);
}

/** 用真实图表组件更新数据，保留可访问的表格入口。 */
function updateChart(): void {
  if (!chartEl.value) return;
  chart ??= echarts.init(chartEl.value);
  chart.setOption({
    animation: !matchMedia('(prefers-reduced-motion: reduce)').matches,
    grid: { left: 42, right: 16, top: 24, bottom: 68 },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 3, itemWidth: 9, itemHeight: 9, icon: 'circle', textStyle: { color: '#7a8597', fontSize: 12 } },
    xAxis: { type: 'category', boundaryGap: false, data: series.value.map(point => point.date.slice(5).replace('-', '/')), axisLine: { lineStyle: { color: '#e7ecf2' } }, axisTick: { show: false }, axisLabel: { color: '#8b96a8', margin: 14, hideOverlap: true } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#8b96a8' }, splitLine: { lineStyle: { color: '#edf1f6' } } },
    series: [
      { name: text.clicks, type: 'line', data: series.value.map(point => point.clicks), smooth: 0.22, symbolSize: 6, lineStyle: { width: 2 }, itemStyle: { color: '#646cff' }, connectNulls: false },
      { name: text.success, type: 'line', data: series.value.map(point => point.success), smooth: 0.22, symbolSize: 6, lineStyle: { width: 2 }, itemStyle: { color: '#18b781' }, connectNulls: false }
    ]
  });
}

watch(series, async () => { await nextTick(); updateChart(); });
watch([period, category, scope], () => { if (scope.value === 'global') void loadStats(); else void nextTick(updateChart); });
onMounted(() => {
  void loadStats();
  scheduleMidnightRefresh();
  updateChart();
  observer = new ResizeObserver(() => chart?.resize());
  if (chartEl.value) observer.observe(chartEl.value);
  document.addEventListener('visibilitychange', onVisible);
});
onActivated(() => { void nextTick(() => chart?.resize()); });
onBeforeUnmount(() => {
  clearTimeout(midnightTimer);
  document.removeEventListener('visibilitychange', onVisible);
  observer?.disconnect();
  chart?.dispose();
});

/** 页面重新可见时刷新日期，跨零点后不显示旧的自然日。 */
function onVisible(): void {
  if (document.visibilityState !== 'visible') return;
  const current = dateKey(new Date());
  if (current !== today.value) {
    today.value = current;
    scheduleMidnightRefresh();
    void loadStats();
  }
}

/** 清空本机统计与待上报队列。 */
function clearLocal(): void {
  clearLocalUsage();
  message.success(text.cleared);
}
</script>

<template>
  <section class="home-page">
    <div class="home-heading">
      <div><h2>{{ text.hero }}</h2><p>{{ text.open }}</p></div>
      <NTag v-if="isDemo" size="small" type="warning" :bordered="false">{{ text.demoHint }}</NTag>
    </div>

    <div class="scope-row">
      <NRadioGroup v-model:value="scope" size="small" :aria-label="text.scope">
        <NRadioButton value="global">{{ text.globalStats }}</NRadioButton>
        <NRadioButton v-if="allowsDemoData()" value="local">{{ text.localStats }}</NRadioButton>
      </NRadioGroup>
      <span>{{ isLocal ? text.localHint : text.scopeHint }}</span>
    </div>

    <NAlert v-if="scope === 'global' && statsState === 'unavailable'" type="warning" :show-icon="false" class="stats-alert">
      <div class="stats-alert-body">
        <div><strong>{{ text.statsUnavailable }}</strong><span>{{ text.statsUnavailableHint }}</span><span v-if="statsError" class="stats-error">{{ statsError }}</span></div>
        <NButton size="small" @click="loadStats">{{ text.reload }}</NButton>
      </div>
    </NAlert>
    <NAlert v-else-if="view?.partial" type="info" :show-icon="false" class="stats-alert">{{ text.statsPartial }}</NAlert>

    <div class="metric-grid">
      <NCard v-for="metric in metrics" :key="metric.label" :bordered="false" class="metric-card">
        <div v-if="statsState === 'loading' && scope === 'global'" class="metric-skeleton"><NSkeleton text style="width: 60%" /><NSkeleton text style="width: 40%; height: 28px" /></div>
        <div v-else class="metric-body"><span class="metric-label">{{ metric.label }}</span><strong>{{ metric.value.toLocaleString() }}</strong></div>
        <span class="metric-icon" :class="metric.color"><NIcon :component="metric.icon" size="29" /></span>
      </NCard>
    </div>
    <p v-if="scope === 'global' && !isLocal" class="metric-note">{{ text.allTimeNote }}</p>

    <div class="chart-row">
      <NCard :bordered="false" class="trend-card">
        <div class="section-heading">
          <h3>{{ text.trend }}</h3>
          <NRadioGroup v-model:value="period" size="small" :aria-label="text.statPeriod"><NRadioButton :value="7">{{ text.seven }}</NRadioButton><NRadioButton :value="30">{{ text.thirty }}</NRadioButton></NRadioGroup>
        </div>
        <div class="chart-filters">
          <NSelect v-model:value="category" size="small" :options="categories" :aria-label="text.filter" />
          <NButton text size="small" @click="showTable = !showTable">{{ text.table }}</NButton>
        </div>
        <div ref="chartEl" class="trend-chart" role="img" :aria-label="`${text.trend}: ${summary}`"></div>
        <p class="chart-summary">{{ text.summary }}：{{ summary }}</p>
        <p class="chart-meta">{{ text.dateInfo }}<template v-if="view?.generatedAt"> · {{ text.statsUpdatedAt }} {{ new Date(view.generatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</template><template v-if="series.some(point => point.isToday)"> · {{ text.statsTodayOpen }}</template></p>
        <NDataTable v-if="showTable" :columns="columns" :data="series" size="small" :max-height="260" :bordered="false" />
      </NCard>
      <NCard :bordered="false" class="ranking-card">
        <div class="section-heading"><h3>{{ text.rank }}</h3><span class="small-muted">{{ text.rankingScope }}</span></div>
        <div v-if="!rankings.length" class="ranking-empty">{{ isLocal ? text.noRank : text.noStats }}</div>
        <button v-for="(item, index) in rankings" :key="item.toolId" class="rank-row" @click="openTool(item.route, item.toolId)">
          <span class="rank-number" :class="`rank-${index}`">{{ index + 1 }}</span>
          <span class="rank-name">{{ item.name }}</span>
          <span class="rank-count">{{ item.count.toLocaleString() }}</span>
        </button>
        <NButton v-if="isLocal" text size="small" class="clear-local" @click="clearLocal">{{ text.clearLocalData }}</NButton>
      </NCard>
    </div>

    <section class="quick-tools">
      <h3>{{ text.quick }}</h3><p>{{ text.quickSub }}</p>
      <div class="tool-grid">
        <button v-for="tool in quickTools" :key="tool.id" class="tool-entry" :disabled="!tool.enabled" @click="openTool(tool.route, null)">
          <span class="tool-icon" :class="tool.id === 'pdf' ? 'red' : tool.id === 'copybook' ? 'blue' : 'green'"><NIcon :component="tool.icon" size="31" /></span>
          <span class="tool-copy"><strong>{{ tool.title }}</strong><small>{{ tool.description }}</small><small v-if="!isLocal && tool.count" class="tool-count">{{ tool.count.toLocaleString() }} {{ text.success }}</small></span>
          <NIcon :component="ArrowForwardOutline" size="21" class="tool-arrow" />
        </button>
      </div>
      <p class="pdf-note">{{ text.execLocal }}</p>
    </section>
  </section>
</template>

<style scoped>
.home-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 8px 0 22px; }
.home-heading h2 { font-size: clamp(24px, 2.45vw, 36px); line-height: 1.45; letter-spacing: -.8px; margin: 0 0 6px; font-weight: 750; }
.home-heading p { font-size: 16px; color: var(--muted); margin: 0; }
.scope-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.scope-row > span { color: var(--muted); font-size: 12px; }
.stats-alert { margin-bottom: 14px; border-radius: 8px; }
.stats-alert-body { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.stats-alert-body strong { margin-right: 8px; }
.stats-alert-body span { color: var(--muted); font-size: 12px; }
.stats-error { display: block; margin-top: 4px; font-family: ui-monospace, monospace; }
.metric-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 16px; margin-bottom: 10px; }
.metric-card { border-radius: 8px; }
.metric-card :deep(.n-card-content) { display: flex; align-items: center; justify-content: space-between; padding: 24px 20px; gap: 8px; }
.metric-body { min-width: 0; }
.metric-skeleton { flex: 1; display: grid; gap: 8px; }
.metric-label { display: block; color: var(--muted); font-size: 14px; white-space: nowrap; }
.metric-card strong { display: block; font-size: 28px; line-height: 1.6; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums; }
.metric-icon { display: grid; place-items: center; border-radius: 50%; width: 54px; height: 54px; flex-shrink: 0; }
.metric-note { font-size: 11px; color: var(--muted); margin: 0 0 18px; }
.blue { color: #5284f7; background: #edf4ff; }.green { color: #19af7d; background: #eaf9f3; }.purple { color: #8070f5; background: #f0edff; }.orange { color: #ed973a; background: #fff3e6; }.red { color: #f16974; background: #fff0f2; }
.chart-row { display: grid; grid-template-columns: minmax(0,1.75fr) minmax(280px,1fr); gap: 16px; }
.chart-row :deep(.n-card-content) { padding: 22px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-heading h3 { font-size: 19px; margin: 0; font-weight: 700; color: var(--ink); }
.small-muted { font-size: 12px; color: var(--muted); }
.chart-filters { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; }
.chart-filters .n-select { width: 130px; }
.trend-chart { width: 100%; height: 282px; margin-top: 8px; }
.chart-summary { font-size: 12px; color: var(--muted); line-height: 1.8; margin: 8px 0 0; }
.chart-meta { font-size: 11px; color: var(--muted); margin: 4px 0 0; }
.rank-row { width: 100%; background: none; border: 0; border-bottom: 1px solid var(--border); cursor: pointer; display: flex; align-items: center; gap: 12px; padding: 16px 0; text-align: left; color: var(--ink); min-height: 44px; }
.rank-row:first-of-type { margin-top: 12px; }.rank-row:last-of-type { border-bottom: 0; }.rank-row:hover .rank-name { color: #646cff; }
.rank-number { background: var(--canvas); color: var(--muted); border-radius: 50%; width: 26px; height: 26px; display: grid; place-items: center; font-size: 14px; flex-shrink: 0; }
.rank-0 { background: #fff0f2; color: #f16b7a; }.rank-1 { background: #fff2e3; color: #e9a04d; }.rank-2 { background: #fff6e2; color: #dcad42; }
.rank-name { font-size: 14px; }.rank-count { margin-left: auto; font-size: 14px; font-variant-numeric: tabular-nums; }.ranking-empty { padding: 60px 12px; text-align: center; color: var(--muted); }
.clear-local { margin-top: 14px; }
.quick-tools { margin-top: 25px; }.quick-tools h3 { font-size: 22px; margin: 0 0 4px; font-weight: 700; }.quick-tools > p { font-size: 14px; color: var(--muted); margin: 0 0 16px; }
.tool-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 16px; }
.tool-entry { cursor: pointer; background: var(--surface); color: var(--ink); border: 1px solid transparent; border-radius: 8px; padding: 24px 20px; display: flex; gap: 15px; align-items: center; text-align: left; transition: border-color .15s, transform .15s; min-height: 44px; }
.tool-entry:hover { border-color: #a5aaff; transform: translateY(-2px); }.tool-entry:disabled { opacity: .55; cursor: not-allowed; }
.tool-icon { width: 58px; height: 58px; border-radius: 10px; display: grid; place-items: center; flex-shrink: 0; }
.tool-copy strong { font-size: 18px; display: block; margin-bottom: 6px; }.tool-copy small { color: var(--muted); font-size: 13px; line-height: 1.6; display: block; }.tool-count { color: #646cff !important; margin-top: 4px; }.tool-arrow { margin-left: auto; color: var(--muted); flex-shrink: 0; }
.pdf-note { margin-top: 14px; font-size: 12px; color: var(--muted); }
@media (max-width: 1100px) { .metric-icon { width: 44px; height: 44px; }.metric-card :deep(.n-card-content) { padding: 18px 14px; }.tool-entry { padding: 18px 14px; }.tool-icon { width: 44px; height: 48px; }.chart-row { grid-template-columns: minmax(0,1.6fr) minmax(240px,1fr); } }
@media (max-width: 767px) { .home-heading { margin: 5px 0 20px; align-items: flex-start; }.home-heading h2 { font-size: 25px; letter-spacing: -.5px; max-width: 280px; }.home-heading .n-tag { display: none; }.home-heading p { font-size: 14px; }.scope-row { align-items: flex-start; flex-direction: column; gap: 8px; }.metric-grid { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }.metric-card strong { font-size: 26px; }.metric-card :deep(.n-card-content) { padding: 16px 12px; }.metric-icon { width: 38px; height: 38px; }.metric-icon .n-icon { font-size: 24px !important; }.metric-label { font-size: 13px; }.chart-row { grid-template-columns: 1fr; gap: 14px; }.chart-row :deep(.n-card-content) { padding: 16px 12px; }.section-heading h3 { font-size: 17px; }.trend-chart { height: 255px; }.tool-grid { grid-template-columns: 1fr; gap: 10px; }.quick-tools { margin-top: 22px; }.tool-entry { padding: 18px; }.tool-icon { width: 52px; height: 52px; }.tool-copy small { font-size: 14px; }.rank-row { min-height: 54px; } }
</style>
