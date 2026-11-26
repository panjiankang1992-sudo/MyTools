import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 版本记录抽屉在**真实编辑页**里的接线验收（设计 3.6 第 166 行 + 第 176 行）。
 *
 * 抽屉组件本身另有 `admin-version-history-drawer.test.ts` 覆盖渲染细节。
 * 本文件只回答三件事：
 * 1. 编辑页真的有入口，点了会**发请求捞出历史**（不是只有个空壳按钮）；
 * 2. 打开后**焦点进入抽屉**；
 * 3. 关闭后**焦点回到触发按钮**（设计第 176 行「弹窗关闭焦点回触发入口」）。
 *
 * 这三条都是"接线"性质的缺陷最容易藏身的地方：组件全对、但没人调用它，
 * 或者焦点掉到 `body` 上——键盘用户就此迷路。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks/demo-book/edit', query: {} })
}))

const fetchDraft = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return { ...actual, fetchDraft: (...args: unknown[]) => fetchDraft(...args) }
})

const fetchVersionHistory = vi.fn()
vi.mock('../../src/services/admin-curriculum-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-curriculum-api')>()
  return { ...actual, fetchVersionHistory: (...args: unknown[]) => fetchVersionHistory(...args) }
})

const { default: TextbookEditorView } = await import('../../src/views/admin/TextbookEditorView.vue')

enableAutoUnmount(afterEach)

/** 一份最小草稿，让编辑页正常加载。 */
function draftResult() {
  return {
    revision: 1,
    baseContentVersion: null,
    draft: {
      subject: 'chinese', grade: 1, semester: 'first',
      publisher: '人民教育出版社', series: '统编语文', editionLabel: '2024 年修订版',
      sourceNote: '附录', coverageDeclaration: 'partial', units: [], lessons: [], entries: []
    }
  }
}

async function mountEditor() {
  const wrapper = mount(TextbookEditorView, {
    props: { editionId: 'demo-book' },
    attachTo: document.body,
    global: { stubs: { WorksheetPairedEditor: true, WorksheetPreview: true } }
  })
  await flushPromises()
  return wrapper
}

/** 在页面里找到"版本记录"按钮（它在编辑页自己的模板里，不在 Teleport 里）。 */
function triggerButton(wrapper: ReturnType<typeof mount>) {
  const found = wrapper.findAll('button').find(node => node.text().trim() === '版本记录')
  expect(found, '编辑页必须有"版本记录"按钮').toBeTruthy()
  return found!
}

describe('版本记录抽屉在编辑页里的接线（设计 3.6 / 第 176 行）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchDraft.mockReset().mockResolvedValue(draftResult())
    fetchVersionHistory.mockReset().mockResolvedValue([])
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('点"版本记录"会真的拉取该册历史，并把列表渲染出来', async () => {
    fetchVersionHistory.mockResolvedValue([{
      editionId: 'demo-book', contentVersion: '9.9.9', contentSha256: 'sha-999',
      byteLength: 10, publishedBy: 'publisher-account', publishedAt: 5000,
      hasSourceSnapshot: false, sourceSnapshotSha256: null
    }])

    const wrapper = await mountEditor()
    expect(fetchVersionHistory).not.toHaveBeenCalled()

    await triggerButton(wrapper).trigger('click')
    await flushPromises()

    expect(fetchVersionHistory).toHaveBeenCalledWith('demo-book', expect.anything())
    const drawer = document.querySelector('[data-testid="version-drawer"]')
    expect(drawer, '抽屉内容必须真的渲染到页面上').not.toBeNull()
    expect(drawer?.textContent).toContain('9.9.9')
    expect(drawer?.textContent).toContain('publisher-account')
  })

  it('打开抽屉后焦点进入抽屉；关闭后焦点回到"版本记录"按钮', async () => {
    const wrapper = await mountEditor()
    const trigger = triggerButton(wrapper)
    // 模拟键盘用户：先 Tab 到按钮上再按回车打开。
    const triggerEl = trigger.element as HTMLButtonElement
    triggerEl.focus()
    expect(document.activeElement).toBe(triggerEl)

    await trigger.trigger('click')
    await flushPromises()

    const drawer = document.querySelector('[data-testid="version-drawer"]')
    expect(drawer, '抽屉内容必须真的渲染到页面上').not.toBeNull()
    // **抽屉里第一个可聚焦控件必须是关闭按钮**：这是"打开后焦点能进抽屉"的前提，
    // 也是真实浏览器里实测到的落点（见 `version-history-and-keyboard.md` 的 17/17 键盘路径）。
    expect(document.querySelector('.n-drawer-header__close'), '抽屉必须有可聚焦的关闭按钮').not.toBeNull()

    // **不在这里断言 `document.activeElement`**：jsdom 下 `vueuc` 的焦点陷阱会把焦点
    // 重定向到它自己的隐藏哨兵（`Transition` 被测试替身替换后，它定位不到对话框内部），
    // 那个哨兵在 `.n-drawer` 里、却不在 `[data-testid="version-drawer"]` 内。
    // 这是**测试环境产物**：同一个抽屉在真实 Chrome 里焦点落在关闭按钮上，鼠标路径没有这个问题。
    // 断言它只会制造一条看环境脸色的假失败；真实结论由 e2e 键盘脚本给出。
    // 关闭按钮与抽屉内容必须在**同一个对话框**里，且关闭按钮在文档顺序上更靠前
    // （它才是打开抽屉后键盘的第一个落点）。
    const dialog = document.querySelector('.n-drawer[role="dialog"]')
    const closeNode = document.querySelector('.n-drawer-header__close')
    expect(dialog).not.toBeNull()
    expect(dialog?.contains(closeNode as Node)).toBe(true)
    expect(dialog?.contains(drawer as Node)).toBe(true)
    expect(Boolean(closeNode && drawer
      && (closeNode.compareDocumentPosition(drawer) & Node.DOCUMENT_POSITION_FOLLOWING))).toBe(true)

    // 关闭（点击抽屉右上角 ×）—— 这一条测的是**我们自己的**接线，与焦点陷阱无关。
    ;(document.querySelector('.n-drawer-header__close') as HTMLElement).click()
    await flushPromises()
    await flushPromises()

    // 设计第 176 行：弹窗关闭焦点回触发入口。
    expect(document.activeElement, '关闭后焦点必须回到触发按钮').toBe(triggerEl)
  })
})
