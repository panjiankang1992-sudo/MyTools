import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

/**
 * 挂载**真实编辑器**的第一块基石。
 *
 * 审查对 F02/F05/F06/F10 的要求都写着"**挂载真实 App/Editor**"，
 * 而本目录此前的 7 条组件测试**挂的是一个小探针** ✗ ——
 * 那正是审查批评的"只测 store 字段"。
 *
 * 我在多轮记录里写过"本环境无浏览器，所以做不了" ✗。
 * **那句话不准确**：`@vue/test-utils` 在 Node 里就能挂载真实组件。
 * 这一条测试要确认的第一件事就是：**它真的能挂起来**。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks/new', query: {} })
}))

const fetchDraft = vi.fn()
const saveDraft = vi.fn()
// `fetchDraft` 住在 `admin-textbooks-api`，不是 `admin-api`
// ——我第一版 mock 错了模块，于是断言"它被调用过"当然失败。
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchDraft: (...args: unknown[]) => fetchDraft(...args),
    saveDraft: (...args: unknown[]) => saveDraft(...args),
    // F06：编辑器现在走**管理端**翻译入口（额度与访客分开），
    // 所以它住在 `admin-textbooks-api` 而不是 `public-api`。
    // 这里把新函数指向**同一个 spy**，既有的断言因此不需要改写。
    translateEntriesForAdmin: (...args: unknown[]) => translateEntriesForAdmin(...args)
  }
})

const fetchAdminSession = vi.fn()
vi.mock('../../src/services/admin-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-api')>()
  return {
    ...actual,
    fetchAdminSession: (...args: unknown[]) => fetchAdminSession(...args),
    fetchAdminCsrf: vi.fn().mockResolvedValue('nonce'),
    adminLogin: vi.fn(),
    adminLogout: vi.fn()
  }
})

const translateEntries = vi.fn()
// 管理端专用入口（F06）。**单独一个 spy**：只有分开，才能区分编辑器用的是哪一个。
const translateEntriesForAdmin = vi.fn()
vi.mock('../../src/services/public-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/public-api')>()
  return {
    ...actual,
    translateEntries: (...args: unknown[]) => translateEntries(...args)
  }
})

const { default: TextbookEditorView } = await import('../../src/views/admin/TextbookEditorView.vue')

// **必须自动卸载**：编辑器的"离开保护"监听挂在**全局 `window`** 上。
// 前一个测试挂载的实例如果没卸载，它的监听就会留在页面上，
// 而它自己是 dirty 的——于是**它会拦截后一个测试里的 `beforeunload`**。
//
// 第 79/80 轮我追了两轮"加载即标脏"，真因就是这里：
// **产品没有那个问题，是我的测试之间互相污染。**
enableAutoUnmount(afterEach)
const { useAdminAuthStore } = await import('../../src/stores/admin-auth')

describe('真实编辑器可以挂载（F02/F05/F06/F10 的验收基石）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDraft.mockReset()
    translateEntriesForAdmin.mockReset().mockResolvedValue([])
    const auth = useAdminAuthStore()
    auth.status = 'admin'
    auth.userId = 'test-admin'
  })

  it('挂载"新建教材"路径时渲染出编辑器，而不是抛错', async () => {
    const wrapper = mount(TextbookEditorView, {
      props: {},
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })

    // **判别力**：挂载成功本身还不够，要确认它真的渲染出了编辑界面。
    // 锚点用实测看到的那句说明文字，以及编辑器必然提供的几个动作按钮。
    const text = wrapper.text()
    expect(text).toContain('新增教材')
    expect(text).toContain('保存草稿')
    expect(text).toContain('预览')
    // 学科选择器确实渲染了（n-select 会渲染成输入框，不一定显示选项文本）。
    expect(wrapper.findComponent({ name: 'NSelect' }).exists() || text.includes('学科')).toBe(true)
  })

  it('加载到的草稿内容会真的进入双列编辑器的 rows（不只是"发了个请求"）', async () => {
    // **判别力**：只断言"fetchDraft 被调用"太弱——一个把返回值丢掉、
    // 还渲染空表格的实现也能通过。这里用一个记录 props 的桩，
    // 断言**内容真的流到了渲染层**。
    const captured: { subject?: unknown; rows?: unknown } = {}
    // **形状必须与真实的 `AdminDraftResult` 一致**：内容是嵌在 `draft` 下的。
    // 我第一版把 `units`/`entries` 放在顶层，于是加载"成功"但什么内容都没有
    // ——与我在更早一轮踩过的 `data.units` vs `data.draft.units` 是同一个坑。
    fetchDraft.mockResolvedValue({
      revision: 3,
      baseContentVersion: null,
      draft: {
        subject: 'chinese',
        grade: 1,
        semester: 'first',
        publisher: '人民教育出版社',
        series: '统编语文',
        editionLabel: '2024 年修订版',
        sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
                    meaning: null, listTypes: [], confirmed: false }]
      }
    })

    // **必须在 render 里捕获，不能只在 setup 里捕获一次。**
    //
    // 我前三轮一直用"setup 里赋值"的桩，于是拿到的永远是**挂载那一刻**的 props
    // （`rows` 此时还是初始的 `[{}]`）——而真实内容要等 `onMounted` 里的异步加载完成。
    // **产品一直是对的，错的是我的测试。** 这就是 `rows` 常年显示 `[{}]` 的原因。
    const Recorder = defineComponent({
      props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
      setup(props) {
        return () => {
          captured.subject = props.subject
          captured.rows = props.rows
          return h('div', { 'data-recorder': 'paired' })
        }
      }
    })

    mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      // **必须用 stubs**：编辑器是**直接 import** 子组件的，
      // `global.components` 覆盖不了局部 import（我第一版就写错了）。
      global: { stubs: { WorksheetPairedEditor: Recorder, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    expect(fetchDraft).toHaveBeenCalledWith('demo-book')

    // 第 71 轮我断言的正是这一条，当时失败，我以为是产品有问题。
    // 第 73 轮把矛盾点收窄，第 74 轮查明：**是我的桩捕获时机不对**。
    // 现在恢复强断言——它本来就该通过。
    const rows = captured.rows as { tokenId?: string; text?: string; pinyin?: string }[]
    expect(rows.some(row => row.tokenId === 'r1' && row.text === '山' && row.pinyin === 'shān'))
      .toBe(true)
  })

  it('F10：英语草稿的行必须用英语的双列语义（左中文右英文），而不是语文的汉字/拼音', async () => {
    // 用户契约：「**英语左中文右英语**」。语文行用 `text/pinyin`，
    // 英语行必须用 `english/meaning` —— 用错字段名会让英语教材的行渲染成空白。
    const captured: { subject?: unknown; rows?: unknown } = {}
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'english', grade: 3, semester: 'first',
        publisher: '人民教育出版社', series: '人教版英语',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: 'Unit 1', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: 'apple', pinyin: null,
                    meaning: '苹果', listTypes: [], confirmed: false }]
      }
    })

    const Recorder = defineComponent({
      props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
      setup(props) {
        return () => {
          captured.subject = props.subject
          captured.rows = props.rows
          return h('div')
        }
      }
    })

    mount(TextbookEditorView, {
      props: { editionId: 'demo-english' },
      global: { stubs: { WorksheetPairedEditor: Recorder, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    expect(captured.subject).toBe('english')
    const rows = captured.rows as { english?: string; meaning?: string; text?: unknown }[]
    const row = rows.find(item => item.english === 'apple')
    expect(row).toBeTruthy()
    expect(row?.meaning).toBe('苹果')
    // 关键：**不能**把英语内容塞进语文字段。
    expect(row?.text).toBeUndefined()
  })

  it('F06：已自动补全过的内容不会再被补一次（点"自动补全"后提示无可补）', async () => {
    // 用户契约：「**一条新内容最多首次自动补全一次**」。
    // 判据是**已补全标记**（`automaticInitialized`），不是"是否已核对"。
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '长', pinyin: 'zhǎng',
                    meaning: null, listTypes: [], confirmed: false,
                    // **已补过**：手工填的 zhǎng 不能被自动补全覆盖成 cháng
                    automaticInitialized: true }]
      }
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const button = wrapper.findAll('button').find(item => item.text().includes('自动补全'))
    expect(button, '必须能找到"自动补全"按钮').toBeTruthy()
    await button!.trigger('click')
    await Promise.resolve()

    // 提示必须说"没有需要补全的内容"——说明**没有**再补一次。
    expect(wrapper.text()).toContain('没有需要补全的内容')
  })

  it('F06 安全网：未补全过的内容**应该**被补一次（否则"永远不补"也能让上一条通过）', async () => {
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '长', pinyin: null,
                    meaning: null, listTypes: [], confirmed: false,
                    // 与上一条的唯一差别：**没有**已补全标记
                    automaticInitialized: false }]
      }
    })
    // F06 的持久化闸门会先保存；不 mock 它会走失败分支、根本不发补全请求。
    saveDraft.mockResolvedValue({ revision: 2 })
    translateEntriesForAdmin.mockImplementation(async (items: { id: string; text: string }[], _signal: AbortSignal, subject: string) => {
      expect(subject).toBe('chinese')
      return items.map(item => ({ id: item.id, sourceText: item.text, englishText: null, meaning: null,
        status: 'translated', pinyinSegments: [{ grapheme: '长', syllable: 'zhǎng' }] }))
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const button = wrapper.findAll('button').find(item => item.text().includes('自动补全'))
    await button!.trigger('click')
    await Promise.resolve()

    await flushPromises()
    expect(wrapper.text()).toContain('已自动补全 1 条')
    // 而且补出来的拼音应当真的写进了那一行。
    const state = (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState
    const rows = state.rows as { text?: string; pinyin?: string }[]
    expect(rows.some(row => row.text === '长' && row.pinyin === 'zhǎng')).toBe(true)
  })

  it('F02：鉴权失效后，编辑缓冲必须被清空（不能让下一个账号看到上一个人的内容）', async () => {
    // 审查的反例：**A 退出后 B 登录，B 看到 A 的缓冲**。
    // 缓冲只在内存里，所以"换账号清空"只能由编辑器自己响应鉴权状态来做。
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'a' })
    const auth = useAdminAuthStore()
    await auth.initialize()
    expect(auth.canWrite).toBe(true)

    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
                    meaning: null, listTypes: [], confirmed: false }]
      }
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const state = (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState
    expect((state.entries as unknown[]).length).toBe(1)

    // **鉴权失效**（模拟退出/被撤权/换账号）。
    fetchAdminSession.mockResolvedValue({ authenticated: false, adminEnabled: true })
    // **必须传 force**：store 对未强制的重复 initialize 会直接短路
    // （`if (initialized \&\& !force) return`）——我第一版就是在这里失败的。
    await auth.initialize(true)
    await Promise.resolve()

    expect((state.entries as unknown[]).length)
      .toBe(0)
    expect(wrapper.text()).toContain('编辑内容已清空')
  })

  it('F05：内容改动后约 2 秒自动保存一次（防抖：连续改动只保存一次）', async () => {
    vi.useFakeTimers()
    try {
      fetchDraft.mockResolvedValue({
        revision: 1,
        baseContentVersion: null,
        draft: {
          subject: 'chinese', grade: 1, semester: 'first',
          publisher: '人民教育出版社', series: '统编语文',
          editionLabel: '2024 年修订版', sourceNote: '附录',
          coverageDeclaration: 'partial',
          units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
          lessons: [],
          entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                      lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
                      meaning: null, listTypes: [], confirmed: false }]
        }
      })
      saveDraft.mockResolvedValue({ revision: 2 })
      const Recorder = defineComponent({
        props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
        emits: ['update:rows'],
        setup(_props, { emit }) {
          return () => h('button', {
            'data-emit': 'rows',
            onClick: () => emit('update:rows', [
              { tokenId: 'r1', text: '水', pinyin: 'shuǐ', automaticInitialized: false }
            ])
          })
        }
      })

      const wrapper = mount(TextbookEditorView, {
        props: { editionId: 'demo-book' },
        global: { stubs: { WorksheetPairedEditor: Recorder, WorksheetPreview: true } }
      })
      await vi.advanceTimersByTimeAsync(0)
      saveDraft.mockClear()

      // 连续改两次内容：防抖应当把它们合成**一次**保存。
      await wrapper.find('[data-emit="rows"]').trigger('click')
      await vi.advanceTimersByTimeAsync(1000)
      await wrapper.find('[data-emit="rows"]').trigger('click')

      // 第一次改动后满 2 秒之前，不该保存。
      await vi.advanceTimersByTimeAsync(1000)
      expect(saveDraft).toHaveBeenCalledTimes(0)

      // 再推进到第二次改动之后满 2 秒，应当**只保存一次**。
      await vi.advanceTimersByTimeAsync(1500)
      expect(saveDraft).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('F05：有未保存改动时，离开页面必须被拦截', async () => {
    // 编辑器此前**完全没有离开保护**：自动保存 2 秒后才落库，
    // "刚改完就关页面"会丢掉最后那两秒，而用户得不到任何提示。
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
                    meaning: null, listTypes: [], confirmed: false }]
      }
    })
    const Recorder = defineComponent({
      props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
      emits: ['update:rows'],
      setup(_props, { emit }) {
        return () => h('button', {
          'data-emit': 'rows',
          onClick: () => emit('update:rows', [
            { tokenId: 'r1', text: '水', pinyin: 'shuǐ', automaticInitialized: false }
          ])
        })
      }
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: Recorder, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    // 1) **没有改动**时不该拦截——否则用户只是看一眼就关页面也会被拦。
    //
    // 第 79/80 轮这条断言失败过，我追了两轮"加载即标脏"。
    // **真因不是产品**：编辑器的离开保护挂在**全局 `window`** 上，
    // 而前一个测试挂载的实例没有卸载——它自己是 dirty 的，
    // 于是**它拦截了这个测试的事件**。启用 `enableAutoUnmount` 后此断言成立。
    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    // 改一次内容（变 dirty）后必须拦截。
    await wrapper.find('[data-emit="rows"]').trigger('click')
    await Promise.resolve()
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented)
      .toBe(true)

    wrapper.unmount()
  })

  it('F06：卸载时仍在途的补全请求必须被取消（signal 真的 aborted）', async () => {
    // 编辑器此前建了 `AbortController`、也把 signal 传下去了，
    // 但**从来没调用过 `.abort()`** —— 请求永远不会被取消。
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'english', grade: 3, semester: 'first',
        publisher: '人民教育出版社', series: '人教版英语',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: 'Unit 1', order: 1 }],
        lessons: [],
        // **缺 meaning** 才会成为补全目标（英语路径才会真的发请求）。
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: 'apple', pinyin: null,
                    meaning: null, listTypes: [], confirmed: false,
                    automaticInitialized: false }]
      }
    })
    let captured: AbortSignal | undefined
    // 返回一个**永不 resolve** 的 promise，让请求保持在途。
    translateEntriesForAdmin.mockImplementation((_items: unknown, signal: AbortSignal) => {
      captured = signal
      return new Promise(() => {})
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-english' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const button = wrapper.findAll('button').find(item => item.text().includes('自动补全'))
    await button!.trigger('click')
    await Promise.resolve()
    // **鉴别力**：必须是**管理端**入口被调用（额度与访客分开）。
    // 如果编辑器退回用公开的 translateEntries，这一条会失败。
    expect(translateEntriesForAdmin).toHaveBeenCalled()
    expect(captured?.aborted).toBe(false)

    wrapper.unmount()
    await Promise.resolve()
    expect(captured?.aborted)
      .toBe(true)
  })

  it('F06：持久化闸门——必须先保存"已补全标记"，成功后才发补全请求', async () => {
    // 审查要求：「先保存新行与 initialized，**成功后才首发自动请求**」。
    // 不先落库的话，刷新页面会丢掉标记，同一行会被**再补一次**。
    const order: string[] = []
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'english', grade: 3, semester: 'first',
        publisher: '人民教育出版社', series: '人教版英语',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: 'Unit 1', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: 'apple', pinyin: null,
                    meaning: null, listTypes: [], confirmed: false,
                    automaticInitialized: false }]
      }
    })
    saveDraft.mockImplementation(() => {
      order.push('save')
      return Promise.resolve({ revision: 2 })
    })
    translateEntriesForAdmin.mockImplementation(() => {
      order.push('translate')
      return Promise.resolve([{ id: 'r1', meaning: '苹果' }])
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-english' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const button = wrapper.findAll('button').find(item => item.text().includes('自动补全'))
    await button!.trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    expect(order).toContain('save')
    expect(order).toContain('translate')
    expect(order.indexOf('save')).toBeLessThan(order.indexOf('translate'))
  })

  it('F03：打开带地区／年份／ISBN 的草稿再保存，这三个值不能被丢掉', async () => {
    // 接口 PUT 的是**整份草稿**：若 `buildDraft()` 不带这三个字段，
    // 用户从界面保存一次就会把它们**悄悄抹掉**——那是数据丢失，不是缺功能。
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        regionCodes: ['320000'],
        curriculumYear: 2024,
        isbn: '978-7-107-12345-6',
        units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
        lessons: [],
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: '山', pinyin: 'shān',
                    meaning: null, listTypes: [], confirmed: false }]
      }
    })
    saveDraft.mockResolvedValue({ revision: 2 })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const save = wrapper.findAll('button').find(item => item.text().includes('保存草稿'))
    expect(save, '必须能找到"保存草稿"按钮').toBeTruthy()
    await save!.trigger('click')
    await Promise.resolve()
    await Promise.resolve()

    expect(saveDraft).toHaveBeenCalled()
    const payload = saveDraft.mock.calls.at(-1)?.[2] as Record<string, unknown>
    expect(payload.regionCodes).toEqual(['320000'])
    expect(payload.curriculumYear).toBe(2024)
    expect(payload.isbn).toBe('978-7-107-12345-6')
  })

  it('给定 editionId 时会去加载那份草稿（走的是真实的加载路径）', async () => {
    fetchDraft.mockResolvedValue({
      revision: 3,
      baseContentVersion: null,
      draft: {
        subject: 'chinese', grade: 1, semester: 'first',
        publisher: '人民教育出版社', series: '统编语文',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial', units: [], lessons: [], entries: []
      }
    })

    mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    // 加载发生在 `onMounted` 里；等两轮微任务让异步链路走完。
    await Promise.resolve()
    await Promise.resolve()

    expect(fetchDraft).toHaveBeenCalledWith('demo-book')
  })

  it('F06：两次补全交错时，先到的旧回包不得写回（代次守卫）', async () => {
    // 用户配方：**同一 rowId、同一原文、空白目标**上发生**两次在途请求**，
    // **先完成第一次（旧）**，断言目标仍空、旧结果未合入；
    // 再完成第二次（新），断言只出现新结果。
    //
    // 为什么必须这样造：这条守卫挡的是"**原文没变、只是被超越**"的回包。
    // - `sourceText` 校验只在原文被改过时才丢；本用例两次原文都是 `apple` ⇒ 它挡不住。
    // - "目标此刻仍为空"只在用户手工填过时才丢；本用例目标一直为空 ⇒ 它也挡不住。
    // 因此**去掉代次守卫，旧结果就会写回**——这条断言才有鉴别力。
    //
    // "两次请求如何真实共存"：`runAutomaticCompletion` 的 `force` 参数是
    // **显式的重新补全**契约，UI 上对应"重新补全空白"按钮；
    // 第一次仍在途时再点它就是第二次发起，同时 `inFlightCompletion.abort()`
    // 把第一次取消——而"已到达客户端的迟到响应不受 abort 影响"，正是本用例模拟的。
    fetchDraft.mockResolvedValue({
      revision: 1,
      baseContentVersion: null,
      draft: {
        subject: 'english', grade: 3, semester: 'first',
        publisher: '人民教育出版社', series: '人教版英语',
        editionLabel: '2024 年修订版', sourceNote: '附录',
        coverageDeclaration: 'partial',
        units: [{ unitId: null, clientUnitId: 'cu1', title: 'Unit 1', order: 1 }],
        lessons: [],
        // **英语行形状**：`english` / `meaning`，缺 meaning 才是补全目标。
        entries: [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1',
                    lessonId: null, clientLessonId: null, text: 'apple', pinyin: null,
                    meaning: null, listTypes: [], confirmed: false,
                    automaticInitialized: false }]
      }
    })
    // 持久化闸门：必须保存成功才会发补全请求。
    saveDraft.mockResolvedValue({ revision: 2 })

    // 两次请求各自留一个闸门，由测试决定**谁先回来**。
    const pending: Array<(rows: unknown) => void> = []
    const calls: Array<{ items: { id: string; text: string }[]; aborted: () => boolean }> = []
    translateEntriesForAdmin.mockImplementation((items: { id: string; text: string }[], signal: AbortSignal) => {
      calls.push({ items, aborted: () => signal.aborted })
      return new Promise(resolve => {
        // 替身在收到 abort 后**仍然允许**这个 promise 完成：模拟"响应已经在路上"。
        pending.push(resolve as (rows: unknown) => void)
      })
    })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-english' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve()
    await Promise.resolve()

    const rowsOf = (): { english?: string; meaning?: string }[] =>
      (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState.rows as
        { english?: string; meaning?: string }[]
    const meaningOf = (): string => (rowsOf().find(row => row.english === 'apple')?.meaning ?? '').trim()
    const button = (label: string) => {
      const found = wrapper.findAll('button').find(item => item.text().trim() === label)
      expect(found, `必须能找到"${label}"按钮`).toBeTruthy()
      return found!
    }

    // 第一次发起：目标行 `apple` / 空 meaning，原文 `apple`。
    await button('自动补全').trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    expect(calls.length, '第一次点击必须发出补全请求').toBe(1)
    expect(calls[0].items).toEqual([{ id: 'r1', text: 'apple' }])

    // 第二次发起：**同一 rowId、同一原文、空白目标**（显式重新补全）。
    await button('重新补全空白').trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    expect(calls.length, '第二次点击必须再发出一次补全请求').toBe(2)
    expect(calls[1].items).toEqual([{ id: 'r1', text: 'apple' }])
    // 第一次的 signal 已被取消，但它的 promise 仍然可以完成（迟到响应）。
    expect(calls[0].aborted(), '第二次发起必须取消第一次仍在途的请求').toBe(true)

    // **先完成第一次（旧）**：旧回包不得写回。
    pending[0]([{ id: 'r1', meaning: '苹果（旧）' }])
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    expect(meaningOf(), '旧回包到得再早，也不得写进目标（代次已被超越）').toBe('')
    expect(wrapper.text()).not.toContain('苹果（旧）')

    // **再完成第二次（新）**：只有新结果出现。
    pending[1]([{ id: 'r1', meaning: '苹果（新）' }])
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    expect(meaningOf(), '最新一次的结果必须写回').toBe('苹果（新）')
    expect(wrapper.text()).not.toContain('苹果（旧）')
  })
})
