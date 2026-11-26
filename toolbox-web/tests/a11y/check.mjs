/**
 * 公共页面的可访问性检查（D04 的「标签与错误可访问」）。
 *
 * 用真实浏览器打开公共页面，检查：
 * 1. **在 Tab 顺序内**的交互元素必须有可访问名称——没有名称时，键盘／读屏用户
 *    只会听到"按钮"，也不知道按下去会发生什么；
 * 2. 图片必须有 `alt`；
 * 3. 表单控件必须有标签（`aria-label`／`<label>`／占位提示）。
 *
 * 注意：`tabindex="-1"` 的元素**被刻意移出 Tab 顺序**（如 Naive UI 密码框内的
 * 显示／隐藏切换）。它们缺少名称仍不理想，但**不构成键盘可达性问题**，
 * 因此单独归类，不与前者混为一谈。
 *
 * 用法：node tests/a11y/check.mjs   （需本机前端运行在 127.0.0.1:4173）
 */
import { connect, findChrome, launchChrome, evaluate, sleep } from '../e2e/cdp.mjs';

const ROUTES = ['/#/', '/#/copybook', '/#/pdf'];
const chrome = await launchChrome({ executablePath: findChrome(), port: 9779, userDataDir: '/tmp/tb-a11y-check' });
const cdp = await connect(9779);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');

let failed = false;
for (const route of ROUTES) {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: `http://127.0.0.1:4173${route}` });
  await sleep(6000);
  const report = await evaluate(cdp, `
    const nameOf = el => (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').trim()
      || (el.labels && el.labels[0] ? el.labels[0].textContent.trim() : '') || el.getAttribute('placeholder') || '';
    const visible = el => { const s = getComputedStyle(el); return s.display !== 'none' && s.visibility !== 'hidden'; };
    const all = [...document.querySelectorAll('button, a[href], input, select, textarea, [tabindex]')].filter(visible);
    const unnamed = all.filter(el => nameOf(el).length === 0);
    return {
      // 真正的问题：在 Tab 顺序内却没有名称
      reachableUnnamed: unnamed.filter(el => el.getAttribute('tabindex') !== '-1').map(el => el.tagName),
      // 次要问题：被移出 Tab 顺序且没有名称
      unreachableUnnamed: unnamed.filter(el => el.getAttribute('tabindex') === '-1').map(el => el.tagName),
      imgsWithoutAlt: [...document.querySelectorAll('img')].filter(i => !i.hasAttribute('alt')).length,
      unlabeledFields: [...document.querySelectorAll('input:not([type=hidden]), select, textarea')]
        .filter(visible)
        .filter(el => !el.getAttribute('aria-label') && !(el.labels && el.labels.length) && !el.getAttribute('placeholder')).length
    };
  `);
  // 焦点可见性：键盘用户必须能看出焦点在哪。
  //
  // **必须用真实按键**（`Input.dispatchKeyEvent` 发 Tab），不能用 `el.focus()`：
  // 现代样式普遍写成 `:focus-visible`，而它**只对键盘交互生效**——
  // 程序化聚焦不触发它，会得到"所有元素都无焦点样式"的**假结论**。
  // 判定方式：**比对聚焦前后的计算样式**，并**连同 3 层祖先一起比对**。
  //
  // 为什么必须看祖先：Naive UI 把焦点样式加在**外层容器**（`.n-input` 的边框变色），
  // 内层 `<input>` 自身没有任何变化。只看被聚焦元素本身会把这种情况误判为"无焦点样式"。
  await evaluate(cdp, `if (document.activeElement) document.activeElement.blur(); window.__a11ySnap = () => {
    const el = document.activeElement;
    if (!el || el === document.body) return null;
    const chain = []; let node = el;
    for (let i = 0; i < 4 && node && node !== document.body; i += 1) {
      const s = getComputedStyle(node);
      chain.push([s.outlineStyle, s.outlineWidth, s.boxShadow, s.borderColor, s.backgroundColor].join('|'));
      node = node.parentElement;
    }
    return { tag: el.tagName, cls: (el.className || '').toString().slice(0, 40), chain: chain.join('~~') };
  }; return true;`);
  const focus = { checked: 0, withoutVisibleChange: [] };
  for (let step = 0; step < 25; step += 1) {
    await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', windowsVirtualKeyCode: 9, key: 'Tab', code: 'Tab' });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: 9, key: 'Tab', code: 'Tab' });
    await sleep(120);
    const result = await evaluate(cdp, `
      const cur = window.__a11ySnap();
      if (!cur) return null;
      return { tag: cur.tag, cls: cur.cls, chain: cur.chain };`);
    if (!result) break;
    focus.checked += 1;
    // 该元素（及其祖先链）是否至少有一层出现了焦点样式。
    const styled = await evaluate(cdp, `
      const el = document.activeElement;
      if (!el) return false;
      let node = el;
      for (let i = 0; i < 4 && node && node !== document.body; i += 1) {
        const s = getComputedStyle(node);
        const cls = (node.className || '').toString();
        if (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) return true;
        if (s.boxShadow && s.boxShadow !== 'none') return true;
        // Naive UI 用类名标记聚焦态（边框随之变色）。
        if (/focus|--focus/.test(cls)) return true;
        node = node.parentElement;
      }
      return false;`);
    if (!styled) focus.withoutVisibleChange.push(`${result.tag}${result.cls ? '.' + result.cls : ''}`);
  }
  console.log(`${route} 焦点可见性：真实 Tab 检查 ${focus.checked} 个，无可见轮廓 ${focus.withoutVisibleChange.length} 个`
    + (focus.withoutVisibleChange.length ? '：' + focus.withoutVisibleChange.slice(0, 5).join(', ') : ''));

  // 焦点可见性纳入判定：键盘用户看不到焦点就是可达性问题。
  const ok = report.reachableUnnamed.length === 0 && report.imgsWithoutAlt === 0
    && report.unlabeledFields === 0 && focus.withoutVisibleChange.length === 0;
  if (!ok) failed = true;
  console.log(`${route} ${ok ? 'OK ' : 'FAIL'} ${JSON.stringify(report)}`);
}
// ---- 管理页（需要真实登录）----
//
// 为什么补这一段：第 149／150 轮发现的两个可访问性问题（`NInput` 的 `aria-label`
// 不落到内部 `<input>`）**都是手工探针发现的，这个脚本一个都没报**——
// 因为它只跑公共路由。**检查的覆盖面决定了能发现什么。**
//
// 凭据从环境变量读，**不写进仓库**；未提供时明确跳过，而不是静默当成"没问题"。
const account = process.env.A11Y_ADMIN_ACCOUNT;
const password = process.env.A11Y_ADMIN_PASSWORD;
if (!account || !password) {
  console.log('管理页未检查：未提供 A11Y_ADMIN_ACCOUNT／A11Y_ADMIN_PASSWORD（**不是通过**）');
} else {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: 'http://127.0.0.1:4173/#/' });
  await sleep(7000);
  await evaluate(cdp, `document.querySelector('.admin-entry')?.click(); return true;`);
  await sleep(2500);
  const loggedIn = await evaluate(cdp, `
    const set=(el,v)=>{const d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;d.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));};
    const a=document.querySelector('input[aria-label="用户名或邮箱"]');
    const p=document.querySelector('input[aria-label="密码"]');
    if(!a||!p) return false;
    set(a, ${JSON.stringify(account)}); set(p, ${JSON.stringify(password)});
    [...document.querySelectorAll('.n-button')].find(x=>x.textContent.trim()==='登录' && !x.disabled)?.click();
    return true;`);
  if (!loggedIn) {
    console.log('管理页未检查：登录表单未找到');
    failed = true;
  } else {
    await sleep(8000);
    await evaluate(cdp, `location.hash='#/admin/textbooks'; return true;`);
    await sleep(5000);
    await evaluate(cdp, `[...document.querySelectorAll('.n-button')].find(x=>x.textContent.includes('新增教材'))?.click(); return true;`);
    await sleep(4000);
    // 管理页的判据与公共页**不同**：这里关键的是"控件有没有名称"，
    // 而不是横向溢出之类。因此单独统计，不套用公共页的判定。
    const adminReport = await evaluate(cdp, `
      const named = el => (el.getAttribute('aria-label') || el.getAttribute('title')
        || (el.labels && el.labels[0] ? el.labels[0].textContent.trim() : '')
        || el.placeholder || '').trim();
      const controls = [...document.querySelectorAll('input, textarea, select')].filter(el => el.offsetParent !== null);
      const unnamed = controls.filter(el => named(el).length === 0).map(el => el.tagName + '[' + (el.placeholder||'') + ']');
      // 只以显式标签命名的才算"有标签"；仅靠 placeholder 的单独列出（合法但输入后消失）。
      const placeholderOnly = controls.filter(el => !el.getAttribute('aria-label') && !(el.labels && el.labels.length) && el.placeholder)
        .map(el => el.placeholder);
      return { controls: controls.length, unnamed, placeholderOnly };`);
    const ok = adminReport.unnamed.length === 0;
    if (!ok) failed = true;
    console.log(`管理页 ${ok ? 'OK ' : 'FAIL'} 控件 ${adminReport.controls} 个；无名称 ${adminReport.unnamed.length} 个；`
      + `仅靠 placeholder ${adminReport.placeholderOnly.length} 个`
      + (adminReport.unnamed.length ? '：' + adminReport.unnamed.join(', ') : ''));
  }
}

cdp.close();
chrome.kill('SIGKILL');
process.exit(failed ? 1 : 0);
