import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

/**
 * 设计 115 / 297 行：**多表类别与条目级来源**。
 *
 * 两块都曾整段缺失：批量粘贴**写死** `listTypes: []`，条目级来源连字段都没有。
 * 这里挂真实编辑器，验证的是"面板存在 + 处理函数真的改到条目上"，
 * 尤其是一条容易写错的合并语义：**按类别筛选后编辑，不能把被筛掉的行删掉**。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks/demo-book/edit', query: {} })
}))

const fetchDraft = vi.fn()
const saveDraft = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchDraft: (...args: unknown[]) => fetchDraft(...args),
    saveDraft: (...args: unknown[]) => saveDraft(...args)
  }
})

const adminRequest = vi.fn()
vi.mock('../../src/services/admin-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-api')>()
  return {
    ...actual,
    adminRequest: (...args: unknown[]) => adminRequest(...args),
    fetchAdminSession: vi.fn().mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'a' }),
    fetchAdminCsrf: vi.fn().mockResolvedValue('nonce'),
    adminLogin: vi.fn(),
    adminLogout: vi.fn()
  }
})

const { default: TextbookEditorView } = await import('../../src/views/admin/TextbookEditorView.vue')
const { sourceRefsOf } = await import('../../src/lib/admin-entry-metadata')
const { primarySourceRefOf } = await import('../../src/lib/admin-entry-metadata')

enableAutoUnmount(afterEach)

/** 构造一条语文草稿条目。 */
function draftEntry(rowId: string, text: string, listTypes: string[], preserved?: Record<string, unknown>) {
  return {
    rowId, entryId: null, unitId: null, clientUnitId: 'cu1', lessonId: null, clientLessonId: null,
    text, pinyin: `${text}-pinyin`, meaning: null, listTypes, confirmed: false,
    ...(preserved ? { preserved } : {})
  }
}

/** 通过桩主动触发一次行更新（模拟用户在双列编辑器里继续输入）。 */
function makeRecorder(captured: { rows?: unknown }, emitRows: () => unknown[]) {
  return defineComponent({
    props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
    emits: ['update:rows'],
    setup(props, { emit }) {
      return () => {
        captured.rows = props.rows
        return h('button', { 'data-emit': 'rows', onClick: () => emit('update:rows', emitRows()) })
      }
    }
  })
}

const setupStateOf = (wrapper: { vm: unknown }): Record<string, unknown> =>
  (wrapper.vm as { $: { setupState: Record<string, unknown> } }).$.setupState

/** 挂载并等待草稿加载完成。 */
async function mountEditor(entries: ReturnType<typeof draftEntry>[]) {
  fetchDraft.mockResolvedValue({
    revision: 3,
    baseContentVersion: null,
    draft: {
      subject: 'chinese', grade: 1, semester: 'first',
      publisher: '人民教育出版社', series: '统编语文', editionLabel: '2024 年修订版',
      sourceNote: '附录', coverageDeclaration: 'partial',
      units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
      lessons: [], entries
    }
  })
  const captured: { rows?: unknown } = {}
  const wrapper = mount(TextbookEditorView, {
    props: { editionId: 'demo-book' },
    global: { stubs: { WorksheetPairedEditor: makeRecorder(captured, () => [{ tokenId: 'r1', text: '水', pinyin: 'shuǐ' }]), WorksheetPreview: true } }
  })
  await Promise.resolve(); await Promise.resolve()
  return { wrapper, captured }
}

describe('多表类别与条目级来源（设计 115 / 297 行）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDraft.mockReset()
    saveDraft.mockReset()
    adminRequest.mockReset()
  })

  it('面板把类别的中文名与当前条目渲染出来，而不是只留机器标识', async () => {
    const { wrapper } = await mountEditor([draftEntry('r1', '山', ['writing'])])
    const text = wrapper.text()
    expect(text).toContain('表类别与来源')
    // 四个类别名必须可见（用户不可能去勾 "recognition" 这种标识）。
    for (const label of ['识字表', '写字表', '词语表', '表达与短语']) {
      expect(text).toContain(label)
    }
    expect(text).toContain('按表类别筛选')
    expect(text).toContain('当前范围的默认来源')
  })

  it('按类别筛选时编辑可见行，不得把被筛掉的行删掉（合并范围必须与可见范围一致）', async () => {
    const { wrapper } = await mountEditor([draftEntry('r1', '山', ['writing']), draftEntry('r2', '火', [])])
    const state = setupStateOf(wrapper)

    // 只显示"写字表"里的 r1。
    state.listTypeFilter = ['writing']
    await Promise.resolve()
    const visible = state.visibleEntries as { rowId: string }[]
    expect(visible.map(item => item.rowId)).toEqual(['r1'])

    // 在筛选状态下编辑 r1。
    await wrapper.find('[data-emit="rows"]').trigger('click')
    await Promise.resolve()

    // **鉴别力**：若合并仍按"整个节点"计算，r2 会被当成已删除而消失。
    const entries = state.entries as { rowId: string; text: string }[]
    expect(entries.map(item => item.rowId).sort()).toEqual(['r1', 'r2'])
    expect(entries.find(item => item.rowId === 'r1')?.text).toBe('水')
    expect(entries.find(item => item.rowId === 'r2')?.text).toBe('火')
  })

  it('勾选类别写入条目的 listTypes，并保留同一条的其它类别', async () => {
    const { wrapper } = await mountEditor([draftEntry('r1', '山', ['writing'])])
    const state = setupStateOf(wrapper)

    ;(state.onEntryListTypes as (rowId: string, value: string[]) => void)('r1', ['writing', 'recognition'])
    await Promise.resolve()
    const entry = (state.entries as { rowId: string; listTypes: string[] }[])[0]
    expect(entry.listTypes).toEqual(['recognition', 'writing'])

    // 再取消一个：只影响这一个类别。
    ;(state.onEntryListTypes as (rowId: string, value: string[]) => void)('r1', ['recognize-typo'])
    await Promise.resolve()
    expect((state.entries as { listTypes: string[] }[])[0].listTypes).toEqual([])
  })

  it('批量粘贴不再写死空类别：使用"当前范围默认类别"', async () => {
    const { wrapper } = await mountEditor([draftEntry('r1', '山', [])])
    const state = setupStateOf(wrapper)

    state.defaultListTypes = ['vocabulary']
    state.pasteText = '火\n土'
    ;(state.onBulkPaste as () => void)()
    await Promise.resolve()

    const appended = (state.entries as { text: string; listTypes: string[] }[]).filter(item => item.text !== '山')
    expect(appended.map(item => item.text)).toEqual(['火', '土'])
    // 关键：不是 `[]`。
    expect(appended.every(item => JSON.stringify(item.listTypes) === JSON.stringify(['vocabulary']))).toBe(true)
  })

  it('给范围设置默认来源：写入 preserved.sourceRefs，且保留界面上没有入口的其它字段', async () => {
    const { wrapper } = await mountEditor([
      draftEntry('r1', '山', [], { partOfSpeech: 'noun' }),
      draftEntry('r2', '火', [], { sourceRefs: [{ sourceId: '旧来源', locator: null, page: 1 }] })
    ])
    const state = setupStateOf(wrapper)

    state.defaultSourceRef = { sourceId: '附录词语表', locator: '第二列', page: 78 }
    ;(state.applyDefaultSourceToScope as () => void)()
    await Promise.resolve()

    const entries = state.entries as Parameters<typeof sourceRefsOf>[0][]
    expect(sourceRefsOf(entries[0])).toEqual([{ sourceId: '附录词语表', locator: '第二列', page: 78 }])
    // r2 原有的来源被新来源替换（第一条），但**不许丢**其它保留字段。
    expect(sourceRefsOf(entries[1])).toEqual([{ sourceId: '附录词语表', locator: '第二列', page: 78 }])
    expect((entries[0] as { preserved?: Record<string, unknown> }).preserved?.partOfSpeech).toBe('noun')
  })

  it('条目级来源录入直接写进 preserved.sourceRefs，页码为空时不写 0', async () => {
    const { wrapper } = await mountEditor([draftEntry('r1', '山', [])])
    const state = setupStateOf(wrapper)

    ;(state.onEntrySource as (rowId: string, patch: Record<string, unknown>) => void)('r1', { sourceId: '附录A', page: 82 })
    await Promise.resolve()
    const entry = (state.entries as Parameters<typeof primarySourceRefOf>[0][])[0]
    expect(primarySourceRefOf(entry)).toEqual({ sourceId: '附录A', locator: '', page: 82 })

    // 清空来源：不应该留下一个空对象来源数组。
    ;(state.onEntrySource as (rowId: string, patch: Record<string, unknown>) => void)('r1', { sourceId: '', page: null })
    await Promise.resolve()
    expect(sourceRefsOf((state.entries as Parameters<typeof sourceRefsOf>[0][])[0])).toEqual([])
  })
  it('类别改动会触发自动保存，且新类别真的进入请求体（否则刷新后改动就没了）', async () => {
    // **鉴别力**：处理函数只改 `entries` 而不标脏时，界面看起来改了、2 秒后
    // 却什么都不会保存；这条断言把"改了"与"存下来"连起来。
    vi.useFakeTimers()
    try {
      const { wrapper } = await mountEditor([draftEntry('r1', '山', [])])
      await vi.advanceTimersByTimeAsync(0)
      saveDraft.mockClear()
      saveDraft.mockResolvedValue({ revision: 4 })

      const state = setupStateOf(wrapper)
      ;(state.onEntryListTypes as (rowId: string, value: string[]) => void)('r1', ['recognition'])
      await vi.advanceTimersByTimeAsync(2100)

      expect(saveDraft).toHaveBeenCalledTimes(1)
      const payload = saveDraft.mock.calls.at(-1)?.[2] as { entries: { rowId: string; listTypes: string[] }[] }
      expect(payload.entries[0].listTypes).toEqual(['recognition'])
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
