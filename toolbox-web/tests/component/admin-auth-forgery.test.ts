import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

/**
 * A06：**伪造 Pinia 状态**。
 *
 * 这一条此前标"部分"，缺口原文是"无组件测试工具可在运行时伪造验证"。
 * 现在有了 jsdom + @vue/test-utils，可以真的在运行时改 store 状态，看 UI 会不会被它骗到。
 *
 * **要证明的命题**：管理入口的可见性**只能由服务端响应驱动**，
 * 本地把状态改成"我是管理员"**不会**让界面出现管理功能——
 * 因为 `initialize()` 每次都向服务端要真相，并**覆盖**本地状态。
 *
 * 这与仓库的一条既有约定一致：前端不做权限判断，只反映服务端结论。
 */

// 必须在使用 store 之前 mock：store 在模块加载时就 import 了这些函数。
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

const { useAdminAuthStore } = await import('../../src/stores/admin-auth')

/** 一个把 store 结论渲染成文本的最小组件——它代表"界面"。 */
const Probe = defineComponent({
  setup() {
    const auth = useAdminAuthStore()
    return () => h('span', {
      'data-admin': String(auth.isAdmin),
      'data-checking': String(auth.isChecking)
    })
  }
})

describe('A06 管理状态不可由前端伪造', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchAdminSession.mockReset()
  })

  it('服务端说未登录时，即使本地被改成管理员，界面也不显示管理功能', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: false, adminEnabled: true })
    const wrapper = mount(Probe)
    const auth = useAdminAuthStore()

    // 运行时**伪造**：直接把状态改成管理员。
    // 这正是此前无法验证的动作——没有组件测试工具就改不动运行中的 store。
    auth.status = 'admin'
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('data-admin')).toBe('true')

    // 再问一次服务端：本地伪造必须被服务端的结论覆盖。
    await auth.initialize(true)
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('data-admin')).toBe('false')
  })

  it('服务端说已登录时才显示管理员', async () => {
    fetchAdminSession.mockResolvedValue({ authenticated: true, adminEnabled: true, username: 'yuyutian' })
    const wrapper = mount(Probe)
    const auth = useAdminAuthStore()
    await auth.initialize(true)
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('data-admin')).toBe('true')
  })

  it('服务端说管理功能未启用时，本地伪造同样无效', async () => {
    // 另一种情形：服务端明确"功能没开"。
    // 若前端只看 authenticated，这里会错误地显示管理入口。
    fetchAdminSession.mockResolvedValue({ authenticated: false, adminEnabled: false })
    const wrapper = mount(Probe)
    const auth = useAdminAuthStore()
    auth.status = 'admin'
    await auth.initialize(true)
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('data-admin')).toBe('false')
  })
})
