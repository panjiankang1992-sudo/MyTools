import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NSelect } from 'naive-ui'

/**
 * F12：**列表必须显示草稿的真实名称，而不是内部 ID**。
 *
 * 审查原话：

 * > 草稿可能显示内部 ID，缺"已发布·有修改"和部分收录状态。
 *
 * 后端那一半在第 27 轮修好了（`metadataJson` 对草稿不再返回 null）；
 * **这一条验证它在界面上的效果** —— 这是审查要求"挂载真实组件"的意义。
 */

const routerPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush, replace: vi.fn() }),
  useRoute: () => ({ path: '/admin/textbooks', query: {} })
}))

const fetchTextbookList = vi.fn()
vi.mock('../../src/services/admin-textbooks-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-textbooks-api')>()
  return {
    ...actual,
    fetchTextbookList: (...args: unknown[]) => fetchTextbookList(...args)
  }
})

const { default: TextbookListView } = await import('../../src/views/admin/TextbookListView.vue')

// 与编辑器那条测试同理：全局监听/异步任务不该跨测试残留。
enableAutoUnmount(afterEach)

/** 更多操作默认收起，管理列表只在主动展开后加载。 */
async function openMore(wrapper: VueWrapper): Promise<void> {
  const details = wrapper.get('details')
  ;(details.element as HTMLDetailsElement).open = true
  await details.trigger('toggle')
  await flushPromises()
}

function select(wrapper: VueWrapper, placeholder: string) {
  const found = wrapper.findAllComponents(NSelect).find(item => item.props('placeholder') === placeholder)
  expect(found, `应能找到 ${placeholder}`).toBeTruthy()
  return found!
}

async function chooseRange(wrapper: VueWrapper, subject = 'chinese', grade = 1, semester = 'first'): Promise<void> {
  await select(wrapper, '请选择科目').vm.$emit('update:value', subject)
  await select(wrapper, '请选择年级').vm.$emit('update:value', grade)
  await select(wrapper, '请选择学期').vm.$emit('update:value', semester)
}

function book(editionId = 'demo-book', series = '统编语文') {
  return {
    editionId, metadataJson: JSON.stringify({ subject: 'chinese', grade: 1, semester: 'first', publisher: '人民教育出版社', series, editionLabel: '2024 年版' }),
    contentVersion: null, published: false, hasUnpublishedChanges: false, updatedBy: 'admin', updatedAt: 1_700_000_000_000
  }
}

describe('真实列表页可以挂载（F12 的验收基石）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchTextbookList.mockReset()
    routerPush.mockReset()
  })

  it('草稿行显示真实名称（出版社／系列），而不是只显示内部 ID', async () => {
    fetchTextbookList.mockResolvedValue({
      items: [{
        editionId: 'demo-book',
        metadataJson: JSON.stringify({
          subject: 'chinese', grade: 1, semester: 'first',
          publisher: '人民教育出版社', series: '统编语文',
          // **已发布元数据里的字段名是 coverageStatus**（草稿里才叫 coverageDeclaration）。
          coverageStatus: 'partial'
        }),
        contentVersion: null,
        published: true,
        // 发布之后又改过草稿（F12）。
        hasUnpublishedChanges: true,
        updatedBy: 'yuyutian',
        updatedAt: 1_700_000_000_000
      }],
      total: 1
    })

    const wrapper = mount(TextbookListView, { global: { stubs: { RouterLink: true } } })
    await openMore(wrapper)

    const text = wrapper.text()
    // **判别力**：只断言"渲染出了东西"太弱——要断言**真实元数据**进了界面。
    expect(text).toContain('人民教育出版社')
    expect(text).toContain('统编语文')
    // F12：收录范围也必须显示出来——元数据里一直有它，而界面此前从未渲染。
    expect(text).toContain('部分收录')
    // F12：发布之后又改过的草稿必须标出来，
    // 否则管理员会以为线上就是眼前这一版。
    expect(text).toContain('有修改')
  })

  it('F12：选了"状态=草稿"后，请求必须带上这个筛选条件', async () => {
    // 审查探针的原话是「新建语文草稿总列表 1 条，**筛语文变 0 条**」。
    // 那一半是**后端**的问题（第 26 轮已修、有反向验证）。
    // 这一条锁的是**前端真的把筛选条件发出去了**——否则后端修好了也没人用。
    fetchTextbookList.mockResolvedValue({ items: [], total: 0 })

    const wrapper = mount(TextbookListView, { global: { stubs: { RouterLink: true } } })
    await openMore(wrapper)
    fetchTextbookList.mockClear()
    fetchTextbookList.mockResolvedValue({ items: [], total: 0 })

    // 按 aria-label 找到"状态"那个下拉（界面上有五个筛选控件，不能拿第一个凑）。
    // 按**组件类型**查找：Naive UI 的组件名不是 `NSelect`，
    // 用 `{ name: 'NSelect' }` 会一个都找不到（我第一版就是这么写的）。
    const selects = wrapper.findAllComponents(NSelect)
    const status = selects.find(item => item.props('placeholder') === '状态')
    expect(status, '必须能找到"状态"筛选控件').toBeTruthy()

    await status!.vm.$emit('update:value', 'draft')
    await Promise.resolve()
    await Promise.resolve()

    expect(fetchTextbookList).toHaveBeenCalled()
    const args = fetchTextbookList.mock.calls.at(-1)?.[0] as { status?: string }
    expect(args.status).toBe('draft')
  })

  it('元数据缺失时回落到内部 ID，并明确标成"草稿（尚未发布）"', async () => {
    // 这一条是**反面**：它说明第 27 轮的修复为什么必要——
    // 没有元数据时，用户只能看到内部 ID。
    fetchTextbookList.mockResolvedValue({
      items: [{
        editionId: 'no-metadata-book',
        metadataJson: null,
        contentVersion: null,
        published: false,
        hasUnpublishedChanges: false,
        updatedBy: 'yuyutian',
        updatedAt: 1_700_000_000_000
      }],
      total: 1
    })

    const wrapper = mount(TextbookListView, { global: { stubs: { RouterLink: true } } })
    await openMore(wrapper)

    const text = wrapper.text()
    expect(text).toContain('no-metadata-book')
    expect(text).toContain('草稿')
  })
})

describe('简洁的教材选择入口', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchTextbookList.mockReset()
    routerPush.mockReset()
  })

  it('默认只展示四项选择和开始录入，管理列表与恢复入口收起', async () => {
    const wrapper = mount(TextbookListView)
    await flushPromises()
    expect(wrapper.findAllComponents(NSelect)).toHaveLength(4)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-restore"]').exists()).toBe(false)
    expect(fetchTextbookList).not.toHaveBeenCalled()
    expect(select(wrapper, '请选择年级').props('disabled')).toBe(true)
    expect(select(wrapper, '请选择学期').props('disabled')).toBe(true)
    expect(select(wrapper, '请选择教材').props('disabled')).toBe(true)
    expect(wrapper.findAll('button').find(button => button.text() === '开始录入')?.attributes('disabled')).toBeDefined()
  })

  it('按科目年级学期加载教材，以名称和版本显示，用户选择后开始录入', async () => {
    fetchTextbookList.mockResolvedValue({ items: [book()], total: 1 })
    const wrapper = mount(TextbookListView)
    await chooseRange(wrapper)
    await flushPromises()
    expect(fetchTextbookList).toHaveBeenLastCalledWith({ subject: 'chinese', grade: 1, semester: 'first', page: 1, pageSize: 100 })
    const textbook = select(wrapper, '请选择教材')
    expect(textbook.props('value')).toBeNull()
    const options = textbook.props('options') as Array<{ label: string, value: string }>
    expect(options[0]?.label).toContain('统编语文')
    expect(options[0]?.label).toContain('2024 年版')
    expect(options[0]?.label).not.toContain('demo-book')
    await textbook.vm.$emit('update:value', 'demo-book')
    await wrapper.findAll('button').find(button => button.text() === '开始录入')!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/admin/textbooks/demo-book/edit')
  })

  it('更换科目立即清空下游，旧范围的晚到响应不能覆盖新范围', async () => {
    let resolveOld!: (value: unknown) => void
    fetchTextbookList.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce({ items: [book('english-book', '译林英语')], total: 1 })
    const wrapper = mount(TextbookListView)
    await chooseRange(wrapper)
    await select(wrapper, '请选择科目').vm.$emit('update:value', 'english')
    expect(select(wrapper, '请选择年级').props('value')).toBeNull()
    expect(select(wrapper, '请选择学期').props('value')).toBeNull()
    expect(select(wrapper, '请选择教材').props('options')).toEqual([])
    await select(wrapper, '请选择年级').vm.$emit('update:value', 3)
    await select(wrapper, '请选择学期').vm.$emit('update:value', 'second')
    await flushPromises()
    await select(wrapper, '请选择教材').vm.$emit('update:value', 'english-book')
    resolveOld({ items: [book('old-book', '旧语文教材')], total: 1 })
    await flushPromises()
    expect(select(wrapper, '请选择教材').props('value')).toBe('english-book')
    expect((select(wrapper, '请选择教材').props('options') as Array<{ value: string }>).map(item => item.value)).toEqual(['english-book'])
    expect(wrapper.text()).not.toContain('旧语文教材')
  })

  it('教材选项读取接口后续页，管理列表仍保留独立分页', async () => {
    fetchTextbookList.mockResolvedValueOnce({ items: [book('first-book')], total: 2 })
      .mockResolvedValueOnce({ items: [book('second-book')], total: 2 })
      .mockResolvedValueOnce({ items: [book()], total: 21 })
    const wrapper = mount(TextbookListView)
    await chooseRange(wrapper)
    await flushPromises()
    expect((select(wrapper, '请选择教材').props('options') as unknown[])).toHaveLength(2)
    expect(fetchTextbookList.mock.calls[1]?.[0]).toEqual({ subject: 'chinese', grade: 1, semester: 'first', page: 2, pageSize: 100 })
    await openMore(wrapper)
    expect(wrapper.find('.list-pagination').exists()).toBe(true)
    expect(wrapper.find('[data-testid="open-restore"]').exists()).toBe(true)
    expect(fetchTextbookList).toHaveBeenLastCalledWith({ keyword: undefined, status: undefined, page: 1, pageSize: 20 })
  })

  it('查询失败明确提示且可以重试，不显示成无教材', async () => {
    fetchTextbookList.mockRejectedValueOnce(new Error('unavailable'))
      .mockResolvedValueOnce({ items: [book()], total: 1 })
    const wrapper = mount(TextbookListView)
    await chooseRange(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('教材加载失败')
    expect(wrapper.text()).not.toContain('这个范围还没有教材')
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('教材加载失败')
    expect((select(wrapper, '请选择教材').props('options') as unknown[])).toHaveLength(1)
  })
})
