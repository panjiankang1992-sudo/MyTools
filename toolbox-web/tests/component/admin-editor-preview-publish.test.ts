import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

/**
 * 设计 164 / 162 / 362 行：**题型选择、预览快照与发布请求体**。
 *
 * 三条都曾经整段缺失：
 * - 模板里**没有任何题型 UI**，用户永远只能拿到该学科的第一种题型；
 * - `openPreview()` 不 flush 2 秒防抖的自动保存，预览的是**保存前的整册**；
 * - `publishDraft` 只传 editionId，`changeNote` 前端**零命中**。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks/demo-book/edit', query: {} })
}))

const fetchDraft = vi.fn()
const saveDraft = vi.fn()
const publishDraft = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchDraft: (...args: unknown[]) => fetchDraft(...args),
    saveDraft: (...args: unknown[]) => saveDraft(...args),
    publishDraft: (...args: unknown[]) => publishDraft(...args)
  }
})

/** 预览走的是 `adminRequest`（`useAdminDraftPreview`），单独抓它才能断言顺序与参数。 */
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

const { AdminApiError } = await import('../../src/services/admin-api')
const { default: TextbookEditorView } = await import('../../src/views/admin/TextbookEditorView.vue')

enableAutoUnmount(afterEach)

/** 构造一条语文草稿条目。 */
function draftEntry(rowId: string, text: string, pinyin: string) {
  return {
    rowId, entryId: null, unitId: null, clientUnitId: 'cu1', lessonId: null, clientLessonId: null,
    text, pinyin, meaning: null, listTypes: [] as string[], confirmed: false
  }
}

/** 服务端草稿响应；`subject` 决定双列语义。 */
function draftResult(revision: number, subject: 'chinese' | 'english' = 'chinese') {
  const entries = subject === 'chinese'
    ? [draftEntry('r1', '山', 'shān')]
    : [{ rowId: 'r1', entryId: null, unitId: null, clientUnitId: 'cu1', lessonId: null, clientLessonId: null,
         text: 'apple', pinyin: null, meaning: '苹果', listTypes: [] as string[], confirmed: false }]
  return {
    revision,
    baseContentVersion: 'demo-book@2',
    draft: {
      subject, grade: 1, semester: 'first',
      publisher: '人民教育出版社', series: subject === 'chinese' ? '统编语文' : '人教版英语',
      editionLabel: '2024 年修订版', sourceNote: '附录', coverageDeclaration: 'partial',
      units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
      lessons: [], entries
    }
  }
}

/**
 * 带一篇课文的草稿：课文属于 `cu1`，用于验证"课文的父单元要能带出来"。
 *
 * @param revision 服务端修订号
 */
function draftResultWithLesson(revision: number) {
  const base = draftResult(revision)
  return {
    ...base,
    draft: {
      ...base.draft,
      lessons: [
        { lessonId: null, clientLessonId: 'cl1', unitId: null, clientUnitId: 'cu1', title: '天地人', order: 1 }
      ]
    }
  }
}

/** 记录 props 且能主动触发一次行更新的桩。 */
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

const button = (wrapper: ReturnType<typeof mount>, label: string) => {
  const found = wrapper.findAll('button').find(item => item.text().trim() === label)
  expect(found, `必须能找到"${label}"按钮`).toBeTruthy()
  return found!
}

/** 目录树里的"全部内容（N）"按钮；N 会随内容变化，因此按前缀找。 */
const wholeBookButton = (wrapper: ReturnType<typeof mount>) => {
  const found = wrapper.findAll('button').find(item => item.text().trim().startsWith('全部内容'))
  expect(found, '必须能找到"全部内容"按钮').toBeTruthy()
  return found!
}

/** 预览接口的最小载荷（空条目 → 生成会提前返回，测试不必真的渲染 PDF）。 */
const emptyPreviewPayload = { edition: { editionId: 'demo-book' }, units: [], lessons: [], entries: [], draftPreview: true }

/**
 * 第 `index` 次预览请求的**真实 URL 路径**（含查询串）。
 *
 * 断言真实 URL 而不是"有没有调用"：`nodeKind`／`nodeKey` 传错、漏传、
 * 或顺序不对，都只有把整条路径逐字比出来才看得见。
 */
const previewPath = (index: number) => adminRequest.mock.calls[index]?.[0] as string

describe('预览与发布（设计 164 / 362 行）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDraft.mockReset()
    saveDraft.mockReset()
    publishDraft.mockReset()
    adminRequest.mockReset()
  })

  it('点预览前必须先保存成功，且预览用的修订号就是保存后的修订号', async () => {
    const order: string[] = []
    fetchDraft.mockResolvedValue(draftResult(3))
    saveDraft.mockImplementation(() => {
      order.push('save')
      return Promise.resolve({ revision: 7 })
    })
    adminRequest.mockImplementation((path: string) => {
      order.push(`request:${path}`)
      return Promise.resolve(emptyPreviewPayload)
    })

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured, () => [{ tokenId: 'r1', text: '水', pinyin: 'shuǐ' }]), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    // 制造"防抖中的未保存改动"：这正是此前会预览到保存前内容的情形。
    await wrapper.find('[data-emit="rows"]').trigger('click')
    await Promise.resolve()
    await button(wrapper, '预览草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    // **鉴别力**：不 flush 的实现里 order 只有 request，没有 save。
    expect(order[0]).toBe('save')
    expect(order.some(item => item.includes('/draft/preview'))).toBe(true)
    expect(order.indexOf('save')).toBeLessThan(order.findIndex(item => item.includes('/draft/preview')))
    // 预览用的修订号必须与保存后的一致（否则预览的可能是保存前那一版）。
    expect(wrapper.text()).toContain('本次预览基于修订 7')
    expect((setupStateOf(wrapper).draftPreview as { revision: { value: number } }).revision.value).toBe(7)
  })

  it('保存失败时不发预览请求，而是取消预览（绝不拿旧内容糊弄）', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    saveDraft.mockRejectedValue(new AdminApiError('TOOLBOX_ADMIN_NETWORK', 'admin.request.network', undefined, 0))

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured, () => [{ tokenId: 'r1', text: '水', pinyin: 'shuǐ' }]), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()
    await wrapper.find('[data-emit="rows"]').trigger('click')
    await Promise.resolve()
    await button(wrapper, '预览草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    expect(adminRequest).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已取消预览')
    // 也没有打开预览面板（不留下"看起来预览成功了"的空壳）。
    expect(wrapper.text()).not.toContain('本次预览基于修订')
  })

  it('题型按学科给出两种，切学科后不匹配的题型被纠正', async () => {
    fetchDraft.mockResolvedValue(draftResult(3, 'chinese'))

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    const state = setupStateOf(wrapper)
    const chineseChoices = state.previewModeChoices as { label: string; value: string }[]
    expect(chineseChoices.map(item => item.value)).toEqual(['chinese-dictation', 'chinese-pinyin'])
    // 中文名必须真的渲染出来，而不是把机器标识给用户看。
    expect(wrapper.text()).toContain('题型：汉字字帖')

    // 切到英语：题型必须被纠正成英语题型，选项也跟着换。
    ;(state.onSubjectChange as (next: string) => void)('english')
    await Promise.resolve()
    expect(state.previewMode).toBe('english-dictation')
    const englishChoices = state.previewModeChoices as { label: string; value: string }[]
    expect(englishChoices.map(item => item.value)).toEqual(['english-dictation', 'english-meaning'])
    expect(wrapper.text()).toContain('题型：英语默写')
    // 再切回语文：上次选的英语题型仍然要被纠正（不能跨学科残留）。
    ;(state.onSubjectChange as (next: string) => void)('chinese')
    await Promise.resolve()
    expect(state.previewMode).toBe('chinese-dictation')
  })

  it('整册范围：预览请求 URL 不带 nodeKind／nodeKey，文案如实写"当前范围：整册"', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    adminRequest.mockResolvedValue(emptyPreviewPayload)

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('当前范围：整册')
    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()

    // **真实 URL**：整册就是两个参数都不传（后端缺省即整册）。
    expect(previewPath(0)).toBe('/textbooks/demo-book/draft/preview?draftRevision=3')
    expect(previewPath(0)).not.toContain('nodeKind')
    expect(previewPath(0)).not.toContain('nodeKey')
  })

  it('选「第一单元」：请求 URL 含 nodeKind=unit 与真实 nodeKey，文案与范围一致', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    adminRequest.mockResolvedValue(emptyPreviewPayload)

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await flushPromises()

    // 用界面上真实的目录按钮选范围，而不是直接改内部状态。
    await button(wrapper, '第一单元').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前范围：单元：第一单元')

    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()

    expect(adminRequest).toHaveBeenCalledTimes(1)
    expect(previewPath(0)).toBe('/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=3')
    // 文案不许再写"仍按全册生成"（那是过时的限制说明）。
    expect(wrapper.text()).toContain('本次预览只包含该单元及其课文与条目。')
    expect(wrapper.text()).not.toContain('仍包含全部单元')
  })

  it('选课文：请求 URL 含 nodeKind=lesson，标签把父单元带出来', async () => {
    fetchDraft.mockResolvedValue(draftResultWithLesson(3))
    adminRequest.mockResolvedValue(emptyPreviewPayload)

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await flushPromises()

    await button(wrapper, '天地人').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前范围：课文：天地人（第一单元）')

    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()

    expect(previewPath(0)).toBe('/textbooks/demo-book/draft/preview?nodeKind=lesson&nodeKey=cl1&draftRevision=3')
    expect(wrapper.text()).toContain('本次预览只包含该课文及其所属单元（第一单元）。')
  })

  it('切范围会重新取预览：整册 → 第一单元 → 整册，每次都换 URL', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    adminRequest.mockResolvedValue(emptyPreviewPayload)

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await flushPromises()

    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()
    await button(wrapper, '第一单元').trigger('click')
    await flushPromises()
    await wholeBookButton(wrapper).trigger('click')
    await flushPromises()

    // **鉴别力**：只改文案的实现里这里只会有一条请求。
    expect(adminRequest).toHaveBeenCalledTimes(3)
    expect(previewPath(0)).toBe('/textbooks/demo-book/draft/preview?draftRevision=3')
    expect(previewPath(1)).toBe('/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=3')
    expect(previewPath(2)).toBe('/textbooks/demo-book/draft/preview?draftRevision=3')
    expect(wrapper.text()).toContain('当前范围：整册')
  })

  it('404 previewScopeNotFound：显示明确错误，且绝不静默回退成整册', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    // 第一次（带范围）404；并**故意**给第二次请求准备一个能成功的整册响应——
    // 这样"404 后改发整册"的实现会真的拿到内容，而不是靠同一个错误蒙混过去。
    adminRequest
      .mockRejectedValueOnce(new AdminApiError(
        'TOOLBOX_ADMIN_006',
        'admin.notFound',
        { reason: 'previewScopeNotFound', nodeKind: 'unit', nodeKey: 'cu1' },
        404
      ))
      .mockResolvedValue(emptyPreviewPayload)

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await flushPromises()

    await button(wrapper, '第一单元').trigger('click')
    await flushPromises()
    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()

    // 明确错误：说清是"范围不存在"，而不是一句"目标不存在"。
    expect(wrapper.text()).toContain('预览范围不存在')
    expect(wrapper.text()).toContain('不会退化成整册')
    // **不许静默回退**：只有一次请求，且那次仍然是带范围的；
    // 若实现"404 后改发整册"，这里会看到第二次不带 nodeKind 的请求。
    expect(adminRequest).toHaveBeenCalledTimes(1)
    expect(previewPath(0)).toContain('nodeKind=unit')
    expect(previewPath(0)).toContain('nodeKey=cu1')
    // 也没有产出任何"看起来成功"的产物。
    const draftPreview = setupStateOf(wrapper).draftPreview as { artifact: { value: unknown } }
    expect(draftPreview.artifact.value).toBeNull()
  })

  it('draftRevision 用 flush 之后的修订号（保存前 3 → 保存后 7）', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    saveDraft.mockResolvedValue({ revision: 7 })
    adminRequest.mockResolvedValue(emptyPreviewPayload)

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured, () => [{ tokenId: 'r1', text: '水', pinyin: 'shuǐ' }]), WorksheetPreview: true } }
    })
    await flushPromises()

    await button(wrapper, '第一单元').trigger('click')
    await flushPromises()
    // 制造"防抖中的未保存改动"：flush 必须真的发一次保存，修订号才会变。
    await wrapper.find('[data-emit="rows"]').trigger('click')
    await flushPromises()
    await button(wrapper, '预览草稿').trigger('click')
    await flushPromises()

    expect(saveDraft).toHaveBeenCalledTimes(1)
    expect(previewPath(0)).toBe('/textbooks/demo-book/draft/preview?nodeKind=unit&nodeKey=cu1&draftRevision=7')
    // **鉴别力**：用保存前的 revision ref 会得到 3。
    expect(previewPath(0)).not.toContain('draftRevision=3')
    expect(wrapper.text()).toContain('本次预览基于修订 7')
  })

  it('发布请求体包含 changeNote 与 draftRevision/baseContentVersion（设计 362 行）', async () => {
    fetchDraft.mockResolvedValue(draftResult(3))
    saveDraft.mockResolvedValue({ revision: 5 })
    publishDraft.mockResolvedValue({ editionId: 'demo-book', contentVersion: 'v9', contentSha256: 'h', snapshotReloaded: true })

    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    // 管理员的"修改摘要"必须真的进入请求体；此前前端零命中。
    setupStateOf(wrapper).changeNote = '修订第一单元的词表'
    await button(wrapper, '保存并发布').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    expect(publishDraft).toHaveBeenCalledTimes(1)
    const [editionId, intent] = publishDraft.mock.calls[0] as [string, Record<string, unknown>]
    expect(editionId).toBe('demo-book')
    expect(intent).toEqual({ draftRevision: 5, baseContentVersion: 'demo-book@2', changeNote: '修订第一单元的词表' })
    expect(wrapper.text()).toContain('已发布 v9')
  })
})
