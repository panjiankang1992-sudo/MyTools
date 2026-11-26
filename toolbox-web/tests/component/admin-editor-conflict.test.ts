import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

/**
 * F05/F12（设计 §3.7）：**保存冲突的差异可见与"读取最新"**。
 *
 * 审查要求原文：「冲突面板显示"服务器已有更新"，提供"查看差异""读取最新"；
 * 当前本地改动暂存在内存，读取最新前确认」。
 *
 * 这条测试挂的是**真实编辑器**而不是探针，因为要验证的是一整条链路：
 * 409 → 面板 → 差异 → 确认 → 回读修订号 → 下一次保存真的用新修订号。
 * 最后一个环节是本轮修的**真 bug**：此前不回读 revision，再点保存仍然 409，
 * 用户只能刷新整页。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks/demo-book/edit', query: {} })
}))

const fetchDraft = vi.fn()
const saveDraft = vi.fn()
const validateDraft = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchDraft: (...args: unknown[]) => fetchDraft(...args),
    saveDraft: (...args: unknown[]) => saveDraft(...args),
    validateDraft: (...args: unknown[]) => validateDraft(...args)
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

/** 服务端草稿响应。 */
function draftResult(revision: number, entries: ReturnType<typeof draftEntry>[]) {
  return {
    revision,
    baseContentVersion: 'demo-book@2',
    draft: {
      subject: 'chinese', grade: 1, semester: 'first',
      publisher: '人民教育出版社', series: '统编语文', editionLabel: '2024 年修订版',
      sourceNote: '附录', coverageDeclaration: 'partial',
      units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
      lessons: [], entries
    }
  }
}

/** 记录 props 的桩：既能观察 rows，也能主动触发一次行更新。 */
function makeRecorder(captured: { rows?: unknown }) {
  return defineComponent({
    props: { subject: { type: String, default: '' }, rows: { type: Array, default: () => [] } },
    emits: ['update:rows'],
    setup(props, { emit }) {
      return () => {
        captured.rows = props.rows
        return h('button', {
          'data-emit': 'rows',
          // 本地改动：把 r1 的汉字改成"水"。
          onClick: () => emit('update:rows', [{ tokenId: 'r1', text: '水', pinyin: 'shuǐ', automaticInitialized: false }])
        })
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

describe('保存冲突：差异可见 + 读取最新（F05/F12）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDraft.mockReset()
    saveDraft.mockReset()
    validateDraft.mockReset()
    adminRequest.mockReset()
  })

  it('409 时进入冲突面板（含"服务器已有更新""查看差异""读取最新"），且本地缓冲保留', async () => {
    fetchDraft.mockResolvedValueOnce(draftResult(3, [draftEntry('r1', '山', 'shān')]))
    saveDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { expectedRevision: 3 }, 409))

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    await wrapper.find('[data-emit="rows"]').trigger('click')
    await button(wrapper, '保存草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    const text = wrapper.text()
    expect(text).toContain('服务器已有更新')
    expect(button(wrapper, '查看差异')).toBeTruthy()
    expect(button(wrapper, '读取最新')).toBeTruthy()
    // **本地改动没有被丢掉，也没有被标成已保存**。
    const entries = setupStateOf(wrapper).entries as { rowId: string; text: string }[]
    expect(entries.find(entry => entry.rowId === 'r1')?.text).toBe('水')
    expect(text).toContain('当前编辑内容仍保留在本页')
  })

  it('查看差异时真的比对两侧条目：改过的与服务器新增的都要列出来', async () => {
    fetchDraft
      .mockResolvedValueOnce(draftResult(3, [draftEntry('r1', '山', 'shān')]))
      // 查看差异时读到的服务器版本：r1 仍是"山"，另有 r2"火"。
      .mockResolvedValue(draftResult(5, [draftEntry('r1', '山', 'shān'), draftEntry('r2', '火', 'huǒ')]))
    saveDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { expectedRevision: 3 }, 409))

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    await wrapper.find('[data-emit="rows"]').trigger('click')
    await button(wrapper, '保存草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    await button(wrapper, '查看差异').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    const text = wrapper.text()
    // **鉴别力**：只显示"冲突了"的实现不会出现这两类说明。
    expect(text).toContain('双方都改过')
    expect(text).toContain('服务器新增')
    expect(text).toContain('本地：水')
    expect(text).toContain('服务器：山')
    expect(text).toContain('服务器：火')
  })

  it('读取最新必须先确认；确认后回读修订号，下一次保存用新修订号（否则永远 409）', async () => {
    fetchDraft
      .mockResolvedValueOnce(draftResult(3, [draftEntry('r1', '山', 'shān')]))
      .mockResolvedValue(draftResult(5, [draftEntry('r1', '山', 'shān'), draftEntry('r2', '火', 'huǒ')]))
    saveDraft
      .mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { expectedRevision: 3 }, 409))
      .mockResolvedValue({ revision: 6 })

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    await wrapper.find('[data-emit="rows"]').trigger('click')
    await button(wrapper, '保存草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    // 1) 未确认前**不允许**直接替换本地内容。
    await button(wrapper, '读取最新').trigger('click')
    await Promise.resolve()
    expect(wrapper.text()).toContain('确认读取最新')
    expect(setupStateOf(wrapper).revision).toBe(3)

    // 2) 确认后才真的读取，并且**本地修订号被更新**到服务器那一版。
    await button(wrapper, '确认读取最新').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(setupStateOf(wrapper).revision).toBe(5)
    expect((setupStateOf(wrapper).entries as unknown[]).length).toBe(2)

    // 3) 再保存时必须带**新**修订号。
    //
    // 这是本轮修掉的真 bug：此前 revision 仍是 3，点保存依旧 409。
    await button(wrapper, '保存草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    const lastCall = saveDraft.mock.calls.at(-1) as unknown[]
    expect(lastCall?.[1]).toBe(5)
  })

  it('读取最新后本地改动被暂存到内存，可以再放回编辑器', async () => {
    fetchDraft
      .mockResolvedValueOnce(draftResult(3, [draftEntry('r1', '山', 'shān')]))
      .mockResolvedValue(draftResult(5, [draftEntry('r1', '山', 'shān'), draftEntry('r2', '火', 'huǒ')]))
    saveDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { expectedRevision: 3 }, 409))

    const captured: { rows?: unknown } = {}
    const wrapper = mount(TextbookEditorView, {
      props: { editionId: 'demo-book' },
      global: { stubs: { WorksheetPairedEditor: makeRecorder(captured), WorksheetPreview: true } }
    })
    await Promise.resolve(); await Promise.resolve()

    await wrapper.find('[data-emit="rows"]').trigger('click')
    await button(wrapper, '保存草稿').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    await button(wrapper, '读取最新').trigger('click')
    await Promise.resolve()
    await button(wrapper, '确认读取最新').trigger('click')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    // 服务器版本已载入：本地那一条"水"不在了。
    expect((setupStateOf(wrapper).entries as { text: string }[]).some(entry => entry.text === '水')).toBe(false)

    // **暂存的本地改动可以恢复**：不是"直接丢弃"。
    await button(wrapper, '恢复本地改动').trigger('click')
    await Promise.resolve()
    const entries = setupStateOf(wrapper).entries as { rowId: string; text: string }[]
    expect(entries.find(entry => entry.rowId === 'r1')?.text).toBe('水')
  })
})
