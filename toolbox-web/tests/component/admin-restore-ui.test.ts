import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { NCheckbox } from 'naive-ui'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * D02：**恢复入口 UI**的组件验收。
 *
 * 后端（`POST /admin/v1/curriculum/restore-package`）已就绪并有往返测试；
 * 缺的是**人能用、且不会误伤的入口**。这里挂载真实 `CurriculumRestoreView.vue`，
 * 只把服务层换掉——store／组件／预检逻辑都跑真身。
 *
 * 三条硬要求各自对应下面的一条测试：
 * 1. 摘要（册数／版本数／冲突）必须在确认**之前**展示；
 * 2. **确认之后**才允许调用恢复接口（未勾选 → 一次都不许发）；
 * 3. `conflicts` 必须显示出来，不能被静默跳过，也不能只显示"恢复完成"。
 */

const fetchExportManifest = vi.fn()
const restorePackage = vi.fn()
vi.mock('../../src/services/admin-curriculum-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-curriculum-api')>()
  return {
    ...actual,
    fetchExportManifest: (...args: unknown[]) => fetchExportManifest(...args),
    restorePackage: (...args: unknown[]) => restorePackage(...args)
  }
})

const { default: CurriculumRestoreView } = await import('../../src/views/admin/CurriculumRestoreView.vue')

enableAutoUnmount(afterEach)

/** 用真实字节算出 Base64 与长度，避免手写错一个字符就让预检判定失真。 */
function contentOf(text: string): { contentBase64: string; byteLength: number; contentSha256: string } {
  const bytes = Buffer.from(text, 'utf8')
  return {
    contentBase64: bytes.toString('base64'),
    byteLength: bytes.length,
    contentSha256: `sha-${text}`
  }
}

function versionEntry(editionId: string, contentVersion: string, body: string, current = false) {
  return {
    editionId,
    contentVersion,
    current,
    editionMetadataJson: '{}',
    sourceMetadataJson: null,
    sourceSnapshotSha256: null,
    ...contentOf(body)
  }
}

const APPLE = contentOf('apple')

/** 一份三版本、两册的导出包。 */
function samplePackage() {
  return {
    format: 'toolbox-curriculum-package',
    formatVersion: 1,
    exportedBy: 'yuyutian',
    exportedAt: '2026-10-07T01:00:00Z',
    versionCount: 3,
    packageSha256: 'package-sha',
    versions: [
      versionEntry('book-a', '1.0.0', 'apple', true),
      versionEntry('book-a', '1.0.1', 'banana'),
      versionEntry('book-b', '2.0.0', 'cherry', true)
    ]
  }
}

/** 库内现状：book-a 已有 1.0.0（同哈希），1.0.1 却是**另一个哈希**（冲突）。 */
function manifestWithConflict() {
  return {
    format: 'toolbox-curriculum-export',
    formatVersion: 1,
    exportedBy: 'yuyutian',
    exportedAt: '2026-10-07T00:00:00Z',
    editions: [
      {
        editionId: 'book-a',
        currentVersion: '1.0.0',
        currentContentSha256: APPLE.contentSha256,
        history: [
          { contentVersion: '1.0.0', contentSha256: APPLE.contentSha256, current: true },
          // 同一版本、不同内容：服务端会报 CONFLICT_HASH_MISMATCH，不会覆盖。
          { contentVersion: '1.0.1', contentSha256: 'sha-something-else', current: false }
        ]
      }
    ]
  }
}

/** 按文字找按钮；比依赖 class 名稳定，也与本目录既有测试一致。 */
function buttonByText(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').find(item => item.text().trim() === label)
  expect(found, `必须能找到「${label}」按钮`).toBeTruthy()
  return found!
}

/** 粘贴导出包并点"解析并预览"。 */
async function pasteAndAnalyze(wrapper: ReturnType<typeof mount>, pkg: unknown): Promise<void> {
  await wrapper.find('textarea').setValue(JSON.stringify(pkg))
  await buttonByText(wrapper, '解析并预览').trigger('click')
  await flushPromises()
}

/** 勾选二次确认（真实 NCheckbox 的 v-model:checked）。 */
async function confirmCheckbox(wrapper: ReturnType<typeof mount>): Promise<void> {
  wrapper.findComponent(NCheckbox).vm.$emit('update:checked', true)
  await flushPromises()
}

describe('恢复入口 UI（D02）', () => {
  beforeEach(() => {
    fetchExportManifest.mockReset()
    fetchExportManifest.mockResolvedValue(manifestWithConflict())
    restorePackage.mockReset()
  })

  it('先展示摘要：册数／版本数／冲突都在点确认之前就能看到', async () => {
    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, samplePackage())

    expect(wrapper.find('[data-testid="restore-preview"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preview-editions"]').text()).toBe('2')
    expect(wrapper.find('[data-testid="preview-versions"]').text()).toBe('3')
    // 库内 book-a@1.0.1 内容不同 → 预计冲突 1 条。
    expect(wrapper.find('[data-testid="preview-conflicts"]').text()).toBe('1')
    // 冲突必须在摘要阶段就写清楚"不会被覆盖"。
    const conflictList = wrapper.find('[data-testid="preview-conflict-list"]')
    expect(conflictList.exists()).toBe(true)
    expect(conflictList.text()).toContain('book-a@1.0.1')
    expect(conflictList.text()).toContain('不会被覆盖')
    // 同哈希的那一版要判成"将跳过"，而不是也报冲突。
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(3)
    expect(wrapper.text()).toContain('将跳过')

    // **摘要阶段一次请求都不许发**。
    expect(restorePackage).not.toHaveBeenCalled()
  })

  it('未经二次确认不得调用恢复接口；勾选后才调用，且只调用一次', async () => {
    const pkg = samplePackage()
    restorePackage.mockResolvedValue({
      restoredEditions: 2, restoredVersions: 2, skippedVersions: 1,
      conflicts: [], reconciliations: [], ok: true
    })

    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, pkg)
    expect(restorePackage).not.toHaveBeenCalled()

    // 未勾选时点"确认恢复"：界面按钮是 disabled 的，即使点击事件抵达处理器，
    // 处理器自己也要挡住——闸门必须写在会发请求的地方。
    await buttonByText(wrapper, '确认恢复').trigger('click')
    await flushPromises()
    expect(restorePackage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('需先勾选上面的确认项')

    await confirmCheckbox(wrapper)
    await buttonByText(wrapper, '确认恢复').trigger('click')
    await flushPromises()

    expect(restorePackage).toHaveBeenCalledTimes(1)
    // 发的必须是**解析后的包对象**（不是原始文本）。
    expect(restorePackage.mock.calls[0][0]).toMatchObject({
      format: 'toolbox-curriculum-package',
      versions: expect.any(Array)
    })
    // 结果区真的渲染了服务端返回的数字。
    expect(wrapper.find('[data-testid="report-restored"]').text()).toBe('2')
    expect(wrapper.find('[data-testid="report-skipped"]').text()).toBe('1')
  })

  it('服务端报的冲突必须逐条显示（不是"恢复完成"了事）', async () => {
    restorePackage.mockResolvedValue({
      restoredEditions: 1,
      restoredVersions: 1,
      skippedVersions: 0,
      conflicts: ['book-a@1.0.1:CONFLICT_HASH_MISMATCH'],
      reconciliations: [{
        editionId: 'book-a',
        currentVersionId: 'book-a@1.0.0',
        expectedCurrentVersion: '1.0.0',
        currentPointerRestored: true,
        historyCount: 2,
        catalogVersionBaseline: '2026-10-04.1',
        contentSha256Matches: true,
        sourceSnapshotMatches: true
      }],
      ok: false
    })

    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, samplePackage())
    await confirmCheckbox(wrapper)
    await buttonByText(wrapper, '确认恢复').trigger('click')
    await flushPromises()

    const conflicts = wrapper.find('[data-testid="report-conflict-list"]')
    expect(conflicts.exists()).toBe(true)
    expect(conflicts.text()).toContain('book-a@1.0.1:CONFLICT_HASH_MISMATCH')
    expect(conflicts.text()).toContain('没有被写入')
    expect(wrapper.find('[data-testid="report-conflicts"]').text()).toBe('1')
    // 反向：有冲突时**不得**显示"没有冲突"。
    expect(wrapper.text()).not.toContain('服务端没有报告冲突')
  })

  it('逐册对账：一致与不一致都要标出来', async () => {
    restorePackage.mockResolvedValue({
      restoredEditions: 2,
      restoredVersions: 2,
      skippedVersions: 0,
      conflicts: [],
      reconciliations: [
        {
          editionId: 'book-a',
          currentVersionId: 'book-a@1.0.1',
          expectedCurrentVersion: '1.0.1',
          currentPointerRestored: true,
          historyCount: 2,
          catalogVersionBaseline: '2026-10-04.1',
          contentSha256Matches: true,
          sourceSnapshotMatches: true
        },
        {
          editionId: 'book-b',
          currentVersionId: 'book-b@2.0.0',
          expectedCurrentVersion: '2.0.1',
          // 指针没摆回、来源快照对不上：必须显式报"不一致"。
          currentPointerRestored: false,
          historyCount: 1,
          catalogVersionBaseline: '',
          contentSha256Matches: true,
          sourceSnapshotMatches: false
        }
      ],
      ok: true
    })

    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, samplePackage())
    await confirmCheckbox(wrapper)
    await buttonByText(wrapper, '确认恢复').trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('[data-testid="restore-report"] tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('book-a')
    expect(rows[0].text()).not.toContain('不一致')
    expect(rows[1].text()).toContain('book-b')
    // 两项不一致都要出现（指针 + 来源快照）。
    expect(rows[1].text().match(/不一致/g)?.length).toBe(2)
    expect(rows[1].text()).toContain('book-b@2.0.0')
  })

  it('读不到库内现状时不谎称无冲突：摘要照给，并说明比对范围只到包内', async () => {
    fetchExportManifest.mockRejectedValue(new Error('network down'))

    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, samplePackage())

    // 摘要仍然可用（包内自查）。
    expect(wrapper.find('[data-testid="preview-versions"]').text()).toBe('3')
    expect(wrapper.find('[data-testid="preview-conflicts"]').text()).toBe('0')
    // **但必须如实说明**库内比对没做成。
    const note = wrapper.find('[data-testid="preview-manifest-note"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('只覆盖包内自查')
    expect(note.text()).toContain('库内冲突会在恢复结果里逐条列出')
  })

  it('粘贴的不是导出包时明确报错，且不发任何请求', async () => {
    const wrapper = mount(CurriculumRestoreView)
    await wrapper.find('textarea').setValue('{"hello":"world"}')
    await buttonByText(wrapper, '解析并预览').trigger('click')
    await flushPromises()

    const error = wrapper.find('[data-testid="restore-parse-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('toolbox-curriculum-package')
    expect(wrapper.find('[data-testid="restore-preview"]').exists()).toBe(false)
    expect(restorePackage).not.toHaveBeenCalled()
    expect(fetchExportManifest).not.toHaveBeenCalled()
  })

  it('包内字节长度与声明不符时判为冲突（拦住粘错/被截断的包）', async () => {
    // 空库：让"长度不符"成为**唯一**的冲突来源，否则这条断言分不清是哪一项触发的。
    fetchExportManifest.mockResolvedValue({ format: 'toolbox-curriculum-export', formatVersion: 1, exportedBy: 'x', exportedAt: '', editions: [] })
    const pkg = samplePackage()
    pkg.versions[2].byteLength = 999

    const wrapper = mount(CurriculumRestoreView)
    await pasteAndAnalyze(wrapper, pkg)

    expect(wrapper.find('[data-testid="preview-conflicts"]').text()).toBe('1')
    expect(wrapper.text()).toContain('正文长度与声明不符')
  })
})
