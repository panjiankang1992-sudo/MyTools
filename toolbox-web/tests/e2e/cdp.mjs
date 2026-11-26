import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';

/**
 * 极简 Chrome DevTools Protocol 驱动。
 * Node 22 自带 WebSocket，因此不需要 Playwright 之类的额外依赖。
 */

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** 按常见安装位置寻找 Chrome，允许用 CHROME_PATH 覆盖。 */
export function findChrome() {
  const candidates = [
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
  ].filter(Boolean);
  return candidates.find(path => existsSync(path)) ?? null;
}

/**
 * 建临时浏览器 profile 目录。
 *
 * **必须**放在系统临时目录：profile 是运行时垃圾（一次可达上百 MB）。
 * 曾经有脚本把它建在 `docs/verification/.../evidence/browser/` 下，
 * 于是 226 MB 的 Chrome 用户数据目录混进了证据目录，而且完全没有提示。
 */
export async function createProfileDir(prefix = 'toolbox-e2e-') {
  return await mkdtemp(join(tmpdir(), prefix));
}

/**
 * 删除临时 profile 目录。
 *
 * 刚 `SIGKILL` 掉浏览器时，profile 里可能还有文件被短暂占用，第一次 `rm` 会失败；
 * 因此重试几次。仍然失败也不抛错（清理不该掩盖真正的断言结果），
 * 但会打印出来——**静默留下几百 MB 垃圾正是这次要修的问题**。
 */
/**
 * 停掉浏览器：先杀子进程，再按 profile 路径兜底清理**存活下来的残留进程**。
 *
 * 为什么需要兜底：本仓库第 140 轮遗留过一个 headless Chrome（用的是临时目录里的 profile），
 * 被 kill 之后仍活着 1 小时 16 分、占着 104 MB 的 profile，而 `rm` 因文件被占用而失败。
 * 兜底只匹配 `--user-data-dir=<本次 profile>` 这一个精确路径，绝不误杀别的会话的浏览器。
 *
 * @param child      `launchChrome()` 返回的子进程
 * @param profileDir 本次使用的 profile 目录
 */
export async function stopChrome(child, profileDir) {
  try { child?.kill('SIGKILL'); } catch { /* 已退出 */ }
  if (!profileDir) return;
  await new Promise(resolve => setTimeout(resolve, 300));
  try {
    const { spawnSync } = await import('node:child_process');
    spawnSync('pkill', ['-f', `--user-data-dir=${profileDir}`], { stdio: 'ignore' });
  } catch { /* pkill 不可用时忽略：前面的 child.kill 已覆盖主进程 */ }
}

export async function removeProfileDir(dir) {
  if (!dir) return;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const result = await rm(dir, { recursive: true, force: true }).then(() => true, () => false);
    if (result) return;
    await new Promise(resolve => setTimeout(resolve, 400));
  }
  console.warn(`临时浏览器 profile 未能删除（可手动清理）：${dir}`);
}

/** 启动无头浏览器并等待调试端口可用。 */
export async function launchChrome({ executablePath, port, userDataDir }) {
  // 硬闸门：profile 绝不能落在仓库的 docs/ 下——那里是证据目录，不是垃圾场。
  const resolvedProfile = resolve(userDataDir ?? '');
  if (resolvedProfile.includes(`${sep}docs${sep}`)) {
    throw new Error(`拒绝把浏览器 profile 放进 docs/ 证据目录：${resolvedProfile}（请改用 createProfileDir()）`);
  }
  const child = spawn(executablePath, [
    '--headless=old',
    '--disable-gpu',
    '--no-sandbox',
    '--disable-dev-shm-usage',
    '--no-first-run',
    '--disable-sync',
    '--disable-background-networking',
    // 打印预览依赖 window.open，无头模式默认拦截弹窗。
    '--disable-popup-blocking',
    `--user-data-dir=${userDataDir}`,
    `--remote-debugging-port=${port}`,
    '--window-size=1440,1000',
    'about:blank'
  ], { stdio: 'ignore', detached: false });
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) return child;
    } catch { /* 还没起来 */ }
    await sleep(250);
  }
  child.kill('SIGKILL');
  throw new Error('Chrome 调试端口未就绪');
}

/** 连接页面目标并返回可用的会话对象。 */
export async function connect(port) {
  let targetUrl = null;
  for (let attempt = 0; attempt < 40 && !targetUrl; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      targetUrl = targets.find(target => target.type === 'page')?.webSocketDebuggerUrl ?? null;
    } catch { /* 重试 */ }
    if (!targetUrl) await sleep(300);
  }
  if (!targetUrl) throw new Error('找不到可调试的页面目标');

  const socket = new WebSocket(targetUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });

  let sequence = 0;
  const pending = new Map();
  const listeners = [];
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(`${message.method ?? 'cdp'} ${JSON.stringify(message.error)}`));
      else resolve(message.result);
    } else if (message.method) {
      for (const listener of listeners) listener(message);
    }
  });

  return {
    /**
     * 发送 CDP 命令。
     * @param method 方法名
     * @param params 参数
     * @param sessionId 可选的会话 id；附加到 worker 等子目标时必须携带
     */
    send: (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      const id = ++sequence;
      pending.set(id, { resolve, reject });
      // 会话消息必须回填 sessionId，否则命令会被当作浏览器级命令处理。
      socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    }),
    /** 在浏览器级开启自动附加，返回已附加的会话 id 列表（含 worker）。 */
    autoAttach: async () => {
      const sessions = [];
      listeners.push(message => {
        if (message.method === 'Target.attachedToTarget') {
          sessions.push({ sessionId: message.params.sessionId, targetInfo: message.params.targetInfo });
        }
      });
      await new Promise((resolve, reject) => {
        const id = ++sequence;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method: 'Target.setAutoAttach', params: { autoAttach: true, waitForDebuggerOnStart: false, flatten: true } }));
      });
      return sessions;
    },
    on: listener => listeners.push(listener),
    close: () => socket.close()
  };
}

/** 采集控制台错误、未捕获异常与失败请求。 */
export function createProbe(cdp) {
  const consoleErrors = [];
  const pageErrors = [];
  const failedRequests = [];
  const badResponses = [];
  cdp.on(message => {
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      consoleErrors.push((message.params.args ?? []).map(arg => arg.value ?? arg.description ?? arg.type).join(' '));
    }
    if (message.method === 'Runtime.exceptionThrown') {
      pageErrors.push(message.params.exceptionDetails?.exception?.description ?? message.params.exceptionDetails?.text ?? 'unknown');
    }
    if (message.method === 'Network.loadingFailed' && !message.params.canceled) {
      failedRequests.push(`${message.params.type} ${message.params.errorText}`);
    }
    if (message.method === 'Network.responseReceived') {
      const { url, status } = message.params.response;
      // 业务接口的 4xx/5xx 由场景单独判断，这里只关心静态资源异常。
      // 公开接口的 4xx 由各用例自行断言；管理接口只豁免"匿名被拒的 401"，
      // 因为那是权限设计的正确行为，而 404/500 仍应视为异常。
      const expectedAdminRejection = status === 401 && url.includes('/admin/v1/');
      if (status >= 400 && !url.includes('/public/v1/') && !expectedAdminRejection) {
        badResponses.push(`${status} ${url}`);
      }
    }
  });
  return { consoleErrors, pageErrors, failedRequests, badResponses };
}

/**
 * 在页面中执行表达式并取回可序列化结果。
 *
 * @param sessionId 可选的 target 会话 id。**必须支持它**：打印预览是独立窗口（独立 target），
 *   不带 sessionId 求值会静默落到主页面，于是"打印窗口的就绪状态"读出来的是主页面 ——
 *   本会话踩过这个坑（表现为 title=主站标题、工具栏为空，极易误判成产品缺陷）。
 */
export async function evaluate(cdp, expression, sessionId) {
  // 表达式必须先补分号再拼接：源码里写了 `... })();` 时，紧跟换行再拼 `})()`
  // 会被 ASI 切成两个语句，返回值静默变成 undefined（本会话踩过）。
  const source = expression.trimEnd().endsWith(';') ? expression.trimEnd() : `${expression.trimEnd()};`;
  const result = await cdp.send('Runtime.evaluate', {
    expression: `(async () => { ${source} })()`,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true
  }, sessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  }
  return result.result.value;
}

/**
 * 取元素中心点坐标；元素不可见时先滚动到视口内。
 * 返回 null 表示元素不存在或仍不在视口内。
 */
export async function centerOf(cdp, expression) {
  return await evaluate(cdp, `
    const element = ${expression};
    if (!element) return null;
    // 只在确实不可见时才滚动：下拉选项一旦滚动，弹出层会被关闭，导致后续点击落空。
    const before = element.getBoundingClientRect();
    const visible = before.width > 0 && before.height > 0 && before.top >= 0 && before.bottom <= window.innerHeight;
    if (!visible) {
      element.scrollIntoView({ block: 'center' });
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    const rect = element.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    if (rect.top < 0 || rect.bottom > window.innerHeight) return null;
    return { x: Math.round(rect.left + rect.width / 2), y: Math.round(rect.top + rect.height / 2) };
  `);
}

/**
 * 用真实鼠标事件点击坐标。
 * Naive UI 的下拉与树不响应合成的 element.click()，必须走 Input 域。
 */
export async function clickPoint(cdp, point) {
  if (!point) return false;
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y, button: 'none' });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
  return true;
}

/**
 * 在指定下拉中按文本选择一项并重试。
 * 目录接口可能较慢，选项出现前不能把「尚未加载」当成「没有内容」。
 */
export async function pickSelectOption(cdp, selectExpression, text, attempts = 12) {
  const openAndFind = async () => {
    await clickPoint(cdp, await centerOf(cdp, `(${selectExpression})?.querySelector('.n-base-selection')`));
    await sleep(1200);
    const point = await centerOf(cdp, `
      [...document.querySelectorAll('.n-base-select-option')].find(o => o.textContent.trim().includes(${JSON.stringify(text)}))
    `);
    return point;
  };
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const point = await openAndFind();
    if (point) {
      await clickPoint(cdp, point);
    } else {
      // 鼠标路径落空时的兜底：对已渲染的选项派发合成 click。
      await evaluate(cdp, `
        const option = [...document.querySelectorAll('.n-base-select-option')]
          .find(o => o.textContent.trim().includes(${JSON.stringify(text)}));
        option?.click();
        return !!option;
      `);
    }
    await sleep(1800);
    // 只有选中值真的变了才算成功，避免「点了但没生效」被当成通过。
    const applied = await evaluate(cdp, `
      const select = ${selectExpression};
      return (select?.textContent ?? '').includes(${JSON.stringify(text)});
    `);
    if (applied) return true;
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(1200);
  }
  return false;
}

/** 轮询等待页面条件成立。 */
export async function waitFor(cdp, expression, label, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    // 表达式与语句两种写法都要支持：
    // - 单表达式（`a && b`）必须被括号包住，否则多行表达式在 `return` 后会触发 ASI，
    //   取值会静默变成 undefined；
    // - 多语句体（以 `return ...;` 结尾）直接用括号包会报 `Unexpected token 'const'`，
    //   必须作为语句体执行。
    // 因此先按单表达式求值，语法不通过时退回到语句体形态。
    last = await evaluate(cdp, `return (${expression});`).catch(async error => {
      if (!/SyntaxError/u.test(String(error?.message ?? error))) throw error;
      return await evaluate(cdp, expression);
    });
    if (last) return last;
    await sleep(400);
  }
  throw new Error(`等待「${label}」超时（最后一次取值 ${JSON.stringify(last)}）`);
}

export { sleep };
