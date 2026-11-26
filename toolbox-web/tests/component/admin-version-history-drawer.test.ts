import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 设计 3.6 第 166 行「版本记录放右侧抽屉」的组件验收。
 *
 * 后端三条**只读**端点早已就绪（`GET /admin/v1/curriculum/versions/{editionId}`、
 * `.../{contentVersion}/bytes`、`.../{contentVersion}/source-snapshot`），
 * 但前端**零处**调用它们——本文件验收的就是这条缺失的链路。
 *
 * 这里挂载**真实** `VersionHistoryDrawer.vue`，只把服务层换掉；
 * 组件的加载／排序／格式化／错误处理都跑真身。
 *
 * 覆盖六件事：
 * 1. 列表渲染：版本号、发布时间、发布人；
 * 2. 有／无来源快照的区分；
 * 3. **空历史**必须显示空状态（不是一片空白）；
 * 4. 服务层报错必须显示出来（**不能静默**）；
 * 5. 修改摘要**不得编造**（后端返回时显示原摘要，未返回时显示"未记录"）；
 * 6. 只读：**不得**出现"恢复该版本为新草稿"之类的写操作。
 *
 * **为什么查询走 `document` 而不是 `wrapper.find`**：`NDrawer` 把内容
 * Teleport 到 `document.body`，`wrapper.find` 只看组件自己的根元素，
 * 在抽屉里**永远找不到东西**（我第一版就栽在这里：9 条里 8 条失败，
 * 报"Cannot call trigger on an empty DOMWrapper"）。
 */

const fetchVersionHistory = vi.fn()
const fetchVersionSourceSnapshot = vi.fn()
vi.mock('../../src/services/admin-curriculum-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-curriculum-api')>()
  return {
    ...actual,
    fetchVersionHistory: (...args: unknown[]) => fetchVersionHistory(...args),
    fetchVersionSourceSnapshot: (...args: unknown[]) => fetchVersionSourceSnapshot(...args)
  }
})

const { default: VersionHistoryDrawer } = await import('../../src/components/admin/VersionHistoryDrawer.vue')
const { AdminApiError } = await import('../../src/services/admin-api')

enableAutoUnmount(afterEach)

/** 一条历史版本；默认字段与后端 `VersionSummary` 实测响应一致。 */
function version(overrides: Record<string, unknown> = {}) {
  return {
    editionId: 'demo-book',
    contentVersion: '1.0.0',
    contentSha256: 'sha-100',
    byteLength: 1668,
    publishedBy: 'local-admin-1001',
    publishedAt: 1791307644000,
    hasSourceSnapshot: true,
    sourceSnapshotSha256: 'snap-100',
    ...overrides
  }
}

/**
 * 挂载抽屉。
 *
 * **必须 `attachTo: document.body`**：不挂到真实文档上时 Teleport 的目标不存在，
 * 抽屉内容根本不会被渲染出来。
 */
async function mountDrawer(editionId = 'demo-book') {
  const wrapper = mount(VersionHistoryDrawer, {
    props: { editionId, show: true },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

/** 在 Teleport 落地的真实文档里查一个节点。 */
function query(selector: string): HTMLElement | null {
  return document.querySelector(selector)
}

/** 在 Teleport 落地的真实文档里查全部节点。 */
function queryAll(selector: string): HTMLElement[] {
  return [...document.querySelectorAll(selector)] as HTMLElement[]
}

/** 抽屉内容整体文本。 */
function drawerText(): string {
  return query('[data-testid="version-drawer"]')?.textContent ?? ''
}

describe('版本记录抽屉（设计 3.6 第 166 行）', () => {
  beforeEach(() => {
    fetchVersionHistory.mockReset()
    fetchVersionSourceSnapshot.mockReset()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('列出历史版本：版本号、发布时间、发布人都在（时间倒序）', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '1.0.0', publishedAt: 1000, publishedBy: 'a-admin' }),
      version({ contentVersion: '1.0.2', publishedAt: 3000, publishedBy: 'c-admin' }),
      version({ contentVersion: '1.0.1', publishedAt: 2000, publishedBy: 'b-admin' })
    ])

    await mountDrawer()

    expect(fetchVersionHistory).toHaveBeenCalledWith('demo-book', expect.anything())
    const text = drawerText()
    // 判别力：三个版本号、三个发布人、以及换算出的时间都要出现。
    expect(text).toContain('1.0.0')
    expect(text).toContain('1.0.1')
    expect(text).toContain('1.0.2')
    expect(text).toContain('a-admin')
    expect(text).toContain('b-admin')
    expect(text).toContain('c-admin')
    expect(text).toContain('（3000）')
    // **倒序**：最新发布（1.0.2）必须排在 1.0.0 前面。
    expect(text.indexOf('1.0.2')).toBeLessThan(text.indexOf('1.0.1'))
    expect(text.indexOf('1.0.1')).toBeLessThan(text.indexOf('1.0.0'))
  })

  it('区分有／无来源快照：有快照才出现"查看来源快照"，无快照显示"无"', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '2.0.0', hasSourceSnapshot: true, sourceSnapshotSha256: 'snap-200' }),
      version({ contentVersion: '1.0.0', hasSourceSnapshot: false, sourceSnapshotSha256: null })
    ])

    await mountDrawer()

    expect(queryAll('[data-snapshot-toggle]').map(node => node.getAttribute('data-snapshot-toggle')))
      .toEqual(['2.0.0'])
    expect(drawerText()).toContain('snap-200')
    // 无快照那一版必须明确说"无"，而不是留空让人以为加载失败。
    const withoutSnapshot = query('[data-has-snapshot="false"]')
    expect(withoutSnapshot).not.toBeNull()
    expect(withoutSnapshot?.textContent?.trim()).toBe('无')
  })

  it('空历史显示空状态（不是一片空白，也不能显示成错误）', async () => {
    fetchVersionHistory.mockResolvedValue([])

    await mountDrawer()

    expect(query('[data-testid="version-empty"]')).not.toBeNull()
    expect(query('[data-testid="version-list"]')).toBeNull()
    expect(query('[data-testid="version-error"]')).toBeNull()
    expect(drawerText()).toContain('暂无版本记录')
  })

  it('服务层报错时必须显示错误原文（不能静默成空列表）', async () => {
    fetchVersionHistory.mockRejectedValue(
      new AdminApiError('TOOLBOX_ADMIN_006', 'admin.curriculum.versionMissing', undefined, 404)
    )

    await mountDrawer()

    const alert = query('[data-testid="version-error"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('读取版本记录失败')
    // 错误里必须带**可定位的原文**（错误码／状态码／消息键），否则用户只能重启页面。
    expect(alert?.textContent).toContain('TOOLBOX_ADMIN_006')
    expect(alert?.textContent).toContain('404')
    // 并且**不能**把失败画成"没有历史"——那是在撒谎。
    expect(query('[data-testid="version-empty"]')).toBeNull()
  })

  it('修改摘要后端未返回时显示"未记录"，不得编造', async () => {
    // 后端现在会返回 changeNote，但**历史版本可能没有**：
    // 发布时未填写、或本次改造之前发布的版本。
    fetchVersionHistory.mockResolvedValue([version({ contentVersion: '1.0.0' })])

    await mountDrawer()

    const cell = query('[data-change-note="1.0.0"]')
    expect(cell).not.toBeNull()
    expect(cell?.textContent).toBe('未记录')
    // 为什么要显示"未记录"也要说明白，不能让用户以为是自己没填。
    expect(drawerText()).toContain('发布时未填写')
  })

  it('后端返回 changeNote 时显示原摘要（界面不是写死"未记录"）', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '1.0.0', changeNote: '补充第三单元词语表' })
    ])

    await mountDrawer()

    expect(query('[data-change-note="1.0.0"]')?.textContent).toBe('补充第三单元词语表')
  })

  it('空串摘要按"未记录"处理：不把"填了空"画成一条摘要', async () => {
    // 空串是"字段在但没内容"，与 null 一样必须显示"未记录"。
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '1.0.0', changeNote: '   ' })
    ])

    await mountDrawer()

    expect(query('[data-change-note="1.0.0"]')?.textContent).toBe('未记录')
  })

  it('该版本原始字节：下载链接指向 /bytes 端点，且用浏览器同源下载', async () => {
    fetchVersionHistory.mockResolvedValue([version({ contentVersion: '3.1.4' })])

    await mountDrawer()

    const link = query('[data-download-version="3.1.4"]')
    expect(link).not.toBeNull()
    expect(link?.getAttribute('href'))
      .toBe('/admin/v1/curriculum/versions/demo-book/3.1.4/bytes')
    // 有 download 属性才是"下载"而不是"在当前标签页里导航走"。
    expect(link?.hasAttribute('download')).toBe(true)
  })

  it('点"查看来源快照"会拉取并展示该版本快照 JSON；失败时显示错误', async () => {
    fetchVersionHistory.mockResolvedValue([version({ contentVersion: '1.0.0', hasSourceSnapshot: true })])
    fetchVersionSourceSnapshot.mockResolvedValue({
      editionId: 'demo-book',
      contentVersion: '1.0.0',
      snapshotJson: '{"snapshotSchemaVersion":1}',
      sha256: 'snap-100'
    })

    await mountDrawer()
    query('[data-snapshot-toggle="1.0.0"]')?.click()
    await flushPromises()

    expect(fetchVersionSourceSnapshot).toHaveBeenCalledWith('demo-book', '1.0.0')
    const pre = query('[data-snapshot-json="1.0.0"]')
    expect(pre).not.toBeNull()
    // JSON 被格式化过，便于人读。
    expect(pre?.textContent).toContain('"snapshotSchemaVersion": 1')

    // 收起后再展开一次；这一次接口失败，**必须显示错误**：
    // 声称有快照却拉不到，是真实故障，不能静默。
    fetchVersionSourceSnapshot.mockRejectedValue(
      new AdminApiError('TOOLBOX_ADMIN_006', 'admin.curriculum.sourceSnapshotMissing',
        { reason: 'sourceSnapshotMissing' }, 404)
    )
    query('[data-snapshot-toggle="1.0.0"]')?.click()
    await flushPromises()
    query('[data-snapshot-toggle="1.0.0"]')?.click()
    await flushPromises()

    expect(drawerText()).toContain('读取来源快照失败')
    expect(drawerText()).toContain('sourceSnapshotMissing')
  })

  it('不提供恢复／回滚／下架／删除／覆盖类写操作（补录入口是唯一的写路径，另见 admin-source-snapshot-repair.test.ts）', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '1.0.0', hasSourceSnapshot: true }),
      version({ contentVersion: '1.0.1', hasSourceSnapshot: false, sourceSnapshotSha256: null })
    ])

    await mountDrawer()

    // 只查**可点的东西**（按钮与链接）：说明文字里出现"不提供恢复旧版、下架或回滚"
    // 是免责声明，不是写操作——第一版我拿整段文本做匹配，于是把自己的免责声明判成了违规。
    // 补录（唯一写路径）的入口文案不含这些词，它是否**只出现在无快照版本**由
    // `admin-source-snapshot-repair.test.ts` 单独验收。
    const controls = queryAll('[data-testid="version-list"] button, [data-testid="version-list"] a')
    const labels = controls.map(node => node.textContent?.trim() ?? '')
    expect(labels.length).toBeGreaterThan(0)
    for (const forbidden of ['恢复', '回滚', '下架', '删除', '覆盖']) {
      expect(labels.filter(label => label.includes(forbidden)),
        `抽屉里不得出现"${forbidden}"控件`).toEqual([])
    }
    // 反向说明：这两个版本确实渲染了控件（否则上面那条会假通过）。
    expect(labels).toContain('下载该版本原始字节')
  })
})
