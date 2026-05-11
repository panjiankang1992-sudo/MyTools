<script setup lang="ts">
import { watch } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useEcharts } from '@/hooks/common/echarts';
import { $t } from '@/locales';
import { fetchGetLogStatistics } from '@/service/api';

defineOptions({
  name: 'PieChart'
});

const appStore = useAppStore();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: {
    trigger: 'item'
  },
  legend: {
    bottom: '1%',
    left: 'center',
    itemStyle: {
      borderWidth: 0
    }
  },
  series: [
    {
      color: ['#5da8ff', '#8e9dff', '#fedc69', '#26deca', '#ff6b6b', '#4ecdc4'],
      name: $t('page.home.schedule'),
      type: 'pie',
      radius: ['45%', '75%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 10,
        borderColor: '#fff',
        borderWidth: 1
      },
      label: {
        show: false,
        position: 'center'
      },
      emphasis: {
        label: {
          show: true,
          fontSize: '12'
        }
      },
      labelLine: {
        show: false
      },
      data: [] as { name: string; value: number }[]
    }
  ]
}));

async function loadData() {
  try {
    const result = await fetchGetLogStatistics({});
    console.log('[PieChart] statistics result:', result);

    if (result?.moduleStats) {
      const moduleData = result.moduleStats.map((item: Api.Log.ModuleStat) => ({
        name: item.module,
        value: item.count
      }));

      updateOptions(opts => {
        opts.series[0].data = moduleData;
        return opts;
      });
    }
  } catch (error) {
    console.error('[PieChart] failed to load statistics:', error);
  }
}

function updateLocale() {
  updateOptions((opts, factory) => {
    const originOpts = factory();

    opts.series[0].name = originOpts.series[0].name;

    // Keep the existing data
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
