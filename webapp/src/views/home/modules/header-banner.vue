<script setup lang="ts">
import { computed } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { $t } from '@/locales';

defineOptions({
  name: 'HeaderBanner'
});

const appStore = useAppStore();
const authStore = useAuthStore();

const gap = computed(() => (appStore.isMobile ? 0 : 16));

// 显示名称：优先使用昵称，其次使用用户名
const displayName = computed(() => {
  return authStore.userInfo.nickname || authStore.userInfo.username || '用户';
});

// 显示头像：优先使用用户头像，其次使用默认头像
const avatarSrc = computed(() => {
  return authStore.userInfo.avatar || '/favicon.svg';
});

interface StatisticData {
  id: number;
  label: string;
  value: string;
}

const statisticData = computed<StatisticData[]>(() => [
  {
    id: 0,
    label: $t('page.home.projectCount'),
    value: '25'
  },
  {
    id: 1,
    label: $t('page.home.todo'),
    value: '4/16'
  },
  {
    id: 2,
    label: $t('page.home.message'),
    value: '12'
  }
]);
</script>

<template>
  <NCard :bordered="false" class="card-wrapper">
    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi span="24 s:24 m:18">
        <div class="flex-y-center">
          <div class="size-72px shrink-0 overflow-hidden rd-1/2">
            <img :src="avatarSrc" class="size-full" @error="(e) => { (e.target as HTMLImageElement).src = '/favicon.svg'; }" />
          </div>
          <div class="pl-12px">
            <h3 class="text-18px font-semibold">
              {{ $t('page.home.greeting', { userName: displayName }) }}
            </h3>
            <p class="text-#999 leading-30px">{{ $t('page.home.weatherDesc') }}</p>
          </div>
        </div>
      </NGi>
      <NGi span="24 s:24 m:6">
        <NSpace :size="24" justify="end">
          <NStatistic v-for="item in statisticData" :key="item.id" class="whitespace-nowrap" v-bind="item" />
        </NSpace>
      </NGi>
    </NGrid>
  </NCard>
</template>

<style scoped></style>
