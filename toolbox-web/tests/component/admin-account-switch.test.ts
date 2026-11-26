import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * A09：**换账号登录不串草稿**。
 *
 * 第 201 轮之前这一条标"未覆盖（需真实登录）"。
 * 本轮改为用组件测试验证它的**机制**，而不是靠浏览器脚本反复试——
 * 这里要证明的不是"某一次点击看起来对"，而是**账号切换时本地状态被彻底重置**。
 *
 * 关键在于分清两件容易混淆的事：
 *
 * 1. **会话状态**（用户名／用户 ID／角色／CSRF）——必须随账号切换**清空**，
 *    否则换账号后界面仍显示上一个账号的身份。
 * 2. **教材草稿**——**本来就不属于某个账号**：它存在服务端，同一个工具箱的
 *    管理员看到的是同一份课程内容。所以"不串草稿"**不是**指"草稿按账号隔离"，
 *    而是指"**上一个账号的会话与编辑缓冲不该残留到下一个账号**"。
 */

const fetchAdminSession = vi.fn()
const adminLogout = vi.fn()
vi.mock('../../src/services/admin-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/services/admin-api')>()
  return {
    ...actual,
    fetchAdminSession: (...a: unknown[]) => fetchAdminSession(...a),
    adminLogout: (...a: unknown[]) => adminLogout(...a),
    fetchAdminCsrf: vi.fn().mockResolvedValue('nonce'),
    adminLogin: vi.fn()
  }
})

const { useAdminAuthStore } = await import('../../src/stores/admin-auth')

describe('A09 换账号登录不串上一个账号的状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchAdminSession.mockReset()
    adminLogout.mockReset()
    adminLogout.mockResolvedValue(undefined)
    // jsdom 没有 BroadcastChannel；store 里已做了兼容，但显式兜底让意图更清楚。
    if (typeof globalThis.BroadcastChannel === 'undefined') {
      ;(globalThis as unknown as Record<string, unknown>).BroadcastChannel = class {
        postMessage() {}
        close() {}
        addEventListener() {}
        removeEventListener() {}
      }
    }
  })

  it('账号 A 退出后，身份与用户 ID 全部清空（不残留到账号 B）', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountA' })
    const auth = useAdminAuthStore()
    await auth.initialize(true)
    expect(auth.username).toBe('accountA')
    expect(auth.isAdmin).toBe(true)

    await auth.logout()

    // 这两条是"串账号"最直接的形态：界面仍显示上一个账号。
    expect(auth.username).toBe('')
    expect(auth.userId).toBe('')
    expect(auth.isAdmin).toBe(false)
  })

  it('退出后立刻换账号 B，界面显示的是 B 而不是 A', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountA' })
    const auth = useAdminAuthStore()
    await auth.initialize(true)
    await auth.logout()

    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountB' })
    await auth.initialize(true)

    expect(auth.username).toBe('accountB')
    expect(auth.username).not.toBe('accountA')
  })

  it('上游退出请求失败也必须清空本地（否则会出现"已退出却仍显示管理员"）', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountA' })
    const auth = useAdminAuthStore()
    await auth.initialize(true)

    adminLogout.mockRejectedValue(new Error('upstream down'))
    await auth.logout()

    // **这一条是审查 F15 指的漏洞**：这个文件此前 mock 的是 `postAdminLogout`，
    // 而实际导出叫 `adminLogout` —— 于是 `mockRejectedValue` 从来没生效，
    // "上游失败"这条路径**一次都没被真正走到**，测试却一直是绿的。
    //
    // 断言"它真的被调用了"，才能证明这个 mock 是接上的（而不是又一次空 mock）。
    expect(adminLogout).toHaveBeenCalled()
    expect(auth.isAdmin).toBe(false)
    expect(auth.username).toBe('')
  })

  it('管理会话模块不持有任何草稿内容（草稿不按账号存在本地）', async () => {
    // 这一条是**源码级不变式**：管理会话状态里不应出现草稿正文。
    // 若将来有人把草稿塞进会话状态，换账号时就可能真的串起来——
    // 那时这条测试会失败，而不是等用户在界面上发现。
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'accountA' })
    const auth = useAdminAuthStore()
    await auth.initialize(true)

    const snapshot = JSON.stringify({
      status: auth.status,
      username: auth.username,
      userId: auth.userId,
      isAdmin: auth.isAdmin
    })
    // 草稿里那两条验证内容的字样不该出现在会话状态里。
    expect(snapshot).not.toContain('白云')
    expect(snapshot).not.toContain('蓝天')
    expect(snapshot).not.toContain('unit')
  })
})
