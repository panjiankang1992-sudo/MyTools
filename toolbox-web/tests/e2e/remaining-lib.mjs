/**
 * 「剩余缺口」端到端验收的公共工具（B03／B09／A09）。
 *
 * 约定（与本仓库既有 e2e 脚本一致）：
 * - **只允许回环目标**：由 `target-guard.mjs` 的 `requireLoopbackTarget` 把关；
 * - 浏览器 profile 一律放系统临时目录（`cdp.mjs` 的 `createProfileDir()`）；
 * - 管理接口调用**一律经过前端同源代理**，这样 `Origin` 与浏览器一致，
 *   后端 `CorsAndOriginFilter` 的同源校验也走的是真实路径；
 * - 断言结果同时写一份 JSON 到证据目录，便于复跑对账。
 */

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sleep } from './cdp.mjs';

/** 仓库根目录。 */
export const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 证据输出目录（可用 E2E_EVIDENCE_DIR 覆盖）。 */
export const evidenceDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07/evidence/browser');

/** 每个脚本一个超时上限：不许无限挂住。 */
export const SCRIPT_TIMEOUT_MS = Number(process.env.E2E_SCRIPT_TIMEOUT_MS ?? 240000);

/** 到点强制退出（并打印原因），避免"跑挂了却一直占着终端"。 */
export function armWatchdog(label) {
  const timer = setTimeout(() => {
    console.error(`\n[看门狗] ${label} 超过 ${SCRIPT_TIMEOUT_MS} ms 仍未结束，强制退出（退出码 3）。`);
    process.exit(3);
  }, SCRIPT_TIMEOUT_MS);
  timer.unref?.();
  return () => clearTimeout(timer);
}

/** 断言/证据收集器。 */
export function createHarness(name) {
  const results = [];
  const check = (label, ok, detail = '') => {
    results.push({ label, ok: Boolean(ok), detail: String(detail) });
    console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` —— ${detail}` : ''}`);
  };
  /** 记录"实测到的事实"，不参与成败判定。 */
  const info = (label, detail) => {
    results.push({ label, ok: true, informational: true, detail: String(detail) });
    console.log(`INFO ${label} —— ${detail}`);
  };
  const failed = () => results.filter(item => !item.ok && !item.informational);
  const write = async (extra = {}) => {
    await mkdir(evidenceDir, { recursive: true });
    const payload = { name, at: new Date().toISOString(), results, ...extra };
    await writeFile(join(evidenceDir, `${name}.json`), `${JSON.stringify(payload, null, 2)}\n`);
    return payload;
  };
  return { name, results, check, info, failed, write };
}

/** 直接打前端的同源管理接口；`Origin` 用同一个前端源，与浏览器请求一致。 */
export async function api(base, path, options = {}) {
  const { method = 'GET', body, cookie, csrf, idempotencyKey, headers = {} } = options;
  const finalHeaders = { Accept: 'application/json', Origin: base, ...headers };
  if (body !== undefined) finalHeaders['Content-Type'] = 'application/json';
  if (cookie) finalHeaders.Cookie = cookie;
  if (csrf) finalHeaders['X-Toolbox-Admin-Csrf'] = csrf;
  if (idempotencyKey) finalHeaders['Idempotency-Key'] = idempotencyKey;
  const response = await fetch(`${base}${path}`, {
    method,
    headers: finalHeaders,
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body))
  });
  const text = await response.text();
  let payload = null;
  try { payload = JSON.parse(text); } catch { /* 非 JSON 响应 */ }
  return { status: response.status, payload, text, headers: response.headers };
}

/** 从登录响应里取出会话 Cookie（Node 20+ 的 getSetCookie）。 */
function sessionCookie(response) {
  const raw = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie') ?? ''];
  for (const line of raw) {
    const match = /^(toolbox_admin_local=[^;]+)/.exec(String(line).trim());
    if (match) return match[1];
  }
  return null;
}

/**
 * 用账号口令登录本地隔离后端，拿到会话 Cookie 与 CSRF 令牌。
 *
 * @param base     前端回环源（同源代理）
 * @param account  本地假账号
 * @param password 口令
 */
export async function adminLogin(base, account, password) {
  const csrfResponse = await api(base, '/admin/v1/auth/csrf');
  const nonce = csrfResponse.payload?.data?.csrfToken;
  if (!nonce) throw new Error(`领取 CSRF 失败：${csrfResponse.status} ${csrfResponse.text.slice(0, 200)}`);
  const preCookie = typeof csrfResponse.headers.getSetCookie === 'function'
    ? (csrfResponse.headers.getSetCookie().map(line => String(line).split(';')[0]).join('; '))
    : '';
  const loginResponse = await api(base, '/admin/v1/auth/login', {
    method: 'POST',
    body: { account, password },
    cookie: preCookie,
    csrf: nonce
  });
  if (loginResponse.status !== 200) {
    throw new Error(`登录（${account}）失败：${loginResponse.status} ${loginResponse.text.slice(0, 200)}`);
  }
  const cookie = sessionCookie(loginResponse);
  if (!cookie) throw new Error(`登录（${account}）响应里没有会话 Cookie`);
  return {
    account,
    cookie,
    cookieName: cookie.split('=')[0],
    cookieValue: cookie.slice(cookie.indexOf('=') + 1),
    csrfToken: loginResponse.payload.data.csrfToken,
    username: loginResponse.payload.data.username,
    userId: loginResponse.payload.data.userId
  };
}

/** 读取服务端草稿。 */
export async function readDraft(base, auth, editionId) {
  const result = await api(base, `/admin/v1/textbooks/${encodeURIComponent(editionId)}/draft`, { cookie: auth.cookie });
  if (result.status !== 200) throw new Error(`读取草稿失败：${result.status} ${result.text.slice(0, 200)}`);
  return { draft: result.payload.data.draft, revision: result.payload.data.revision };
}

/** 新建草稿（幂等键每次新生成，避免与历史回执撞键）。 */
export async function createDraft(base, auth, draft, idempotencyKey) {
  const result = await api(base, '/admin/v1/textbooks', {
    method: 'POST',
    cookie: auth.cookie,
    csrf: auth.csrfToken,
    idempotencyKey: idempotencyKey ?? `create-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    body: draft
  });
  return result;
}

/** 教材总数与指定教材的发布版本号。 */
export async function listTextbooks(base, auth) {
  const result = await api(base, '/admin/v1/textbooks?page=1&pageSize=200', { cookie: auth.cookie });
  return result.payload?.data ?? { items: [], total: 0 };
}

export async function listVersions(base, auth, editionId) {
  const result = await api(base, `/admin/v1/curriculum/versions/${encodeURIComponent(editionId)}`, { cookie: auth.cookie });
  if (result.status !== 200) throw new Error(`读取版本记录失败：${result.status} ${result.text.slice(0, 200)}`);
  // 该接口的 `data` 直接就是版本数组（见 `fetchVersionHistory` 的返回类型）。
  const data = result.payload?.data;
  if (Array.isArray(data)) return data;
  return data?.versions ?? data?.items ?? [];
}

/** 造一份最小可保存草稿（内容由调用方给）。 */
export function draftPayload(editionId, entries, options = {}) {
  return {
    editionId,
    subject: 'chinese',
    grade: 1,
    semester: 'first',
    publisher: '人民教育出版社',
    series: '统编语文',
    editionLabel: '2024 年修订版',
    sourceNote: '端到端验收用样例来源，第 1 页',
    coverageDeclaration: 'partial',
    units: [{ unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 }],
    lessons: [],
    entries: entries.map((entry, index) => ({
      rowId: entry.rowId ?? `row-${index + 1}`,
      entryId: null,
      unitId: null,
      clientUnitId: 'cu1',
      lessonId: null,
      clientLessonId: null,
      text: entry.text,
      pinyin: entry.pinyin ?? null,
      meaning: null,
      listTypes: ['writing'],
      confirmed: true
    })),
    ...options
  };
}

/**
 * 页面侧工具：原生 setter 写值。
 *
 * **不能直接改 `.value`**：Vue 的响应式收不到，后端也就收不到真实保存载荷。
 */
export const NATIVE_SET = `
  (selector, value) => {
    const host = document.querySelector(selector);
    if (!host) return 'no-element';
    const el = host.matches('input, textarea') ? host : host.querySelector('input, textarea');
    if (!el) return 'no-field';
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return el.value;
  }
`;

/** 页面侧工具：按按钮文本点击（合成 click；真实鼠标在本仓库不稳定，不用）。 */
export const CLICK_TEXT = `
  (text) => {
    const button = [...document.querySelectorAll('button')]
      .find(item => (item.textContent ?? '').trim() === text);
    if (!button) return false;
    button.click();
    return true;
  }
`;

/** 页面侧工具：读可见条目（顺序即编辑器里的顺序）。 */
export const READ_ROWS = `
  () => [...document.querySelectorAll('.metadata-list li')].map(item => ({
    rowId: item.querySelector('.metadata-row-id')?.textContent ?? null,
    text: item.querySelector('.metadata-text')?.textContent ?? null
  }))
`;

/** 页面侧工具：读目录树（单元标题 + 其下课文标题，顺序即界面顺序）。 */
export const READ_OUTLINE = `
  () => [...document.querySelectorAll('.outline-tree > li')].map(item => ({
    unit: (item.querySelector('.outline-unit button')?.textContent ?? item.querySelector('.outline-unit')?.textContent ?? '').trim(),
    lessons: [...item.querySelectorAll('.outline-lessons > li')]
      .map(lesson => (lesson.querySelector('button')?.textContent ?? '').trim())
  }))
`;

/** 页面侧工具：等待某个按钮的 disabled 状态符合预期（等条件，不 sleep 硬等）。 */
export const WAIT_FOR_UNDO_STATE = `
  (expectedDisabled) => {
    const button = [...document.querySelectorAll('button')]
      .find(item => (item.textContent ?? '').trim() === '撤销');
    if (!button) return 'no-undo-button';
    return Boolean(button.disabled) === Boolean(expectedDisabled);
  }
`;

export { sleep };
