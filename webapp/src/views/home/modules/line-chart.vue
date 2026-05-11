<script setup lang="ts">
import { watch } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useEcharts } from '@/hooks/common/echarts';
import { $t } from '@/locales';
import { fetchGetLogStatistics } from '@/service/api';

defineOptions({
  name: 'LineChart'
});

const appStore = useAppStore();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: {
      type: 'cross',
      label: {
        backgroundColor: '#6a7985'
      }
    }
  },
  legend: {
    data: [$t('page.home.downloadCount'), $t('page.home.registerCount')],
    top: '0'
  },
  grid: {
    left: '3%',
    right: '4%',
    bottom: '3%',
    top: '15%'
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: [] as string[]
  },
  yAxis: {
    type: 'value'
  },
  series: [
    {
      color: '#8e9dff',
      name: $t('page.home.downloadCount'),
      type: 'line',
      smooth: true,
      stack: 'Total',
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            {
              offset: 0.25,
              color: '#8e9dff'
            },
            {
              offset: 1,
              color: '#fff'
            }
          ]
        }
      },
      emphasis: {
        focus: 'series'
      },
      data: [] as number[]
    },
    {
      color: '#26deca',
      name: $t('page.home.registerCount'),
      type: 'line',
      smooth: true,
      stack: 'Total',
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            {
              offset: 0.25,
              color: '#26deca'
            },
            {
              offset: 1,
              color: '#fff'
            }
          ]
        }
      },
      emphasis: {
        focus: 'series'
      },
      data: [] as number[]
    }
  ]
}));

async function loadData() {
  try {
    const result = await fetchGetLogStatistics({});
    console.log('[LineChart] statistics result:', result);

    if (result?.hourlyStats) {
      const hours = result.hourlyStats.map((item: Api.Log.HourlyStat) => item.hour);
      const requestCounts = result.hourlyStats.map((item: Api.Log.HourlyStat) => item.count);
      const successCounts = result.hourlyStats.map((item: Api.Log.HourlyStat) => item.successCount);

      updateOptions(opts => {
        opts.xAxis.data = hours;
        opts.series[0].data = requestCounts;
        opts.series[1].data = successCounts;
        return opts;
      });
    }
  } catch (error) {
    console.error('[LineChart] failed to load statistics:', error);
  }
}

function updateLocale() {
  updateOptions((opts, factory) => {
    const originOpts = factory();

    opts.legend.data = originOpts.legend.data;
    opts.series[0].name = originOpts.series[0].name;
    opts.series[1].name = originOpts.series[1].name;

    return opts;
  });
}

watch(
  () => appStore.locale,
  () => {
    updateLocale();
  }
);

// init
loadData();
</script>

<template>
  <NCard :bordered="false" class="card-wrapper">
    <div ref="domRef" class="h-360px overflow-hidden"></div>
  </NCard>
</template>

<style scoped></style>
