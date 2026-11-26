const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const compiled = fs.readFileSync(process.argv[2], 'utf8');
const { DshWebSessionRenewal } = require(process.argv[4]);
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const businessErrors = { exports: {} };
const errorSource = fs.readFileSync(require('node:path').join(require('node:path').dirname(process.argv[3]),
  '../shared/network/BusinessTransportError.ets'), 'utf8');
vm.runInNewContext(ts.transpileModule(errorSource, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, businessErrors);
const { BusinessTransportError, BusinessResponseError } = businessErrors.exports;
const publicBusinessOrigin = 'https://mytools.example.test';
const publicDshOrigin = 'https://dsh-app.yuyutian.top';
const publicDshPage = `${publicDshOrigin}/session/fixture`;
const directUrl = 'https://direct6.example.test:8443';
const ticketPath = `/_mytools/connect?ticket=${'a'.repeat(43)}`;
const publicTicket = `${publicDshOrigin}${ticketPath}`;
const directTicket = `${directUrl}${ticketPath}`;
const stalePublicTicket = `${publicDshOrigin}/_mytools/connect?ticket=${'b'.repeat(43)}`;

function host(environment, ticket) {
  const index = fs.readFileSync(process.argv[3], 'utf8');
  const methods = ['IsCurrentDshPageEvent', 'FallbackDshToPublic', 'WatchDshPageLoad', 'ConnectDshWeb',
    'RefreshDshWeb', 'PreconnectDshWeb', 'SubmitLogin', 'RestoreSession', 'OpenDshAfterAuthentication', 'ApplyBusinessEndpoint',
    'WatchDshWebSession', 'CheckDshWebSession',
    'FinishDshWebSessionCheck', 'CloseDshWebSessionCheck', 'DshActiveAttachmentPage',
    'ScheduleEndpointRediscovery', 'HasPublicDshLoadOrPage', 'RefreshAfterEndpointSwitch'].map(name => {
    const found = new RegExp('  private (?:async )?' + name + '\\(').exec(index);
    assert.ok(found, 'Missing integration method ' + name);
    const remaining = index.slice(found.index + found[0].length);
    const nextMethod = /\n  (?:\/\*\*|private |@Builder)/.exec(remaining);
    assert.ok(nextMethod, 'Missing integration method boundary ' + name);
    return index.slice(found.index, found.index + found[0].length + nextMethod.index);
  });
  const code = ts.transpileModule('class Host {' + methods.join('\n') + '} exports.Host = Host;', {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText;
  const output = { exports: {} };
  const preconnects = [];
  vm.runInNewContext(code, { exports: output.exports, DshWebLoadMonitor: environment.type, DshWebSessionRenewal,
    BusinessTransportError, BusinessResponseError,
    DownloadCancellationToken: class { cancelled = false; cancel() { this.cancelled = true; } },
    setTimeout: environment.setTimeout, clearTimeout: environment.clearTimeout, Date: { now: environment.now },
    webview: { WebviewController: { prepareForPageLoad: (...args) => preconnects.push(args) } },
    DshWebAuthPolicy: {
      PUBLIC_ORIGIN: publicDshOrigin,
      webOrigin: (base, publicBase, preferSecure, forcePublic) => !forcePublic && base !== publicBase &&
        (base.startsWith('https://') || (!preferSecure && base.startsWith('http://'))) ? base : publicDshOrigin,
      trustedPage: (page, base = '') => !page.includes('/_mytools/') &&
        (page.startsWith(`${publicDshOrigin}/`) || Boolean(base && page.startsWith(`${base}/`)))
    },
    DshWebCapabilityPolicy: { origin: url => /^https?:\/\//.test(url) ? new URL(url).origin : '' },
    DshApi: class { webTicket(...args) { return ticket(...args); } }, $r: value => value });
  const state = new output.exports.Host();
  let url = 'about:blank'; const loads = [];
  Object.assign(state, { dshWebRevision: 0, authenticationUiRevision: 0, currentIndex: 2,
    authenticated: true, dshWebConnecting: false, dshWebFailed: false, dshWebLoading: true,
    dshPublicFallback: false, ipv6ModeActive: false,
    dshWebNeedsAuth: false, dshWebLoadMonitor: environment.monitor, dshPreferSecure: true,
    dshWebSessionMonitor: new environment.type(), dshForeground: true, dshWebSessionTimer: -1,
    dshWebSessionAttempt: 0, dshWebSessionRetryAt: 0, dshWebRenewAt: 0, dshWebSessionExpired: false,
    dshWebRenewRetryAvailable: false, dshAttachmentOrigin: '',
    dshWebLaunchOrigin: '', dshWebStartedAt: 0, dshWebTicketAt: 0, dshWebDocumentReceived: false,
    dshPreconnectedOrigin: '', dshPreconnectedAt: 0,
    serviceUrl: publicBusinessOrigin, authManager: { publicBusinessBaseUrl: () => publicBusinessOrigin },
    ActiveServiceUrl: () => publicBusinessOrigin, PdfResource: r => r, CloseDshPopup: () => {},
    InstallDshDismissBehavior: () => {}, stopped: 0,
    dshWebController: { getUrl: () => url, loadUrl: value => { url = value; loads.push(value); },
      runJavaScript: async () => '1', stop: () => { state.stopped++; } } });
  return { state, loads, preconnects, setPage: value => { url = value; } };
}

// 使用真实恢复和端点切换方法，覆盖冷启动时先进入 DSH 的异步顺序。
function restoredHost(environment, ticket) {
  const connection = host(environment, ticket), state = connection.state;
  let endpoint = publicBusinessOrigin;
  Object.assign(state, { serviceUrl: publicBusinessOrigin, activeServiceUrl: publicBusinessOrigin,
    authManager: { normalizeBaseUrl: value => value,
      publicBusinessBaseUrl: () => publicBusinessOrigin,
      restore: async () => ({ username: 'test-user', nickname: '', role: 'user', avatar: '' }),
      resetBusinessEndpoint: () => { endpoint = publicBusinessOrigin; },
      activateBusinessEndpoint: value => { endpoint = value; } },
    ActiveServiceUrl: () => endpoint, PrepareLocalAccountScope: async () => {},
    ApplicationContext: () => ({}), SwitchReaderStorageScope: () => {},
    PrepareReadingSyncForAccount: () => {}, ApplyProfileAvatar: () => {},
    RefreshProfileIdentity: () => {}, PullBookSources: () => {},
    ReconnectPlaybackAfterEndpointSwitch: () => {},
    DiscoverLanEndpoint: async refresh => {
      state.ApplyBusinessEndpoint('http://192.168.1.7:23200', refresh);
    } });
  return connection;
}

function setup() {
  let now = 0, sequence = 0;
  const timers = new Map(), output = { exports: {} };
  const clock = {
    setTimeout(fn, delay) { const id = ++sequence; timers.set(id, { at: now + delay, fn }); return id; },
    clearTimeout(id) { timers.delete(id); }
  };
  vm.runInNewContext(compiled, { exports: output.exports, ...clock });
  return { monitor: new output.exports.DshWebLoadMonitor(), type: output.exports.DshWebLoadMonitor, now: () => now,
    ...clock, timers, async advance(ms) {
      const until = now + ms;
      while (true) {
        const next = [...timers].filter(([, t]) => t.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at; timers.delete(next[0]); next[1].fn(); await flush();
      }
      now = until; await flush();
    } };
}

function mountedHost(environment, ticket, page = publicDshPage, origin = publicDshOrigin) {
  const connection = host(environment, ticket);
  const state = connection.state;
  connection.setPage(page);
  state.dshAttachmentOrigin = origin;
  state.dshWebLoading = false;
  state.dshWebRenewAt = environment.now() + 20 * 60 * 1000;
  return connection;
}

function sessionScriptMock(state, statuses) {
  const scripts = [];
  state.dshWebController.runJavaScript = async script => {
    scripts.push(script);
    if (script.includes('return state.result;')) {
      assert.ok(statuses.length > 0, 'Unexpected result probe');
      return statuses.shift();
    }
    return '1';
  };
  return scripts;
}

(async () => {
  const success = setup(), ready = [];
  let state = '0', calls = 0;
  success.monitor.start(async () => { calls++; return state; }, r => ready.push(r));
  await success.advance(3000); assert.deepEqual(ready, []);
  state = '1'; success.monitor.checkNow(); await flush();
  assert.deepEqual(ready, ['1']); assert.equal(success.timers.size, 0);
  const completedCalls = calls; await success.advance(120000); assert.equal(calls, completedCalls);

  // 即便网页脚本永远不返回，截止计时仍提供重试出口。
  const hanging = setup(), timeouts = [];
  let resolveHang, hangingCalls = 0;
  hanging.monitor.start(() => { hangingCalls++; return new Promise(r => { resolveHang = r; }); }, r => timeouts.push(r));
  await hanging.advance(1000);
  hanging.monitor.checkNow(); hanging.monitor.checkNow();
  assert.equal(hangingCalls, 1);
  await hanging.advance(59000);
  assert.deepEqual(timeouts, ['timeout']); assert.equal(hanging.timers.size, 0);
  resolveHang('1'); await flush(); assert.deepEqual(timeouts, ['timeout']);

  // 上次连接的认证失败不能覆盖已经就绪的新连接。
  const reconnect = setup(), oldResults = [], newResults = [];
  let resolveOld;
  reconnect.monitor.start(() => new Promise(r => { resolveOld = r; }), r => oldResults.push(r));
  await reconnect.advance(1000);
  reconnect.monitor.start(async () => '1', r => newResults.push(r));
  resolveOld('2'); await flush();
  await reconnect.advance(1000);
  assert.deepEqual(oldResults, []); assert.deepEqual(newResults, ['1']);

  const leaving = setup(), ignored = [];
  let resolveLeaving;
  leaving.monitor.start(() => new Promise(r => { resolveLeaving = r; }), r => ignored.push(r));
  await leaving.advance(1000); leaving.monitor.close();
  resolveLeaving('2'); await flush(); await leaving.advance(60000);
  assert.deepEqual(ignored, []); assert.equal(leaving.timers.size, 0);

  const denied = setup(), auth = [];
  denied.monitor.start(async () => '2', r => auth.push(r));
  denied.monitor.checkNow(); await flush();
  assert.deepEqual(auth, ['2']); assert.equal(denied.timers.size, 0);

  // 文档完成事件、暂时无脚本上下文和重定向不能无限续期。
  const errors = setup(), exhausted = [];
  errors.monitor.start(async () => { throw new Error('Context not ready'); }, r => exhausted.push(r), 5000);
  for (let i = 0; i < 4; i++) { errors.monitor.checkNow(); await errors.advance(1000); }
  await errors.advance(1000); assert.deepEqual(exhausted, ['timeout']);

  const reentrant = setup(), transitions = [];
  reentrant.monitor.start(async () => '2', r => {
    transitions.push(r); reentrant.monitor.start(async () => '1', n => transitions.push(n));
  });
  await reentrant.advance(2000);
  assert.deepEqual(transitions, ['2', '1']); assert.equal(reentrant.timers.size, 0);

  const script = success.type.probeScript();
  const probe = (body, sidebar, root = false) => vm.runInNewContext(script, { document: {
    body,
    querySelector: selector => {
      if (selector === '#root') return root ? {} : null;
      assert.equal(selector, '.hHd-Xa_root');
      return sidebar ? {} : null;
    }
  } });
  const body = text => ({ textContent: text });
  assert.equal(probe(null, false), 0);
  assert.equal(probe(body('Loading plugins…'), false), 0);
  assert.equal(probe(body('Loading plugins…'), false, true), 3);
  const visibleChat = { get textContent() { throw new Error('Ready chat body must not be scanned'); } };
  assert.equal(probe(visibleChat, true, true), 1);
  assert.equal(probe(body('dsh web authentication required; sign in'), false), 2);

  // 直接执行 Index 的真实方法，模拟票据超时后重试及迟到票据返回。
  const integration = setup(); let oldTicket; let ticketCalls = 0;
  const connection = host(integration, () => ++ticketCalls === 1 ?
    new Promise(resolve => { oldTicket = resolve; }) : Promise.resolve(publicTicket));
  const oldConnect = connection.state.ConnectDshWeb();
  assert.equal(connection.state.IsCurrentDshPageEvent('about:blank'), false);
  assert.deepEqual(connection.preconnects[0], [`${publicDshOrigin}/`, true, 1]);
  await integration.advance(60000);
  assert.equal(connection.state.dshWebFailed, true);
  assert.equal(connection.state.dshWebConnecting, false);
  assert.equal(connection.state.stopped, 1);
  assert.equal(connection.state.dshWebStatus, 'app.string.dsh_web_load_timeout');
  await connection.state.ConnectDshWeb();
  oldTicket(stalePublicTicket); await oldConnect;
  assert.equal(connection.loads.includes(stalePublicTicket), false);
  assert.equal(connection.loads.at(-1), publicTicket);
  connection.setPage('https://dsh-app.yuyutian.top/');
  connection.state.dshWebLoadMonitor.checkNow(); await flush();
  assert.equal(connection.state.dshWebLoading, false);
  assert.equal(connection.state.dshWebFailed, false);

  const switched = setup(); const account = host(switched, async () => publicTicket);
  let oldAuth;
  account.setPage('https://dsh-app.yuyutian.top/');
  account.state.dshWebLaunchOrigin = publicDshOrigin;
  account.state.dshAttachmentOrigin = publicDshOrigin;
  account.state.dshWebController.runJavaScript = () => new Promise(resolve => { oldAuth = resolve; });
  account.state.WatchDshPageLoad(); await switched.advance(1000);
  account.state.authenticationUiRevision++;
  oldAuth('2'); await flush();
  assert.equal(account.state.dshWebFailed, false);
  assert.equal(account.state.dshWebNeedsAuth, false);
  account.state.currentIndex = 0;
  assert.equal(account.state.IsCurrentDshPageEvent('https://dsh-app.yuyutian.top/'), false);
  // 真实网络与响应错误保留重试入口，只有认证失败继续显示账号提示。
  for (const ErrorType of [BusinessTransportError, BusinessResponseError]) {
    const failed = setup();
    const connectionFailure = host(failed, async () => { throw new ErrorType('https://example.test', 'Network unavailable'); });
    await connectionFailure.state.ConnectDshWeb();
    assert.equal(connectionFailure.state.dshWebFailed, true);
    assert.equal(connectionFailure.state.dshWebNeedsAuth, false);
    assert.equal(connectionFailure.state.dshWebStatus, 'app.string.dsh_web_error');
    assert.equal(connectionFailure.state.dshWebLoading, false);
    assert.equal(connectionFailure.state.dshWebConnecting, false);
    assert.equal(failed.timers.size, 0);
  }
  const rejected = host(setup(), async () => { throw new Error('Account rejected'); });
  await rejected.state.ConnectDshWeb();
  assert.equal(rejected.state.dshWebNeedsAuth, true);
  assert.equal(rejected.state.dshWebStatus, 'app.string.dsh_web_auth_hint');
  const wrongOrigin = host(setup(), async () => directTicket);
  await wrongOrigin.state.ConnectDshWeb();
  assert.equal(wrongOrigin.state.dshWebFailed, true);
  assert.equal(wrongOrigin.state.dshWebNeedsAuth, false);
  assert.equal(wrongOrigin.loads.includes(directTicket), false);

  // 恢复账号后立即换票，处于换票和页面挂载阶段的公网连接都不会被端点发现重启。
  const restored = setup(); let finishDiscovery, finishStartupTicket, restoredTickets = 0;
  const startup = restoredHost(restored, () => {
    restoredTickets++;
    assert.equal(startup.preconnects[0]?.[0], `${publicDshOrigin}/`);
    return new Promise(resolve => { finishStartupTicket = resolve; });
  });
  startup.state.authenticated = false;
  startup.state.DiscoverLanEndpoint = () => new Promise(resolve => { finishDiscovery = resolve; });
  await startup.state.ConnectDshWeb();
  assert.equal(restoredTickets, 0);
  const restoration = startup.state.RestoreSession(); await flush();
  assert.equal(typeof finishDiscovery, 'function');
  assert.equal(restoredTickets, 1, 'first ticket does not wait for endpoint discovery');
  assert.equal(startup.state.dshWebLoadMonitor.isRunning(), true);
  const publicRevision = startup.state.dshWebRevision;
  const pendingStartupTicket = startup.state.dshTicketCancellation;
  startup.state.ApplyBusinessEndpoint(directUrl);
  assert.equal(startup.state.dshWebRevision, publicRevision);
  assert.equal(pendingStartupTicket.cancelled, false);
  finishStartupTicket(publicTicket); await flush();
  assert.equal(startup.loads.at(-1), publicTicket);
  startup.state.ApplyBusinessEndpoint('http://192.168.1.7:23200');
  assert.equal(startup.state.dshWebRevision, publicRevision);
  assert.equal(startup.loads.at(-1), publicTicket);
  finishDiscovery(); await restoration; await flush();
  assert.equal(restoredTickets, 1);
  startup.setPage(publicDshPage);
  await restored.advance(250);
  assert.equal(startup.state.dshWebLoading, false);
  startup.state.ApplyBusinessEndpoint(directUrl);
  assert.equal(startup.state.dshWebController.getUrl(), publicDshPage);
  assert.equal(startup.state.dshWebRevision, publicRevision);
  assert.equal(restoredTickets, 1);

  const loginClock = setup(); let finishLoginDiscovery, loginTickets = 0;
  const newLogin = restoredHost(loginClock, async () => { loginTickets++; return publicTicket; });
  Object.assign(newLogin.state, { authenticated: false, account: 'fixture-account', password: 'fixture',
    HandleIncomingVehicleShortcut: () => {},
    DiscoverLanEndpoint: () => new Promise(resolve => { finishLoginDiscovery = resolve; }) });
  newLogin.state.authManager.login = newLogin.state.authManager.restore;
  const loggingIn = newLogin.state.SubmitLogin(); await flush();
  assert.equal(typeof finishLoginDiscovery, 'function');
  assert.equal(loginTickets, 1);
  assert.equal(newLogin.loads.at(-1), publicTicket);
  finishLoginDiscovery(); await loggingIn; await flush();
  assert.equal(loginTickets, 1);

  // 退出或切换账号后，迟到的路由发现不能重新连接当前 DSH 页面。
  let completeStaleDiscovery, completeStaleTicket, lateTickets = 0;
  const staleRestore = restoredHost(setup(), () => {
    lateTickets++;
    return new Promise(resolve => { completeStaleTicket = resolve; });
  });
  staleRestore.state.authenticated = false;
  staleRestore.state.DiscoverLanEndpoint = () => new Promise(resolve => { completeStaleDiscovery = resolve; });
  const restoring = staleRestore.state.RestoreSession(); await flush();
  assert.equal(lateTickets, 1);
  staleRestore.state.authenticationUiRevision++;
  completeStaleDiscovery(); await restoring; await flush();
  completeStaleTicket(stalePublicTicket); await flush();
  assert.equal(lateTickets, 1);
  assert.equal(staleRestore.loads.includes(stalePublicTicket), false);

  // 用户在其他标签等待账号恢复时，不预签发 DSH 票据。
  const warmClock = setup(); let initialTickets = 0;
  const earlyEntry = restoredHost(warmClock, async () => { initialTickets++; return publicTicket; });
  earlyEntry.state.authenticated = false;
  earlyEntry.state.currentIndex = 0;
  await earlyEntry.state.RestoreSession(); await flush();
  assert.equal(initialTickets, 0);
  assert.deepEqual(earlyEntry.preconnects, [[`${publicDshOrigin}/`, true, 1]]);
  assert.deepEqual(earlyEntry.loads, ['about:blank']);
  earlyEntry.state.OpenDshAfterAuthentication();
  assert.equal(earlyEntry.preconnects.length, 1);
  await warmClock.advance(15000);
  earlyEntry.state.dshForeground = false;
  earlyEntry.state.OpenDshAfterAuthentication();
  assert.equal(earlyEntry.preconnects.length, 1, 'background authentication must not preconnect');
  earlyEntry.state.dshForeground = true;
  earlyEntry.state.OpenDshAfterAuthentication();
  assert.equal(earlyEntry.preconnects.length, 2, 'a fresh connection can be warmed after the cooldown');
  assert.equal(initialTickets, 0);

  // 首次直连页面超时只换取一次公网票据，已挂载页面和旧网络结果不重放。
  const ipv6 = setup(); const ticketOptions = [];
  const direct = host(ipv6, async (...args) => { ticketOptions.push(args); return args[1] ? publicTicket : directTicket; });
  direct.state.ActiveServiceUrl = () => directUrl;
  direct.state.ipv6ModeActive = true;
  direct.state.dshWebController.runJavaScript = async () => '0';
  await direct.state.ConnectDshWeb();
  direct.setPage(directUrl + '/');
  await ipv6.advance(9999);
  assert.equal(direct.state.dshPublicFallback, false);
  assert.equal(ticketOptions.length, 1);
  await ipv6.advance(1);
  assert.equal(direct.state.dshPublicFallback, true);
  assert.deepEqual(ticketOptions.map(args => args[1]), [false, true]);
  assert.equal(direct.loads.at(-1), publicTicket);
  assert.equal(direct.state.ActiveServiceUrl(), directUrl);
  assert.deepEqual(direct.preconnects.map(args => args[0]), [`${directUrl}/`, `${publicDshOrigin}/`]);
  direct.setPage(directUrl + '/');
  assert.equal(direct.state.FallbackDshToPublic(), false);

  // 尚未取得票据时超时，也要取消旧请求并通过公网重新取票。
  const hangingDirect = setup(); let completeDirect;
  const beforeNavigation = host(hangingDirect, (secure, publicRoute) => publicRoute ? Promise.resolve(publicTicket) :
    new Promise(resolve => { completeDirect = resolve; }));
  beforeNavigation.state.ActiveServiceUrl = () => directUrl;
  beforeNavigation.state.ipv6ModeActive = true;
  const pendingDirect = beforeNavigation.state.ConnectDshWeb();
  const abandoned = beforeNavigation.state.dshTicketCancellation;
  await hangingDirect.advance(10000);
  assert.equal(abandoned.cancelled, true);
  assert.equal(beforeNavigation.loads.at(-1), publicTicket);
  completeDirect(directTicket); await pendingDirect;
  assert.equal(beforeNavigation.loads.includes(directTicket), false);

  // 正在启动的直连文档保留完整加载窗口，不因 React 尚未挂载就改用公网。
  const slowPage = setup(); const slowTicketOptions = [];
  const loadingDocument = host(slowPage, async (...args) => { slowTicketOptions.push(args); return directTicket; });
  loadingDocument.state.ActiveServiceUrl = () => directUrl;
  loadingDocument.state.ipv6ModeActive = true;
  loadingDocument.state.dshWebController.runJavaScript = async () => '3';
  await loadingDocument.state.ConnectDshWeb();
  loadingDocument.setPage(`${directUrl}/`);
  await slowPage.advance(10000);
  assert.equal(loadingDocument.state.dshWebDocumentReceived, true);
  assert.equal(loadingDocument.state.dshWebLoading, true);
  assert.equal(loadingDocument.state.dshPublicFallback, false);
  assert.equal(slowTicketOptions.length, 1);
  loadingDocument.state.dshWebController.runJavaScript = async () => '1';
  await slowPage.advance(250);
  assert.equal(loadingDocument.state.dshWebLoading, false);

  // 网络切回后，即使业务还走同一个公网地址，也会恢复已失败的 DSH 页面。
  const recovered = setup(); let recoveryTickets = 0;
  const onNetwork = host(recovered, async () => { recoveryTickets++; return publicTicket; });
  onNetwork.state.networkChangeTimerId = -1;
  onNetwork.state.DiscoverLanEndpoint = async () => {};
  onNetwork.state.dshWebFailed = true;
  onNetwork.state.ScheduleEndpointRediscovery(800, true);
  await recovered.advance(800);
  assert.equal(recoveryTickets, 1);
  onNetwork.state.dshWebFailed = true;
  onNetwork.state.dshWebNeedsAuth = true;
  onNetwork.state.ScheduleEndpointRediscovery(800, true);
  await recovered.advance(800);
  assert.equal(recoveryTickets, 1);

  direct.state.dshPublicFallback = false;
  direct.state.dshWebLoading = false;
  assert.equal(direct.state.FallbackDshToPublic(), false);
  direct.state.dshWebLoading = true;
  direct.setPage('https://other.example.test/');
  assert.equal(direct.state.FallbackDshToPublic(), false);

  // 网页 401 触发一次原地换票，业务地址变更后仍使用已挂载的公网源。
  const renewal = setup();
  const renewalTickets = [];
  let provideTicket;
  const mounted = mountedHost(renewal, (...args) => {
    renewalTickets.push(args);
    return new Promise(resolve => { provideTicket = resolve; });
  });
  mounted.state.ActiveServiceUrl = () => directUrl;
  const renewalScripts = sessionScriptMock(mounted.state, ['3', '1']);
  mounted.state.WatchDshWebSession(true);
  await renewal.advance(250);
  assert.equal(mounted.state.dshWebSessionExpired, true);
  assert.equal(renewalTickets.length, 1);
  assert.deepEqual(renewalTickets[0].slice(0, 2), [false, true]);
  mounted.state.CheckDshWebSession(true);
  mounted.state.WatchDshWebSession(true);
  await renewal.advance(250);
  assert.equal(renewalTickets.length, 1);
  provideTicket(publicTicket);
  await flush();
  assert.equal(renewalScripts.filter(script => script.includes(JSON.stringify(ticketPath))).length, 1);
  await renewal.advance(250);
  assert.equal(mounted.state.dshWebSessionExpired, false);
  assert.equal(mounted.state.dshWebRenewAt, renewal.now() + 20 * 60 * 1000);
  assert.equal(mounted.state.dshWebRenewRetryAvailable, false);
  assert.equal(mounted.state.dshWebController.getUrl(), publicDshPage);
  assert.deepEqual(mounted.loads, []);

  // 票据源不匹配时保留当前页面，在自动重试间隔内只允许用户手动换票。
  const cooldown = setup();
  let cooldownTickets = 0;
  const retry = mountedHost(cooldown, async () => ++cooldownTickets === 1 ? `${directUrl}${ticketPath}` : publicTicket);
  const retryScripts = sessionScriptMock(retry.state, ['3', '1']);
  retry.state.WatchDshWebSession(true);
  await cooldown.advance(250);
  assert.equal(cooldownTickets, 1);
  assert.equal(retry.state.dshWebRenewRetryAvailable, true);
  assert.equal(retryScripts.some(script => script.includes(JSON.stringify(ticketPath))), false);
  retry.state.WatchDshWebSession(true);
  await cooldown.advance(59000);
  assert.equal(cooldownTickets, 1);
  retry.state.CheckDshWebSession(true);
  await flush();
  await cooldown.advance(250);
  assert.equal(cooldownTickets, 2);
  assert.equal(retry.state.dshWebRenewRetryAvailable, false);
  assert.equal(retry.state.dshWebSessionExpired, false);
  assert.deepEqual(retry.loads, []);

  // 直连入口到达续期时间时保持当前可信源并直接换票。
  const proactive = setup();
  const proactiveTickets = [];
  const directMounted = mountedHost(proactive, async (...args) => {
    proactiveTickets.push(args);
    return `${directUrl}${ticketPath}`;
  }, `${directUrl}/session/fixture`, directUrl);
  directMounted.state.ActiveServiceUrl = () => directUrl;
  directMounted.state.dshWebRenewAt = proactive.now();
  sessionScriptMock(directMounted.state, ['1']);
  directMounted.state.CheckDshWebSession();
  await flush();
  await proactive.advance(250);
  assert.equal(proactiveTickets.length, 1);
  assert.deepEqual(proactiveTickets[0].slice(0, 2), [false, false]);
  assert.equal(directMounted.state.dshWebRenewAt, proactive.now() + 20 * 60 * 1000);
  assert.deepEqual(directMounted.loads, []);

  // 退出账号、导航或截止时间都使尚未返回的票据失效。
  for (const change of ['account', 'page', 'timeout']) {
    const staleClock = setup();
    let finishTicket;
    const stale = mountedHost(staleClock, () => new Promise(resolve => { finishTicket = resolve; }));
    const staleScripts = sessionScriptMock(stale.state, []);
    stale.state.CheckDshWebSession(true);
    const cancellation = stale.state.dshWebSessionCancellation;
    if (change === 'account') {
      stale.state.authenticationUiRevision++;
      stale.state.CloseDshWebSessionCheck();
    } else if (change === 'page') {
      stale.setPage(`${publicDshOrigin}/session/changed`);
    } else {
      await staleClock.advance(24000);
      assert.equal(stale.state.dshWebRenewRetryAvailable, true);
    }
    finishTicket(publicTicket);
    await flush();
    assert.equal(cancellation.cancelled, true, change);
    assert.equal(staleScripts.some(script => script.includes(JSON.stringify(ticketPath))), false, change);
    assert.deepEqual(stale.loads, [], change);
  }

  console.log('PASS: readiness, hung script timeout, retry isolation, leaving page, auth, fixed deadline, reentrant retry, DOM probe');
  console.log('PASS: real Index methods reject late tickets, allow retry after timeout, and isolate account changes');
  console.log('PASS: network and response failures are distinct from authentication failures');
  console.log('PASS: sign-in and restore overlap discovery, preserve in-flight public navigation and ignore stale restoration');
  console.log('PASS: initial IPv6 falls back after 10s without a document, preserving React startup once a document appears');
  console.log('PASS: mounted 401 recovery and manual retry stay in place, serialize tickets, respect cooldown, and ignore stale contexts');
})().catch(error => { console.error(error); process.exitCode = 1; });
