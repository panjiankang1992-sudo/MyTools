import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import {
  AdminApiError,
  adminLogin as loginRequest,
  adminLogout as logoutRequest,
  clearAdminCsrfToken,
  fetchAdminCsrf,
  fetchAdminSession,
  setAdminCsrfToken
} from '../services/admin-api';

/**
 * 管理员会话状态机。
 *
 * 状态取值与设计文档 §4.4 一致：
 * `checking`（首次验证中，不闪现账号信息）／`anonymous`（未登录）／
 * `admin`（已登录管理员）／`expired`（登录过期，冻结写入但保留编辑缓冲）／
 * `unavailable`（身份依赖不可用，锁定写入但**不谎称已退出**）。
 *
 * 公开页面不受本状态影响：初始化失败也不会阻塞公共功能。
 */

/** 管理会话状态取值。 */
export type AdminAuthStatus = 'checking' | 'anonymous' | 'admin' | 'expired' | 'unavailable';

let channel: BroadcastChannel | null = null;

/** 通过 BroadcastChannel 同步"已退出"，不广播任何令牌或教材正文。 */
function broadcastLogout(): void {
  try {
    channel ??= openChannel();
    channel?.postMessage({ type: 'admin-logout' });
  } catch {
    // 浏览器不支持时静默降级：跨标签页同步是增强而非必需。
  }
}

/**
 * 打开跨标签页通道。
 *
 * `unref()` 是必要的：在 Node（测试运行器）里，未 unref 的 BroadcastChannel
 * **会阻止进程退出**，表现为测试跑完却一直不结束。通道只是增强功能，
 * 不应该让宿主进程为它保活。
 *
 * @returns 通道；环境不支持时为 null
 */
function openChannel(): BroadcastChannel | null {
  try {
    const created = new BroadcastChannel('toolbox-admin-session');
    (created as unknown as { unref?: () => void }).unref?.();
    return created;
  } catch {
    return null;
  }
}

export const useAdminAuthStore = defineStore('admin-auth', () => {
  const status = ref<AdminAuthStatus>('checking');
  const username = ref<string>('');
  const userId = ref<string>('');
  const adminEnabled = ref(false);
  const lastErrorKey = ref<string>('');
  /** 过期时保留的编辑缓冲由页面自行持有；store 只负责会话判定。 */
  let initialized = false;

  const isAdmin = computed(() => status.value === 'admin');
  const isChecking = computed(() => status.value === 'checking');
  /** 是否允许发起写请求。 */
  const canWrite = computed(() => status.value === 'admin');

  /**
   * 初始化会话状态；失败不阻塞公共页面。
   *
   * @param force 是否强制重新查询
   */
  async function initialize(force = false): Promise<void> {
    if (initialized && !force) return;
    if (status.value === 'checking' && initialized) return;
    status.value = 'checking';
    try {
      const session = await fetchAdminSession();
      adminEnabled.value = session.adminEnabled;
      if (session.authenticated) {
        status.value = 'admin';
        username.value = session.username ?? '';
        userId.value = session.userId ?? '';
        // F01：刷新或新开标签页后，模块内存里的 CSRF 已丢失。
        // 服务端会把**同一个**令牌还回来，这里恢复它——否则界面显示已登录，
        // 但任何写请求都会因缺 CSRF 而 403。
        // 只在服务端确实返回时设置：不要用空值把已有的令牌抹掉。
        if (session.csrfToken) {
          setAdminCsrfToken(session.csrfToken);
        }
      } else {
        status.value = 'anonymous';
        username.value = '';
        userId.value = '';
      }
    } catch (cause) {
      // 依赖不可用 ≠ 未登录：不能把服务端故障显示成"请登录"。
      if (cause instanceof AdminApiError && cause.isUnauthenticated) {
        status.value = 'expired';
      } else {
        status.value = 'unavailable';
      }
      lastErrorKey.value = cause instanceof AdminApiError ? cause.messageKey : 'admin.request.network';
    } finally {
      initialized = true;
    }
  }

  /**
   * 登录；成功后进入 admin 状态。
   *
   * @param account 用户名或邮箱
   * @param password 密码
   */
  async function login(account: string, password: string): Promise<void> {
    lastErrorKey.value = '';
    const { csrfToken } = await fetchAdminCsrf();
    try {
      const result = await loginRequest(account, password, csrfToken);
      setAdminCsrfToken(result.csrfToken);
      status.value = 'admin';
      username.value = result.username ?? '';
      userId.value = result.userId ?? '';
      adminEnabled.value = true;
    } catch (cause) {
      if (cause instanceof AdminApiError) {
        lastErrorKey.value = cause.messageKey;
        // 无管理员权限时不留任何本地会话痕迹。
        if (cause.isForbidden) {
          status.value = 'anonymous';
          clearAdminCsrfToken();
        }
      } else {
        lastErrorKey.value = 'request.failed';
      }
      throw cause;
    }
  }

  /** 退出登录；先清本地状态，再尽力通知服务端。 */
  async function logout(): Promise<void> {
    try {
      await logoutRequest();
    } catch {
      // 上游失败不能导致本地仍显示已登录。
    } finally {
      clearLocal();
      broadcastLogout();
    }
  }

  /** 清除全部本地管理状态。 */
  function clearLocal(): void {
    status.value = 'anonymous';
    username.value = '';
    userId.value = '';
    lastErrorKey.value = '';
    clearAdminCsrfToken();
  }

  /** 会话失效：冻结写入但保留页面上的编辑缓冲（由页面决定何时清理）。 */
  function markExpired(): void {
    if (status.value === 'admin') status.value = 'expired';
    clearAdminCsrfToken();
  }

  /** 标记依赖不可用。 */
  function markUnavailable(): void {
    status.value = 'unavailable';
  }

  /** 监听其他标签页的退出，避免跨标签页仍显示已登录。 */
  function listenCrossTab(): void {
    try {
      channel = openChannel();
      if (channel) channel.onmessage = event => {
        if (event?.data?.type === 'admin-logout') clearLocal();
      };
    } catch {
      // 不支持时忽略。
    }
  }

  return {
    status,
    username,
    userId,
    adminEnabled,
    lastErrorKey,
    isAdmin,
    isChecking,
    canWrite,
    initialize,
    login,
    logout,
    clearLocal,
    markExpired,
    markUnavailable,
    listenCrossTab
  };
});
