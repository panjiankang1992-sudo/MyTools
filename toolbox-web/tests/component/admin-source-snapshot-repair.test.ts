import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { NButton, NCheckbox } from 'naive-ui'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 来源快照**补录入口**的组件验收（设计第 297 行：来源元数据随不可变版本保存，事后不可修改）。
 *
 * 后端 `POST /admin/v1/curriculum/versions/{editionId}/{contentVersion}/repair-source-snapshot?basis=`
 * 已就绪（`SourceSnapshotRepairService`），缺的是人能用、且不会误伤的入口。
 *
 * 这里挂载**真实** `VersionHistoryDrawer.vue`，只把服务层换掉；
 * 组件的加载／排序／表单状态／错误处理都跑真身。
 *
 * 覆盖五条硬要求：
 * 1. 有快照的版本**不出现**补录入口（不可事后修改）；
 * 2. 依据必填，且**闸门写在会发请求的函数里**——按钮 `disabled` 只是提示，
 *    这里用"绕过 disabled 直接触发组件 click 事件"来证明；
 * 3. 二次确认：未勾选确认时同样一次都不许发；
 * 4. 提交后展示 `applied` / `overwroteOriginal` / `reason`，并**刷新历史**；
 * 5. `overwroteOriginal: true` 必须显示为**异常**而不是成功（本界面不提供覆盖路径）；
 *    服务层报错必须显示原文，不能静默。
 *
 * 查询走 `document`：`NDrawer` 把内容 Teleport 到 `document.body`，
 * `wrapper.find` 只看组件自己的根元素，在抽屉里永远找不到东西（与既有抽屉测试同一坑）。
 */

const fetchVersionHistory = vi.fn()
const fetchVersionSourceSnapshot = vi.fn()
const repairVersionSourceSnapshot = vi.fn()
vi.mock('../../src/services/admin-curriculum-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-curriculum-api')>()
  return {
    ...actual,
    fetchVersionHistory: (...args: unknown[]) => fetchVersionHistory(...args),
    fetchVersionSourceSnapshot: (...args: unknown[]) => fetchVersionSourceSnapshot(...args),
    repairVersionSourceSnapshot: (...args: unknown[]) => repairVersionSourceSnapshot(...args)
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

/** 一条**没有**来源快照的版本。 */
function versionWithoutSnapshot(contentVersion = '1.0.0', extra: Record<string, unknown> = {}) {
  return version({
    contentVersion,
    hasSourceSnapshot: false,
    sourceSnapshotSha256: null,
    ...extra
  })
}

/** 一份补录成功的后端响应。 */
function repairedResult(overrides: Record<string, unknown> = {}) {
  return {
    editionId: 'demo-book',
    contentVersion: '1.0.0',
    applied: true,
    overwroteOriginal: false,
    reason: 'repaired',
    snapshot: { json: '{"snapshot":{"origin":"repair"}}', sha256: 'repaired-sha' },
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

/** 点开某版本的补录表单。 */
async function openRepair(contentVersion: string): Promise<void> {
  const trigger = query(`[data-repair-open="${contentVersion}"]`)
  expect(trigger, `版本 ${contentVersion} 必须出现"补录来源快照"入口`).not.toBeNull()
  trigger?.click()
  await flushPromises()
}

/**
 * 在真实输入框里填写补录依据。
 *
 * `[data-testid="repair-basis"]` 是包住 `NInput` 的容器，这里找它**内部的真实 input**，
 * 直接派发 `input` 事件，走 NInput 自己的 v-model，而不是去猜组件内部结构。
 */
async function setBasis(text: string): Promise<void> {
  const input = query('[data-testid="repair-basis"] input') as HTMLInputElement | null
  expect(input, '必须能找到补录依据输入框').not.toBeNull()
  if (!input) return
  input.value = text
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

/** 勾选／取消二次确认（真实 NCheckbox 的 `v-model:checked`）。 */
async function setConfirmed(wrapper: ReturnType<typeof mount>, checked: boolean): Promise<void> {
  const boxes = wrapper.findAllComponents(NCheckbox)
  expect(boxes.length, '抽屉里应当只有一个二次确认复选框').toBe(1)
  boxes[0].vm.$emit('update:checked', checked)
  await flushPromises()
}

/** "确认补录"按钮的**组件**包装（不是 DOM 元素）。 */
function repairSubmitButton(wrapper: ReturnType<typeof mount>) {
  const found = wrapper.findAllComponents(NButton)
    .find(button => button.attributes('data-testid') === 'repair-submit')
  expect(found, '必须能找到「确认补录」按钮').toBeTruthy()
  return found!
}

/**
 * **绕过 `disabled` 直接触发**补录按钮的 click 事件。
 *
 * `NButton` 的原生 `disabled` 会挡住真实点击，但组件实例上的 `click` 事件
 * 仍然直达父组件的 `@click` 处理器。用它来验证"闸门写在会发请求的函数里"，
 * 而不是写在按钮的渲染属性上。
 *
 * `expectDisabled` 断言按钮当前的禁用状态：被闸门拦住的用例必须传 true
 * （否则这条测试没有证明任何东西——按钮本来就可点），正向对照传 false。
 *
 * @param wrapper        抽屉包装
 * @param expectDisabled 触发前按钮是否应当是禁用的
 */
function emitSubmitClick(wrapper: ReturnType<typeof mount>, expectDisabled: boolean): void {
  const button = repairSubmitButton(wrapper)
  expect((button.element as HTMLButtonElement).disabled,
    `「确认补录」按钮的 disabled 状态应为 ${expectDisabled}`).toBe(expectDisabled)
  button.vm.$emit('click')
}

describe('版本记录抽屉：来源快照补录入口', () => {
  beforeEach(() => {
    fetchVersionHistory.mockReset()
    fetchVersionSourceSnapshot.mockReset()
    repairVersionSourceSnapshot.mockReset()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('有来源快照的版本不出现补录入口（来源元数据事后不可修改）', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '2.0.0', hasSourceSnapshot: true, sourceSnapshotSha256: 'snap-200' }),
      versionWithoutSnapshot('1.0.0')
    ])

    await mountDrawer()

    // 补录入口**只**挂在没有快照的那一版上。
    expect(queryAll('[data-repair-open]').map(node => node.getAttribute('data-repair-open')))
      .toEqual(['1.0.0'])
    // 有快照的版本连补录表单都不该能打开。
    expect(query('[data-version="2.0.0"] [data-repair-open]')).toBeNull()
    expect(query('[data-repair-form="2.0.0"]')).toBeNull()
    // 反向说明：无快照那一版确实渲染了入口（否则上面那条会假通过）。
    expect(query('[data-version="1.0.0"] [data-repair-open]')?.textContent?.trim())
      .toBe('补录来源快照')
  })

  it('空依据：绕过 disabled 直接触发也不发请求，并显示必填提示', async () => {
    fetchVersionHistory.mockResolvedValue([versionWithoutSnapshot('1.0.0')])

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    // 先勾二次确认，隔离出"依据"这一道闸门。
    await setConfirmed(wrapper, true)

    // 按钮确实处于 disabled；直接触发组件 click 绕过它。
    emitSubmitClick(wrapper, true)
    await flushPromises()

    expect(repairVersionSourceSnapshot).not.toHaveBeenCalled()
    expect(query('[data-testid="repair-basis-error"]')?.textContent).toContain('补录依据必填')

    // 纯空白依据同样不算填了。
    await setBasis('   ')
    emitSubmitClick(wrapper, true)
    await flushPromises()
    expect(repairVersionSourceSnapshot).not.toHaveBeenCalled()

    // 正向对照：填写依据后，同一条路径**必须**能把请求发出去——
    // 否则上面两条"没发请求"可能只是按钮坏了，而不是闸门在挡。
    await setBasis('sources/cn-1-first-2024.json')
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult())
    fetchVersionHistory.mockResolvedValue([version({ contentVersion: '1.0.0' })])
    emitSubmitClick(wrapper, false)
    await flushPromises()
    expect(repairVersionSourceSnapshot).toHaveBeenCalledTimes(1)
  })

  it('未勾选二次确认：绕过 disabled 直接触发也不发请求', async () => {
    fetchVersionHistory.mockResolvedValue([versionWithoutSnapshot('1.0.0')])
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult())

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    await setBasis('sources/cn-1-first-2024.json')

    emitSubmitClick(wrapper, true)
    await flushPromises()

    expect(repairVersionSourceSnapshot).not.toHaveBeenCalled()
    expect(query('[data-testid="repair-error"]')?.textContent).toContain('二次确认')
  })

  it('提交后展示 applied / overwroteOriginal / reason，并刷新历史（入口随之消失）', async () => {
    fetchVersionHistory
      .mockResolvedValueOnce([versionWithoutSnapshot('1.0.0')])
      // 补录成功后后端返回的历史里，该版本已"有快照"。
      .mockResolvedValueOnce([version({ contentVersion: '1.0.0', sourceSnapshotSha256: 'repaired-sha' })])
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult())

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    // 首尾带空白，验证发出去的依据是 trim 过的。
    await setBasis('  sources/cn-1-first-2024.json  ')
    await setConfirmed(wrapper, true)

    emitSubmitClick(wrapper, false)
    await flushPromises()

    expect(repairVersionSourceSnapshot).toHaveBeenCalledWith(
      'demo-book', '1.0.0', 'sources/cn-1-first-2024.json'
    )
    expect(query('[data-testid="repair-applied"]')?.textContent?.trim()).toBe('true')
    expect(query('[data-testid="repair-overwrote"]')?.textContent?.trim()).toBe('false')
    expect(query('[data-testid="repair-reason"]')?.textContent).toContain('repaired')
    expect(query('[data-testid="repair-success"]')).not.toBeNull()
    // **刷新了该版本的历史项**：第二次拉取，且刷新后补录入口消失、换成"查看来源快照"。
    expect(fetchVersionHistory).toHaveBeenCalledTimes(2)
    expect(queryAll('[data-repair-open]')).toEqual([])
    expect(query('[data-snapshot-toggle="1.0.0"]')).not.toBeNull()
  })

  it('后端返回 overwroteOriginal:true 时必须显示为异常，不得当成成功', async () => {
    fetchVersionHistory.mockResolvedValue([versionWithoutSnapshot('1.0.0')])
    // 后端契约里它恒为 false；这里模拟契约被破坏，界面必须报警而不是庆祝。
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult({ overwroteOriginal: true }))

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    await setBasis('sources/cn-1-first-2024.json')
    await setConfirmed(wrapper, true)

    emitSubmitClick(wrapper, false)
    await flushPromises()

    expect(query('[data-testid="repair-overwrite-anomaly"]')).not.toBeNull()
    expect(query('[data-testid="repair-overwrite-anomaly"]')?.textContent).toContain('异常')
    // 明确不能同时出现"成功"或"未应用"的结论——那两种都会掩盖覆盖事故。
    expect(query('[data-testid="repair-success"]')).toBeNull()
    expect(query('[data-testid="repair-not-applied"]')).toBeNull()
    // 原始返回值照实展示，便于人工核查。
    expect(query('[data-testid="repair-overwrote"]')?.textContent?.trim()).toBe('true')
  })

  it('applied:false（snapshotAlreadyPresent）显示为"未应用"，不是成功', async () => {
    fetchVersionHistory.mockResolvedValue([versionWithoutSnapshot('1.0.0')])
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult({
      applied: false,
      reason: 'snapshotAlreadyPresent',
      snapshot: { json: '{}', sha256: 'existing-sha' }
    }))

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    await setBasis('sources/cn-1-first-2024.json')
    await setConfirmed(wrapper, true)

    emitSubmitClick(wrapper, false)
    await flushPromises()

    expect(query('[data-testid="repair-not-applied"]')).not.toBeNull()
    expect(query('[data-testid="repair-success"]')).toBeNull()
    expect(query('[data-testid="repair-applied"]')?.textContent?.trim()).toBe('false')
    expect(query('[data-testid="repair-reason"]')?.textContent).toContain('snapshotAlreadyPresent')
  })

  it('服务层报错时显示错误原文（不能静默），并保留表单便于重试', async () => {
    fetchVersionHistory.mockResolvedValue([versionWithoutSnapshot('1.0.0')])
    repairVersionSourceSnapshot.mockRejectedValue(
      new AdminApiError('TOOLBOX_ADMIN_005', 'admin.curriculum.repairBasisRequired',
        { reason: 'repairBasisRequired' }, 422)
    )

    const wrapper = await mountDrawer()
    await openRepair('1.0.0')
    await setBasis('sources/cn-1-first-2024.json')
    await setConfirmed(wrapper, true)

    emitSubmitClick(wrapper, false)
    await flushPromises()

    const alert = query('[data-testid="repair-error"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('补录失败')
    // 错误里必须带**可定位的原文**（错误码／状态码）。
    expect(alert?.textContent).toContain('TOOLBOX_ADMIN_005')
    expect(alert?.textContent).toContain('422')
    // 失败不能画成"没有结果"或"补录成功"。
    expect(query('[data-testid="repair-result"]')).toBeNull()
    expect(query('[data-testid="repair-success"]')).toBeNull()
    expect(query('[data-repair-form="1.0.0"]')).not.toBeNull()
  })

  it('有快照的版本：即使程序化调用补录函数也被拒绝（只补空，不发请求）', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '2.0.0', hasSourceSnapshot: true, sourceSnapshotSha256: 'snap-200' })
    ])
    repairVersionSourceSnapshot.mockResolvedValue(repairedResult({ contentVersion: '2.0.0' }))

    const wrapper = await mountDrawer()
    // 界面不为这一版渲染入口，因此只能直接调用函数来证明闸门在函数里。
    const vm = wrapper.vm as unknown as {
      submitRepair: (target: Record<string, unknown>) => Promise<void>
    }
    await vm.submitRepair(version({ contentVersion: '2.0.0', hasSourceSnapshot: true }))
    await flushPromises()

    expect(repairVersionSourceSnapshot).not.toHaveBeenCalled()
    expect(query('[data-testid="repair-error"]')?.textContent).toContain('已有来源快照')
  })

  it('不提供覆盖路径：抽屉里的可交互控件没有"覆盖／恢复／回滚／下架／删除"', async () => {
    fetchVersionHistory.mockResolvedValue([
      version({ contentVersion: '2.0.0', hasSourceSnapshot: true, sourceSnapshotSha256: 'snap-200' }),
      versionWithoutSnapshot('1.0.0')
    ])

    await mountDrawer()

    // 反向说明：补录入口确实渲染了控件（先断言，再打开表单——打开后文案会变成"收起补录"）。
    const entryLabels = queryAll('[data-testid="version-list"] button')
      .map(node => node.textContent?.trim() ?? '')
    expect(entryLabels).toContain('补录来源快照')

    await openRepair('1.0.0')

    const controls = queryAll('[data-testid="version-list"] button, [data-testid="version-list"] a')
    const labels = controls.map(node => node.textContent?.trim() ?? '')
    for (const forbidden of ['覆盖', '恢复', '回滚', '下架', '删除']) {
      expect(labels.filter(label => label.includes(forbidden)),
        `补录界面不得出现"${forbidden}"控件`).toEqual([])
    }
    // 打开后的表单控件也在：确认补录／取消（两者都不含被禁词）。
    expect(labels).toContain('确认补录')
    expect(labels).toContain('取消')
    // 说明文字可以提醒"不覆盖"，但**不能有**任何真的能覆盖的按钮：
    // 有快照的版本一个补录入口都没有。
    expect(query('[data-version="2.0.0"] [data-repair-open]')).toBeNull()
    expect(drawerText()).toContain('不覆盖既有快照')
  })
})
