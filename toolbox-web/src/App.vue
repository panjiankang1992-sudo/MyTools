<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAutoComplete, NButton, NConfigProvider, NDialogProvider, NDrawer, NDrawerContent, NDropdown, NIcon, NMenu, NMessageProvider, NTooltip, darkTheme, dateZhCN, zhCN } from 'naive-ui';
import { MenuOutline, MoonOutline, PersonOutline, SearchOutline, SunnyOutline } from '@vicons/ionicons5';
import AdminLoginDialog from './components/admin/AdminLoginDialog.vue';
import { useAdminAuthStore } from './stores/admin-auth';
import { isAllowedRedirect } from './lib/admin-redirect';
import { commonLabel, text } from './locales/common';
import { toolLabel } from './locales/tools';
import { recordEntryClick, startAnalytics, stopAnalytics } from './lib/analytics';
import { CATEGORY_DEFINITIONS, TOOL_DEFINITIONS } from './config/tools';
import { toolIcon } from './config/icons';
import type { ToolCategory } from './types';

const route = useRoute();

/** 管理路由：这些页面不参与 KeepAlive，见模板里的 F02 说明。 */
const isAdminRoute = computed(() => route.path.startsWith('/admin'));
const router = useRouter();
const drawerOpen = ref(false);
const dark = ref(false);
const search = ref('');
const menuButton = ref<InstanceType<typeof NButton> | null>(null);
const adminAuth = useAdminAuthStore();
const loginVisible = ref(false);

/** 管理菜单只在已登录管理员时出现，且不计入公开工具点击统计。 */
const adminMenuOptions = computed(() => (adminAuth.isAdmin
  ? [{
      key: 'admin-group',
      label: '管理',
      icon: () => h(NIcon, { size: 23 }, { default: () => h(PersonOutline) }),
      children: [{ key: '/admin/textbooks', label: '教材录入' }]
    }]
  : []));

/** 右上角身份入口的菜单项。 */
const adminDropdownOptions = [
  { key: '/admin/textbooks', label: '教材录入' },
  { key: 'logout', label: '退出管理员模式' }
];

/** 登录成功：关闭弹窗，并回到被守卫挡下的原目标（只允许站内已注册路由）。 */
function onAdminLoginSuccess(): void {
  drawerOpen.value = false;
  const target = route.query.redirect;
  if (isAllowedRedirect(target)) {
    // 清掉 query，避免刷新后再次跳转。
    void router.replace(target);
  }
}

/** 处理右上角菜单选择。 */
async function onAdminAction(key: string): Promise<void> {
  if (key === 'logout') {
    // 退出只清管理状态；公共字帖草稿不受影响。
    await adminAuth.logout();
    // F02：退出后必须**离开管理页**。
    // 管理页不在 KeepAlive 里，因此一旦离开，编辑器组件就真的被卸载，
    // 它 refs 里的未保存正文随之销毁——否则下一个账号（或未登录状态）
    // 回到这个地址会看到上一个人的内容。
    if (route.path.startsWith('/admin')) {
      await router.push('/');
    }
    return;
  }
  router.push(key);
}

/** 左侧目录由注册表驱动，新增分类不需要改这里。 */
const pages = computed(() => CATEGORY_DEFINITIONS
  .slice()
  .sort((left, right) => left.order - right.order)
  .map(item => ({ key: item.route, label: commonLabel(item.labelKey), icon: toolIcon(item.icon) })));

const options = computed(() => [
  ...pages.value.map(page => ({
    ...page,
    icon: () => h(NIcon, { size: 23 }, { default: () => h(page.icon) })
  })),
  ...adminMenuOptions.value
]);

/** 一级目录使用分类名称，独立工具页使用具体名称，避免共享路由误显示某一种字帖。 */
const current = computed(() => {
  if (route.path.startsWith('/admin/textbooks')) return '教材录入';
  const category = pages.value.find(page => page.key === route.path);
  if (category) return category.label;
  const tool = TOOL_DEFINITIONS.find(item => item.enabled && item.route === route.path);
  if (tool) return toolLabel(tool.labelKey);
  return pages.value[0]?.label ?? '';
});

/** 搜索索引覆盖全部已启用工具与一级目录。 */
const searchOptions = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  const entries = [
    ...pages.value.map(page => ({ label: page.label, value: page.key })),
    ...TOOL_DEFINITIONS.filter(tool => tool.enabled).map(tool => ({ label: toolLabel(tool.labelKey), value: tool.route }))
  ];
  if (!keyword) return entries.slice(0, 12);
  return entries.filter(entry => entry.label.toLowerCase().includes(keyword)).slice(0, 12);
});

const themeOverrides = {
  common: { primaryColor: '#646cff', primaryColorHover: '#7c82ff', primaryColorPressed: '#535bf2', borderRadius: '6px', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif' },
  Menu: { itemHeight: '48px', itemTextColorActive: '#535bf2', itemIconColorActive: '#646cff', itemColorActive: '#eef0ff', itemColorActiveHover: '#e7eaff', itemBorderRadius: '6px' }
};

/** 通过工具入口导航，并记录一次入口点击事件。 */
function navigate(path: string): void {
  if (path !== '/') {
    const target = TOOL_DEFINITIONS.find(tool => tool.enabled && tool.route === path);
    const category = target?.category ?? (path.split('/')[1] as ToolCategory);
    if (['pdf', 'copybook', 'words'].includes(category)) recordEntryClick(category, target?.id ?? null);
  }
  void router.push(path);
  drawerOpen.value = false;
  search.value = '';
}

/** 关闭抽屉后把焦点还给触发按钮，键盘用户可以继续操作。 */
function closeDrawer(): void {
  const element = menuButton.value?.$el as HTMLElement | undefined;
  element?.focus?.();
}

watch(() => route.path, () => { drawerOpen.value = false; });

onMounted(() => {
  // 初始化失败不阻塞公共页面；状态机自行区分未登录与依赖不可用。
  void adminAuth.initialize();
  adminAuth.listenCrossTab(); startAnalytics(); });
onBeforeUnmount(() => { stopAnalytics(); });
</script>

<template>
  <NConfigProvider :theme="dark ? darkTheme : null" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider><NDialogProvider>
      <div class="toolbox-app" :class="{ 'is-dark': dark }">
        <aside class="site-sidebar" :aria-label="text.expand">
          <RouterLink to="/" class="site-brand">{{ text.brand }}</RouterLink>
          <NMenu :value="route.path" :options="options" :node-props="option => ({ 'aria-label': String(option.label), title: String(option.label) })" @update:value="navigate" />
          <p class="sidebar-footer">{{ text.more }}</p>
        </aside>
        <div class="site-workspace">
          <header class="site-header">
            <NButton ref="menuButton" class="mobile-menu" quaternary circle :aria-label="text.expand" @click="drawerOpen = true"><template #icon><NIcon :component="MenuOutline" /></template></NButton>
            <span class="mobile-brand">{{ text.brand }}</span>
            <h1 class="header-title">{{ current }}</h1>
            <div class="header-actions">
              <NAutoComplete v-model:value="search" class="tool-search" :options="searchOptions" :placeholder="text.search" :aria-label="text.search" @select="navigate"><template #prefix><NIcon :component="SearchOutline" /></template></NAutoComplete>
              <NTooltip><template #trigger><NButton quaternary circle :aria-label="dark ? text.light : text.dark" @click="dark = !dark"><template #icon><NIcon :component="dark ? SunnyOutline : MoonOutline" /></template></NButton></template>{{ dark ? text.light : text.dark }}</NTooltip>
              <NButton v-if="route.path === '/'" class="header-primary" type="primary" @click="navigate('/copybook')">{{ text.start }}</NButton>
              <!-- 检查会话期间只显示占位，不闪现账号信息 -->
              <NButton v-if="adminAuth.isChecking" class="admin-entry" quaternary loading disabled aria-label="正在检查登录状态">检查中</NButton>
              <NButton v-else-if="adminAuth.isAdmin" class="admin-entry" quaternary>
                <template #icon><NIcon :component="PersonOutline" /></template>
                <NDropdown :options="adminDropdownOptions" trigger="hover" @select="onAdminAction">
                  <span class="admin-name">管理员<span class="admin-account"> · {{ adminAuth.username || '已登录' }}</span></span>
                </NDropdown>
              </NButton>
              <NButton v-else class="admin-entry" quaternary @click="loginVisible = true">管理员登录</NButton>
            </div>
          </header>
          <main class="page-content"><RouterView v-slot="{ Component }">
            <!--
              F02：**管理页面不进 KeepAlive**。
              此前所有路由都套在同一个 KeepAlive 里，于是编辑器的正文缓冲
              会在"退出登录／换账号"之后**继续留在内存中**——
              下一个账号（或未登录状态）回到管理页时会看到上一个账号的未保存内容。
              管理缓冲必须由会话生命周期决定，而不是由组件缓存决定。
            -->
            <KeepAlive v-if="!isAdminRoute" :max="5"><component :is="Component" :key="route.path" /></KeepAlive>
            <component :is="Component" v-else :key="route.path" />
          </RouterView></main>
        </div>
        <NDrawer v-model:show="drawerOpen" placement="left" :width="280" class="mobile-drawer" @after-leave="closeDrawer">
          <NDrawerContent :title="text.brand" closable :native-scrollbar="false"><NMenu :value="route.path" :options="options" @update:value="navigate" /><p class="drawer-footer">{{ text.more }}</p></NDrawerContent>
        </NDrawer>
        <AdminLoginDialog v-model:show="loginVisible" @success="onAdminLoginSuccess" />
      </div>
    </NDialogProvider></NMessageProvider>
  </NConfigProvider>
</template>
