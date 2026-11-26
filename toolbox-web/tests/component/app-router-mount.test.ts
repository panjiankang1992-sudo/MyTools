import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import type { Stubs, VueWrapper } from '@vue/test-utils'
import { NDropdown, NSelect } from 'naive-ui'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, onMounted } from 'vue'
import type { Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { RouteRecordRaw, Router } from 'vue-router'

/**
 * F02／F15：**挂载真实 `App.vue` 的组合验收**。
 *
 * 审查对 F15 的要求原文是「**真正挂载 App/Editor 的组合测试**」，
 * 提示词进一步点名：「用真实 router 或 `createMemoryHistory` 挂载 App，
 * **勿只模拟 `useRouter` 然后声称已测路由切换**」。
 *
 * 本文件之前，`tests/component/` 里 7 条测试挂的都是页面组件，
 * 而且**每一条都把 `vue-router` 整个 mock 掉**（`useRouter: () => ({ push: vi.fn() })`）——
 * 于是「App 是否真的把管理路由排除出 KeepAlive」这件事**从未被任何测试执行过**，
 * 只在报告里靠"读代码核对"声称。这正是审查要否掉的形态。
 *
 * 这里换掉那个做法：
 * - **真实 `App.vue`**（不是探针、不是子路径导入）；
 * - **真实 `vue-router`**（`createRouter` + `createMemoryHistory`），
 *   `useRoute`／`useRouter` **一律不 mock**；
 * - 路由跳转由 `router.push` 真正执行，断言读的是 `App` 自己 `useRoute()` 拿到的那份 route。
 *
 * ## 诚实的 mock 边界
 *
 * 只有**网络服务层**被替换（`admin-api`／`admin-textbooks-api`），
 * 因为 jsdom 里没有后端；store、router、App、两个真实管理页面都跑真身。
 * 公共路由（`/`、`/copybook`）用**探针组件**代替真实视图：
 * `HomeView`／`WorksheetV2View` 会拉起 echarts／pdf.js／OCR 资源与统计请求，
 * 而本文件要验证的是 **KeepAlive 的策略**，不是那两个页面的渲染，
 * 用探针能把「是否被缓存」用 `onMounted` 计数变成可判定的信号。
 * 管理路由用的是**真实页面**（`TextbookListView`／`TextbookEditorView`）。
 *
 * `analytics` **没有**被 mock：它在 jsdom 下能正常工作
 * （队列为空时 `startAnalytics` 不发任何请求）。
 */

const fetchAdminSession = vi.fn()
const adminLogout = vi.fn()
vi.mock('../../src/services/admin-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-api')>()
  return {
    ...actual,
    fetchAdminSession: (...args: unknown[]) => fetchAdminSession(...args),
    adminLogout: (...args: unknown[]) => adminLogout(...args),
    fetchAdminCsrf: vi.fn().mockResolvedValue({ csrfToken: 'nonce' }),
    adminLogin: vi.fn()
  }
})

const fetchTextbookList = vi.fn()
const fetchDraft = vi.fn()
const saveDraft = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchTextbookList: (...args: unknown[]) => fetchTextbookList(...args),
    fetchDraft: (...args: unknown[]) => fetchDraft(...args),
    saveDraft: (...args: unknown[]) => saveDraft(...args)
  }
})

const { default: App } = await import('../../src/App.vue')
const { default: TextbookListView } = await import('../../src/views/admin/TextbookListView.vue')
const { default: TextbookEditorView } = await import('../../src/views/admin/TextbookEditorView.vue')
const { useAdminAuthStore } = await import('../../src/stores/admin-auth')

// App 在 onMounted 里注册了 analytics 定时器；组件卸载时会停掉。
// 这里仍然自动卸载，保证测试之间不残留监听与定时器。
enableAutoUnmount(afterEach)

/**
 * 路由探针：把「这个组件实例被创建了几次」变成可断言的计数。
 *
 * KeepAlive 的语义正是「再次进入时复用实例、不再 `onMounted`」，
 * 所以「挂载次数」是它唯一可靠的外部可观测信号。
 */
const mountCounts = new Map<string, number>()

function probe(label: string): Component {
  return defineComponent({
    name: `Probe${label}`,
    setup() {
      onMounted(() => { mountCounts.set(label, (mountCounts.get(label) ?? 0) + 1) })
      return () => h('div', { 'data-probe': label }, `probe:${label}`)
    }
  })
}

/** 记录编辑器行内容变化的桩（编辑器真实地通过 `update:rows` 接收输入）。 */
const pairedEditorRecorder = defineComponent({
  name: 'WorksheetPairedEditor',
  props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
  emits: ['update:rows'],
  setup(_props, { emit }) {
    return () => h('button', {
      'data-emit-rows': 'true',
      onClick: () => emit('update:rows', [
        { tokenId: 'r1', text: '水', pinyin: 'shuǐ', automaticInitialized: false }
      ])
    }, 'emit-rows')
  }
})

/** 用真实 router 构建路由表；`App.vue` 本身不注册路由，路由表是组合测试的输入。 */
function makeRouter(routes: RouteRecordRaw[]): Router {
  return createRouter({ history: createMemoryHistory(), routes })
}

/** 挂载真实 App，并安装真实 router 与 pinia。 */
function mountApp(router: Router, stubs: Stubs = {}): VueWrapper {
  const pinia = createPinia()
  // 先设为活动 pinia：这样测试体里 `useAdminAuthStore()` 拿到的
  // 与 App 内部 `useAdminAuthStore()` 是**同一个实例**。
  setActivePinia(pinia)
  return mount(App, { global: { plugins: [pinia, router], stubs } })
}

/** 读取 `App.vue` 的 `<script setup>` 绑定（`route` 就是它自己 `useRoute()` 的结果）。 */
function appSetupState(wrapper: VueWrapper): Record<string, unknown> {
  return (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState
}

/** 读取某个组件 `$` 内部实例。 */
function instanceOf(wrapper: VueWrapper): { isUnmounted: boolean } {
  return (wrapper.vm as unknown as { $: { isUnmounted: boolean } }).$
}

/** 一个形状与 `AdminDraftResult` 一致的草稿（含一条服务端已有内容）。 */
function draftResult(entries: unknown[]) {
  return {
    revision: 1,
    baseContentVersion: null,
    draft: {
      subject: 'chinese', grade: 1, semester: 'first',
      publisher: '人民教育出版社', series: '统编语文',
      editionLabel: '2024 年修订版', sourceNote: '附录',
      coverageDeclaration: 'partial',
      units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
      lessons: [],
      entries
    }
  }
}

const SERVER_ENTRY = {
  rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
  lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
  meaning: null, listTypes: [], confirmed: false
}

const ADMIN_ROUTES: RouteRecordRaw[] = [
  { path: '/admin/textbooks', component: TextbookListView, meta: { requiresAdmin: true } },
  { path: '/admin/textbooks/new', component: probe('admin-new'), meta: { requiresAdmin: true } },
  {
    path: '/admin/textbooks/:editionId/edit',
    component: TextbookEditorView,
    props: true,
    meta: { requiresAdmin: true }
  }
]

describe('真实 App + 真实 router 的组合验收（F02/F15）', () => {
  beforeEach(() => {
    mountCounts.clear()
    fetchAdminSession.mockReset()
    fetchAdminSession.mockResolvedValue({ authenticated: false, adminEnabled: true })
    adminLogout.mockReset()
    adminLogout.mockResolvedValue(undefined)
    fetchTextbookList.mockReset()
    fetchTextbookList.mockResolvedValue({ items: [], total: 0 })
    fetchDraft.mockReset()
    saveDraft.mockReset()
    saveDraft.mockResolvedValue({ revision: 2 })
  })

  it('F15-1：路由切换真的发生——App 自己看到的 route.path 变了，组件树也真的换了', async () => {
    const router = makeRouter([
      { path: '/', component: probe('home') },
      ...ADMIN_ROUTES
    ])
    await router.push('/')
    await router.isReady()
    const wrapper = mountApp(router)
    await flushPromises()

    // 起点：路径是 `/`，渲染的是公共探针，管理列表**不在**树里。
    expect((appSetupState(wrapper).route as { path: string }).path).toBe('/')
    expect(wrapper.vm.$route.path).toBe('/')
    expect(wrapper.find('[data-probe="home"]').exists()).toBe(true)
    expect(wrapper.findComponent(TextbookListView).exists()).toBe(false)

    // **真正的路由跳转**：由 router 自己解析、自己换组件。
    await router.push('/admin/textbooks')
    await flushPromises()

    // 1) App 内部的 `useRoute()` 结果必须反映新路径。
    //    这一条直接依赖真实 vue-router：把 `useRoute` mock 成固定值就不可能通过。
    expect((appSetupState(wrapper).route as { path: string }).path).toBe('/admin/textbooks')
    expect(wrapper.vm.$route.path).toBe('/admin/textbooks')
    expect(router.currentRoute.value.path).toBe('/admin/textbooks')

    // 2) 组件树**真的换了**：公共探针被卸载，真实管理列表被挂载并渲染出内容。
    expect(wrapper.find('[data-probe="home"]').exists()).toBe(false)
    expect(wrapper.findComponent(TextbookListView).exists()).toBe(true)
    expect(wrapper.text()).toContain('教材录入')
    // 初次进入不拉取全部教材；选好科目、年级、学期后才查询对应范围。
    expect(fetchTextbookList).not.toHaveBeenCalled()
    const list = wrapper.findComponent(TextbookListView)
    const selection = (placeholder: string) => {
      const control = list.findAllComponents(NSelect).find(item => item.props('placeholder') === placeholder)
      expect(control, `应能找到 ${placeholder}`).toBeTruthy()
      return control!
    }
    await selection('请选择科目').vm.$emit('update:value', 'english')
    await selection('请选择年级').vm.$emit('update:value', 3)
    expect(fetchTextbookList).not.toHaveBeenCalled()
    await selection('请选择学期').vm.$emit('update:value', 'first')
    await flushPromises()
    // 真页面通过网络服务层发出精确查询，继续验证 App 中挂载的并非空壳。
    expect(fetchTextbookList).toHaveBeenCalledExactlyOnceWith({
      subject: 'english', grade: 3, semester: 'first', page: 1, pageSize: 100
    })
  })

  it('F02-2：管理路由不进 KeepAlive——离开再回来是**重新创建**，不是复用缓存实例', async () => {
    const router = makeRouter([
      { path: '/', component: probe('home') },
      { path: '/copybook', component: probe('copybook') },
      ...ADMIN_ROUTES
    ])
    await router.push('/')
    await router.isReady()
    const wrapper = mountApp(router)
    await flushPromises()

    await router.push('/admin/textbooks/new')
    await flushPromises()
    expect(wrapper.find('[data-probe="admin-new"]').exists()).toBe(true)
    expect(mountCounts.get('admin-new')).toBe(1)

    // 离开管理路由。
    await router.push('/')
    await flushPromises()
    expect(wrapper.find('[data-probe="admin-new"]').exists()).toBe(false)

    // 回到同一个管理路由：**必须重新创建**（`onMounted` 再跑一次）。
    // 若管理路由被放进 KeepAlive，这里会是 1 —— 编辑器的未保存正文就会被留下。
    await router.push('/admin/textbooks/new')
    await flushPromises()
    expect(wrapper.find('[data-probe="admin-new"]').exists()).toBe(true)
    expect(mountCounts.get('admin-new')).toBe(2)
  })

  it('F02-4：公共字帖不受影响——非管理路由**仍然**被 KeepAlive 缓存', async () => {
    const router = makeRouter([
      { path: '/', component: probe('home') },
      { path: '/copybook', component: probe('copybook') },
      ...ADMIN_ROUTES
    ])
    await router.push('/')
    await router.isReady()
    mountApp(router)
    await flushPromises()
    expect(mountCounts.get('home')).toBe(1)

    await router.push('/copybook')
    await flushPromises()
    expect(mountCounts.get('copybook')).toBe(1)

    // `/` → `/copybook` → `/` → `/copybook`：两个公共路由都只创建过一次。
    // 这一条是**反向的判别力**：如果 App 把 KeepAlive 整个去掉，
    // 计数会变成 2，这里同样会失败——它同时锁住"没有扩大改动范围"。
    await router.push('/')
    await flushPromises()
    expect(mountCounts.get('home')).toBe(1)

    await router.push('/copybook')
    await flushPromises()
    expect(mountCounts.get('copybook')).toBe(1)
  })

  it('F02-3：A 退出后编辑缓冲不残留——编辑器实例被销毁，B 重新从服务端读，看不到 A 的未保存内容', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountA', userId: '1' })
    fetchDraft.mockResolvedValue(draftResult([SERVER_ENTRY]))

    const router = makeRouter([
      { path: '/', component: probe('home') },
      ...ADMIN_ROUTES
    ])
    // 直达编辑页（真实路由 `/admin/textbooks/:editionId/edit` + 真实编辑器）。
    await router.push('/admin/textbooks/demo-book/edit')
    await router.isReady()
    const wrapper = mountApp(router, {
      // 编辑器直接 import 子组件，只能用 stubs 覆盖；预览组件在 jsdom 下会拉起重资源。
      WorksheetPairedEditor: pairedEditorRecorder,
      WorksheetPreview: true
    })
    await flushPromises()

    const auth = useAdminAuthStore()
    expect(auth.isAdmin).toBe(true)
    expect(auth.username).toBe('accountA')

    const editor = wrapper.findComponent(TextbookEditorView)
    expect(editor.exists()).toBe(true)
    const rowsOf = (): { text?: string }[] => {
      const state = (editor.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState
      return state.rows as { text?: string }[]
    }
    // 服务端已有内容进了渲染层（证明走的是真实加载路径）。
    expect(rowsOf().some(row => row.text === '山')).toBe(true)

    // A 输入了一条**只存在于内存**的新内容。
    await wrapper.find('[data-emit-rows]').trigger('click')
    await flushPromises()
    expect(rowsOf().some(row => row.text === '水')).toBe(true)

    const editorInstance = instanceOf(editor)
    expect(editorInstance.isUnmounted).toBe(false)
    expect(fetchDraft).toHaveBeenCalledTimes(1)

    // 触发**真实**下拉菜单的 select 事件 → App 的 `onAdminAction('logout')`。
    // 不直接调用 setup 里的函数：那会绕开模板绑定。
    // 触发**真实**下拉菜单的 select 事件 → App 的 `onAdminAction('logout')`。
    // 不直接调用 setup 里的函数：那会绕开模板绑定。
    //
    // **不能只写 `findComponent(NDropdown)`**：页面上的 `NSelect`（编辑器里就有）
    // 内部也渲染一个 `NDropdown`，裸查找会命中的是它——我第一次跑就因此
    // 把 `'logout'` 当路径推了进去（`/admin/textbooks/demo-book/logout`），
    // 而真正的退出菜单从未被点到。这里按"选项里有 logout"定位真身。
    const adminDropdown = wrapper.findAllComponents(NDropdown).find(item =>
      (item.props('options') as { key: string }[] | undefined)?.some(option => option.key === 'logout'))
    expect(adminDropdown, '必须能找到管理员身份下拉菜单').toBeTruthy()
    adminDropdown!.vm.$emit('select', 'logout')
    await flushPromises()

    expect(adminLogout).toHaveBeenCalled()
    expect(auth.isAdmin).toBe(false)
    expect(auth.username).toBe('')
    // App 的退出逻辑必须把界面**带离管理页**。
    expect(router.currentRoute.value.path).toBe('/')

    // **关键断言**：编辑器实例是被**销毁**的（KeepAlive 之外的 `v-else` 分支），
    // 不是被缓存起来（KeepAlive 的 deactivate 不会置 `isUnmounted`）。
    // 它 refs 里的未保存正文因此真的随实例一起消失。
    expect(editorInstance.isUnmounted).toBe(true)

    // 换账号 B，再回到同一个地址。
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountB', userId: '2' })
    await auth.initialize(true)
    expect(auth.username).toBe('accountB')

    await router.push('/admin/textbooks/demo-book/edit')
    await flushPromises()

    // 新实例重新读了一次服务端草稿（旧实例没有被复用）。
    expect(fetchDraft).toHaveBeenCalledTimes(2)
    const fresh = wrapper.findComponent(TextbookEditorView)
    const freshRows = (fresh.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState.rows as
      { text?: string }[]
    // B 只看到服务端那一版；A 的未保存内容不在里面，也不在页面上。
    expect(freshRows.some(row => row.text === '山')).toBe(true)
    expect(freshRows.some(row => row.text === '水')).toBe(false)
    expect(wrapper.text()).not.toContain('水')
  })
})
