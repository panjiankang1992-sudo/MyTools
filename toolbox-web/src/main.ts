import { createApp } from 'vue';
import { useAdminAuthStore } from './stores/admin-auth';
import { createPinia } from 'pinia';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';
import 'virtual:uno.css';
import './style.css';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/pdf', component: () => import('./views/PdfView.vue') },
    // 每个 PDF 子工具拥有稳定路由，旧入口 /pdf?tool=xxx 归一化到同一页面。
    { path: '/pdf/:toolId', component: () => import('./views/PdfView.vue'), props: true },
    // 2026-10-03：字帖与英语字帖切换到统一输入流程（方案 T02）。
    // 旧视图 WorksheetView.vue 保留在仓库中以便回滚，不再被路由引用。
    { path: '/copybook', component: () => import('./views/WorksheetV2View.vue'), props: { subject: 'chinese' } },
    { path: '/words', component: () => import('./views/WorksheetV2View.vue'), props: { subject: 'english' } },
    // 字帖改造后的统一流程页面：与旧入口并存，验证通过后再切换。
    { path: '/worksheet-v2', component: () => import('./views/WorksheetV2View.vue'), props: { subject: 'chinese' } },
    { path: '/worksheet-v2/english', component: () => import('./views/WorksheetV2View.vue'), props: { subject: 'english' } },
    // 管理端：仅管理员可进；新增与修改共用同一个编辑页（设计文档 §3.4）。
    // 管理页面**不计入公开工具点击统计**，因此不注册进 `TOOL_DEFINITIONS`。
    {
      path: '/admin/textbooks',
      component: () => import('./views/admin/TextbookListView.vue'),
      meta: { requiresAdmin: true }
    },
    {
      path: '/admin/textbooks/new',
      component: () => import('./views/admin/TextbookEntryView.vue'),
      meta: { requiresAdmin: true }
    },
    {
      path: '/admin/textbooks/:editionId/edit',
      component: () => import('./views/admin/TextbookEntryView.vue'),
      props: true,
      meta: { requiresAdmin: true }
    },
    {
      path: '/admin/textbooks/:editionId/advanced',
      component: () => import('./views/admin/TextbookEditorView.vue'),
      props: true,
      meta: { requiresAdmin: true }
    },
    // D02：导出包 → 数据库的恢复入口。**破坏性操作**，页面内自带"预览 + 二次确认"。
    // 与其它管理路由一样以 `/admin` 开头，因此同样被 `App.vue` 排除出 KeepAlive。
    {
      path: '/admin/curriculum/restore',
      component: () => import('./views/admin/CurriculumRestoreView.vue'),
      meta: { requiresAdmin: true }
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
});

/**
 * 管理路由守卫。
 *
 * 首次未授权直达**不加载编辑器**：在解析路由前先完成一次会话验证。
 * 这里只负责"能不能进"；已打开编辑器后因期限届满失效的处理由页面自行完成
 * （保留内存缓冲、冻结写入），避免守卫把已挂载实例立即卸载。
 */
router.beforeEach(async to => {
  if (!to.meta.requiresAdmin) return true;
  // 这里**静态**导入管理会话 store。
  //
  // 原本写的是 `await import('./stores/admin-auth')`，意图是"未授权直达时不加载它"。
  // 但 `App.vue` 与 `AdminLoginDialog.vue` 都**静态**导入同一个模块，
  // 它本来就在主包里——那个动态导入**什么也没省下**，
  // 只让打包器报一条 `INEFFECTIVE_DYNAMIC_IMPORT` 警告（F15 要求零告警）。
  //
  // 真正"未授权直达不加载"的是**编辑器**：它由路由懒加载，
  // 与本 store 无关。
  const auth = useAdminAuthStore();
  await auth.initialize();
  if (auth.isAdmin) return true;
  // 未登录或被拒：留在公共页面，由用户从右上角入口登录。
  return { path: '/', query: { redirect: to.fullPath } };
});

createApp(App).use(createPinia()).use(router).mount('#app');
