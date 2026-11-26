/* 手机适配处理布局、导航和用户主动触发的终端按键，不接触凭据或设置数据。 */
(function () {
  "use strict";
  if (window.mytoolsDshMobileBack) return;
  if (typeof crypto.randomUUID !== "function") {
    crypto.randomUUID = function () {
      var bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 15) | 64;
      bytes[8] = (bytes[8] & 63) | 128;
      var hex = Array.from(bytes, function (value) { return value.toString(16).padStart(2, "0"); }).join("");
      return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20);
    };
  }
  var mobile = window.matchMedia("(max-width: 767px), (max-width: 900px) and (max-height: 500px)");
  var collapsed = false;
  var dismissedPointer = false;
  function viewport() {
    var view = window.visualViewport;
    document.documentElement.style.setProperty("--mytools-view-height", (view ? view.height : window.innerHeight) + "px");
    document.documentElement.style.setProperty("--mytools-view-top", (view ? view.offsetTop : 0) + "px");
  }
  function visible(element) {
    return element instanceof HTMLElement && element.getClientRects().length > 0 &&
      !element.closest('[inert], [aria-hidden="true"]') && getComputedStyle(element).visibility !== "hidden";
  }
  function lastVisible(selector) {
    return Array.from(document.querySelectorAll(selector)).filter(visible).pop();
  }
  function closeSidebar() {
    var sidebar = document.querySelector(".hHd-Xa_root:not(.hHd-Xa_collapsed)");
    if (!sidebar || !visible(sidebar)) return false;
    sidebar.querySelector(".hHd-Xa_toggle")?.click();
    return true;
  }
  // 仅标记上游临时消息。节点消失可能是确认、拒绝或离开会话，不能据此显示发送成功。
  function observeSubmissionEchoes(root) {
    var selector = '[data-submission-echo]';
    var pending = new Map();
    function remove(echo) {
      var entry = pending.get(echo);
      if (!entry) return;
      window.clearTimeout(entry.timer);
      entry.label.remove();
      pending.delete(echo);
    }
    function update(echo) {
      if (!root.contains(echo) || !echo.matches(selector)) {
        remove(echo);
        return;
      }
      var entry = pending.get(echo);
      if (!entry) {
        var label = document.createElement('span');
        label.setAttribute('data-mytools-submission-status', 'pending');
        label.setAttribute('role', 'status');
        label.setAttribute('aria-atomic', 'true');
        label.textContent = '\u7b49\u5f85\u670d\u52a1\u7aef\u786e\u8ba4';
        // 只描述等待时间，避免把仍在处理的请求判为失败。
        var timer = window.setTimeout(function () {
          if (!root.contains(echo) || !echo.matches(selector)) {
            remove(echo);
            return;
          }
          label.setAttribute('data-mytools-submission-status', 'unconfirmed');
          label.textContent = '\u5c1a\u672a\u6536\u5230\u670d\u52a1\u7aef\u786e\u8ba4';
        }, 20000);
        entry = { label: label, timer: timer };
        pending.set(echo, entry);
      }
      // 同一节点更新内容时仍使用原计时，不新建状态或重复计时。
      if (entry.label.parentElement !== echo) echo.appendChild(entry.label);
    }
    root.querySelectorAll(selector).forEach(update);
    var observer = new MutationObserver(function (records) {
      var affected = new Set();
      var removed = false;
      records.forEach(function (record) {
        if (record.target instanceof Element && (record.target.matches(selector) || pending.has(record.target))) {
          affected.add(record.target);
        }
        if (record.type !== 'childList') return;
        removed = removed || record.removedNodes.length > 0;
        // 只检查新增子树，聊天输出不会触发重新扫描既有会话。
        record.addedNodes.forEach(function (node) {
          if (!(node instanceof Element) || node.hasAttribute('data-mytools-submission-status')) return;
          if (node.matches(selector)) affected.add(node);
          node.querySelectorAll(selector).forEach(function (echo) { affected.add(echo); });
        });
      });
      if (removed) pending.forEach(function (_, echo) {
        if (!root.contains(echo)) remove(echo);
      });
      affected.forEach(update);
    });
    observer.observe(root, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['data-submission-echo'] });
  }
  // 保留光标与指令导航，只有进入编辑区的触摸操作才恢复软键盘。
  function installComposerKeyboardPolicy() {
    var inputSelector = '[data-composer-input][contenteditable="true"]';
    var buttonSelector = 'button.uV2eYG_add[aria-haspopup="listbox"]:enabled';
    var saved = new WeakMap();
    var resting = window.visualViewport;
    var restingWidth = resting?.width;
    var restingHeight = resting?.height;
    function restore(input) {
      var previous = saved.get(input);
      if (!previous) return;
      saved.delete(input);
      [['inputmode', 'none', previous.mode], ['virtualkeyboardpolicy', 'manual', previous.policy]].forEach(function (entry) {
        if (input.getAttribute(entry[0]) !== entry[1]) return;
        if (entry[2] === null) input.removeAttribute(entry[0]);
        else input.setAttribute(entry[0], entry[2]);
      });
    }
    function keepOpenKeyboard(input) {
      if (document.activeElement !== input) return false;
      var view = window.visualViewport;
      if (navigator.virtualKeyboard?.boundingRect?.height > 0) return true;
      if (!view || view.scale !== 1) return false;
      var fullHeight = view.width === restingWidth ? restingHeight : 0;
      return Math.max(document.documentElement.clientHeight, fullHeight || 0) - view.height >= 100;
    }
    function onToolbarActivation(event) {
      if (!mobile.matches || event.defaultPrevented || event.button !== 0 || !(event.target instanceof Element)) return;
      var button = event.target.closest(buttonSelector);
      var input = button?.closest('[data-composer-card]')?.querySelector(inputSelector);
      if (!input || saved.has(input) || keepOpenKeyboard(input)) return;
      saved.set(input, { mode: input.getAttribute('inputmode'), policy: input.getAttribute('virtualkeyboardpolicy') });
      input.setAttribute('virtualkeyboardpolicy', 'manual');
      input.setAttribute('inputmode', 'none');
    }
    function onEditorTouch(event) {
      if (!(event.target instanceof Element)) return;
      var input = event.target.closest(inputSelector);
      if (input) restore(input);
    }
    function rememberRestingViewport() {
      var view = window.visualViewport;
      if (!view || view.scale !== 1 || document.activeElement?.matches(inputSelector)) return;
      restingWidth = view.width;
      restingHeight = view.height;
    }
    window.visualViewport?.addEventListener('resize', rememberRestingViewport);
    document.addEventListener('pointerdown', onEditorTouch, true);
    // DSH 在 mousedown 和 click 内都会主动聚焦，捕获阶段先设好键盘策略。
    document.addEventListener('mousedown', onToolbarActivation, true);
    document.addEventListener('click', onToolbarActivation, true);
    document.addEventListener('click', onEditorTouch, true);
    document.addEventListener('focusout', function (event) { restore(event.target); }, true);
  }
  function observeComposerMetrics(root) {
    var selector = '[data-composer-stat="activity"], [data-composer-stat="usage"]';
    var primary = 'data-mytools-stat-primary';
    var bound = new Map();
    function update(stat) {
      var button = stat.querySelector('button.bOPqQW_pill');
      if (!button) return;
      var label = button.querySelector('.bOPqQW_label');
      var first = label?.firstChild;
      var value = first?.nodeType === Node.TEXT_NODE && label.querySelector('.bOPqQW_sep[aria-hidden="true"]') ?
        first.textContent.trim() : '';
      if (value && button.getAttribute(primary) !== value) button.setAttribute(primary, value);
      else if (!value && button.hasAttribute(primary)) button.removeAttribute(primary);
    }
    function bind(stat) {
      if (bound.has(stat)) return;
      update(stat);
      // 仅观察统计项的文本，消息流式渲染不会触发更新。
      var observer = new MutationObserver(function () { update(stat); });
      observer.observe(stat, { childList: true, subtree: true, characterData: true });
      bound.set(stat, observer);
    }
    root.querySelectorAll(selector).forEach(bind);
    var observer = new MutationObserver(function (records) {
      var removed = false;
      records.forEach(function (record) {
        removed = removed || record.removedNodes.length > 0;
        record.addedNodes.forEach(function (node) {
          if (!(node instanceof Element)) return;
          if (node.matches(selector)) bind(node);
          node.querySelectorAll(selector).forEach(bind);
        });
      });
      if (removed) bound.forEach(function (watch, stat) {
        if (root.contains(stat)) return;
        watch.disconnect();
        bound.delete(stat);
      });
    });
    observer.observe(root, { childList: true, subtree: true });
  }
  // 只给获得焦点的终端添加按键条，不监听或读取用户输入内容。
  function enhanceTerminal(target) {
    if (!mobile.matches || !(target instanceof Element)) return;
    var input = target.closest('.xterm')?.querySelector('textarea.xterm-helper-textarea');
    var root = input?.closest('[data-sidebar-terminal]');
    if (!root || root.querySelector('[data-mytools-terminal-keys]')) return;
    var toolbar = document.createElement('div');
    toolbar.dataset.mytoolsTerminalKeys = '';
    toolbar.setAttribute('role', 'toolbar');
    toolbar.setAttribute('aria-label', '\u7ec8\u7aef\u5feb\u6377\u6309\u952e');
    var keys = [
      ['Tab', 'Tab', 'Tab', 9], ['Esc', 'Escape', 'Escape', 27],
      ['Ctrl+C', 'c', 'KeyC', 67, true], ['Ctrl+L', 'l', 'KeyL', 76, true],
      ['\u2191', 'ArrowUp', 'ArrowUp', 38], ['\u2193', 'ArrowDown', 'ArrowDown', 40],
      ['\u2190', 'ArrowLeft', 'ArrowLeft', 37], ['\u2192', 'ArrowRight', 'ArrowRight', 39],
      ['Home', 'Home', 'Home', 36], ['End', 'End', 'End', 35]
    ];
    keys.forEach(function (key) {
      var button = document.createElement('button');
      button.type = 'button';
      button.textContent = key[0];
      button.setAttribute('aria-label', key[4] ? key[0] : key[1]);
      // 按下辅助键不抢走输入焦点，手机软键盘因此可继续保留。
      button.addEventListener('pointerdown', function (event) { event.preventDefault(); });
      button.addEventListener('click', function () {
        if (!mobile.matches || !input.isConnected || input.disabled || !visible(root)) return;
        if (lastVisible('[role="dialog"],dialog[open],[role="menu"],[role="listbox"]')) return;
        input.focus({ preventScroll: true });
        // 沿用终端键盘处理链，不直接调用远端接口，也不自动发送回车执行命令。
        var options = { key: key[1], code: key[2], keyCode: key[3], which: key[3],
          ctrlKey: !!key[4], bubbles: true, cancelable: true };
        input.dispatchEvent(new KeyboardEvent('keydown', options));
        input.dispatchEvent(new KeyboardEvent('keyup', options));
      });
      toolbar.appendChild(button);
    });
    root.appendChild(toolbar);
  }
  // 返回复用上游的关闭与 Escape 语义，不修改设置值，也不重放认证票据。
  window.mytoolsDshMobileBack = function () {
    var menu = lastVisible('[role="menu"],[role="listbox"]');
    var dialog = lastVisible('[role="dialog"],dialog[open]');
    if (menu || dialog) {
      var target = visible(document.activeElement) ? document.activeElement : (menu || dialog);
      target.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", code: "Escape", bubbles: true, cancelable: true }));
      return true;
    }
    var panelClose = lastVisible('.P3OORG_iconButton[aria-label]');
    // 只选择已观察到的右侧面板关闭动作，不能误触全屏切换或新建终端。
    if (panelClose && /^(Collapse right sidebar|Close right sidebar|\u6536\u8d77\u53f3\u4fa7\u8fb9\u680f|\u5173\u95ed\u53f3\u4fa7\u8fb9\u680f)$/i.test(panelClose.getAttribute("aria-label") || "")) {
      panelClose.click();
      return true;
    }
    if (closeSidebar()) return true;
    var detailBack = lastVisible(".X_2TxG_crumb");
    if (detailBack) { detailBack.click(); return true; }
    return false;
  };
  function collapseInitialSidebar() {
    if (!mobile.matches || collapsed) return collapsed;
    var sidebar = document.querySelector(".hHd-Xa_root");
    if (!sidebar) return false;
    collapsed = true;
    closeSidebar();
    return true;
  }
  function setup() {
    viewport();
    var root = document.getElementById('root') || document.body;
    observeSubmissionEchoes(root);
    observeComposerMetrics(root);
    document.addEventListener('focusin', function (event) { enhanceTerminal(event.target); });
    enhanceTerminal(document.activeElement);
    // 只在首次挂载前观察节点；聊天流式输出不再触发全页扫描。
    if (!collapseInitialSidebar() && mobile.matches) {
      var observer = new MutationObserver(function () { if (collapseInitialSidebar()) observer.disconnect(); });
      observer.observe(document.body, { childList: true, subtree: true });
      window.setTimeout(function () { observer.disconnect(); }, 30000);
    }
    document.addEventListener("click", function (event) {
      if (!mobile.matches || !(event.target instanceof Element)) return;
      var sidebar = event.target.closest(".hHd-Xa_root:not(.hHd-Xa_collapsed)");
      if (!sidebar) return;
      var page = event.target.closest('nav[aria-label] button');
      var newSession = event.target.closest('.hHd-Xa_newSession');
      var session = event.target.closest('[role="treeitem"]:not([aria-expanded])');
      // 选择页面或具体会话后收起抽屉，展开工作区分组仍保留列表。
      if (page || newSession || (session && !event.target.closest("button"))) requestAnimationFrame(function () {
        if (!sidebar.classList.contains("hHd-Xa_collapsed")) sidebar.querySelector(".hHd-Xa_toggle")?.click();
      });
    });
    document.addEventListener("click", function (event) {
      if (!dismissedPointer) return;
      dismissedPointer = false;
      event.preventDefault();
      event.stopImmediatePropagation();
    }, true);
    // 点击抽屉以外的区域只收起抽屉，不穿透触发背后的会话操作。
    document.addEventListener("pointerdown", function (event) {
      dismissedPointer = false;
      if (!mobile.matches || !(event.target instanceof Element)) return;
      if (event.target.closest('[role="dialog"],[role="menu"],[role="listbox"],[data-radix-popper-content-wrapper]')) return;
      if (document.querySelector('[role="dialog"],[role="menu"],[role="listbox"]')) return;
      var sidebar = document.querySelector(".hHd-Xa_root:not(.hHd-Xa_collapsed)");
      if (!sidebar || sidebar.contains(event.target)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      sidebar.querySelector(".hHd-Xa_toggle")?.click();
      dismissedPointer = true;
    }, true);
    installComposerKeyboardPolicy();
  }
  if (document.readyState === "loading") window.addEventListener("DOMContentLoaded", setup, { once: true });
  else setup();
  window.addEventListener("resize", viewport);
  mobile.addEventListener("change", function () { collapseInitialSidebar(); viewport(); enhanceTerminal(document.activeElement); });
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", viewport);
    window.visualViewport.addEventListener("scroll", viewport);
  }
})();
