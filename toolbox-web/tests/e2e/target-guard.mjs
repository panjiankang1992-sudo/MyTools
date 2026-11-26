/**
 * 端到端目标的回环闸门。
 *
 * 背景：`tests/e2e/run.mjs` 原先默认目标是公网站点 `https://toolbox.yuyutian.top`。
 * 一旦有人在没有显式设置 `E2E_TARGET` 的情况下运行它，浏览器就会对**生产**发起
 * 真实的访问与写入。验收脚本必须自己拒绝这种取值，而不是依赖调用方记得设置。
 *
 * 因此这里只允许两种目标：
 * 1. 显式传入 `E2E_TARGET`；
 * 2. 取值必须是回环地址（`127.0.0.1` / `localhost` / `[::1]`）。
 *
 * 其余情况（未设置、指向公网、协议不是 http）一律以退出码 2 报告为「未执行」。
 */

/** 允许的回环主机名。 */
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]']);

/**
 * 判断目标地址是否是回环地址。
 *
 * @param raw 原始目标地址
 * @returns 是回环 http 地址时为 true
 */
export function isLoopbackTarget(raw) {
  if (typeof raw !== 'string' || raw.trim() === '') return false;
  let url;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  // 只接受明文 http：本地 Vite dev 不走 TLS，https 回环不在验收范围内。
  return url.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname);
}

/**
 * 校验目标地址；不合法时打印原因并让调用方退出。
 *
 * 刻意**不返回默认值**：任何"没设置就用默认"的行为都会把默认值打到某个真实站点上。
 *
 * @param raw            原始 `E2E_TARGET`
 * @param envVarName     变量名，用于错误文案
 * @returns 已去掉末尾斜杠的目标地址
 */
export function requireLoopbackTarget(raw, envVarName = 'E2E_TARGET') {
  if (typeof raw !== 'string' || raw.trim() === '') {
    console.error(`未设置 ${envVarName}：拒绝回落到默认目标，本次未执行（退出码 2）。`);
    process.exit(2);
  }
  if (!isLoopbackTarget(raw)) {
    console.error(
      `${envVarName} 必须是本机回环地址（如 http://127.0.0.1:4173），当前为 ${raw}：本次未执行（退出码 2）。`
    );
    process.exit(2);
  }
  return raw.replace(/\/+$/, '');
}
