<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount } from 'vue';
import { LAYOUT_SCROLL_EL_ID } from '@sa/materials';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useTabStore } from '@/store/modules/tab';
import { router } from '@/router';

defineOptions({
  name: 'GlobalContent'
});

interface Props {
  /** Show padding for content */
  showPadding?: boolean;
}

withDefaults(defineProps<Props>(), {
  showPadding: true
});

const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const tabStore = useTabStore();

const transitionName = computed(() => (themeStore.page.animate ? themeStore.page.animateMode : ''));
const scrollPositions = new Map<string, number>();

const removeBeforeGuard = router.beforeEach((_to, from) => {
  const el = document.querySelector<HTMLElement>(`#${LAYOUT_SCROLL_EL_ID}`);
  if (el) scrollPositions.set(from.fullPath, el.scrollTop);
});

const removeAfterHook = router.afterEach(to => {
  void nextTick(() => {
    const el = document.querySelector<HTMLElement>(`#${LAYOUT_SCROLL_EL_ID}`);
    el?.scrollTo({ left: 0, top: scrollPositions.get(to.fullPath) || 0 });
  });
});

onBeforeUnmount(() => {
  removeBeforeGuard();
  removeAfterHook();
  scrollPositions.clear();
});

</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <Transition
      :name="transitionName"
      @before-leave="appStore.setContentXScrollable(true)"
      @after-enter="appStore.setContentXScrollable(false)"
    >
      <KeepAlive :include="routeStore.cacheRoutes" :exclude="routeStore.excludeCacheRoutes">
        <component
          :is="Component"
          v-if="appStore.reloadFlag"
          :key="tabStore.getTabIdByRoute(route)"
          :class="{ 'p-16px': showPadding }"
          class="flex-grow bg-layout transition-300"
        />
      </KeepAlive>
    </Transition>
  </RouterView>
</template>

<style></style>
