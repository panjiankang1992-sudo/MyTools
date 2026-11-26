const assert = require('node:assert/strict');
const { setImmediate: flush } = require('node:timers/promises');
const vm = require('node:vm');
const { DshWebSessionRenewal } = require(process.argv[2]);

const publicPage = 'https://dsh.example.test/session/fixture';
const lanPage = 'http://192.168.1.7:23201/session/fixture';
const ticketPath = '/_mytools/connect?ticket=' + 'A_1-'.repeat(10) + 'ABC';
const statusPath = '/_mytools/session-status';
const redirect = { type: 'opaqueredirect', status: 0 };

function createPage(page = publicPage) {
  let href = page;
  let timerId = 0;
  const timers = new Map();
  const requests = [];
  const navigations = [];
  const editor = { value: 'Unsent draft' };
  const location = {
    get href() { return href; },
    set href(value) { navigations.push(value); },
    get origin() { return new URL(href).origin; },
    assign(value) { navigations.push(value); },
    replace(value) { navigations.push(value); },
    reload() { navigations.push(href); }
  };
  const window = { location, open: value => navigations.push(value) };
  const document = { baseURI: 'https://other.example.test/', querySelector: () => editor };
  Object.defineProperty(window, 'location', { get: () => location, set: value => navigations.push(value) });
  const context = vm.createContext({
    window, location, document, URL, AbortController,
    setTimeout(fn, delay) {
      const id = ++timerId;
      timers.set(id, { fn, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    fetch(url, options) {
      return new Promise((resolve, reject) => {
        requests.push({ url: new URL(url, document.baseURI), options, resolve, reject });
        if (options.signal.aborted) {
          reject(options.signal.reason);
        } else {
          options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
        }
      });
    }
  });
  const run = script => vm.runInContext(script, context);
  return {
    requests, timers, navigations, editor, location,
    changeRoute(next) { href = next; },
    exchange(attempt, path = ticketPath, expectedPage = page) {
      return run(DshWebSessionRenewal.exchangeScript(expectedPage, path, attempt));
    },
    probe(attempt, expectedPage = page) {
      return run(DshWebSessionRenewal.probeScript(expectedPage, attempt));
    },
    result(attempt, expectedPage = page) {
      return run(DshWebSessionRenewal.resultScript(expectedPage, attempt));
    },
    cancel(attempt) {
      return run(DshWebSessionRenewal.cancelScript(attempt));
    }
  };
}

function checkRequest(request, expectedUrl, redirectMode) {
  assert.equal(request.url.href, expectedUrl);
  assert.equal(request.options.method ?? 'GET', 'GET');
  assert.equal(request.options.credentials, 'same-origin');
  assert.equal(request.options.mode, 'same-origin');
  assert.equal(request.options.cache, 'no-store');
  assert.equal(request.options.referrerPolicy, 'no-referrer');
  assert.equal(request.options.redirect, redirectMode);
  assert.ok(request.options.signal instanceof AbortSignal);
}

(async () => {
  for (const page of [publicPage, lanPage]) {
    const web = createPage(page);
    const origin = new URL(page).origin;
    assert.equal(web.exchange(1), 1);
    assert.equal(web.result(1), 0);
    assert.equal(web.requests.length, 1);
    checkRequest(web.requests[0], origin + ticketPath, 'manual');

    web.requests[0].resolve(redirect);
    await flush();
    assert.equal(web.requests.length, 2);
    checkRequest(web.requests[1], origin + statusPath, 'error');
    assert.equal(web.result(1), 0, 'An opaque redirect does not prove that the session is valid');

    web.requests[1].resolve({ status: 204 });
    await flush();
    assert.equal(web.result(1), 1);
    assert.notEqual(web.result(1), 1, 'A completed result is consumed once');
    assert.equal(web.location.href, page);
    assert.deepEqual(web.navigations, []);
    assert.equal(web.editor.value, 'Unsent draft');
    assert.equal(web.timers.size, 0);
  }

  for (const [status, result] of [[204, 1], [401, 3], [503, 2]]) {
    const web = createPage();
    assert.equal(web.probe(8), 1);
    assert.equal(web.result(8), 0);
    checkRequest(web.requests[0], new URL(publicPage).origin + statusPath, 'error');
    web.requests[0].resolve({ status });
    await flush();
    assert.equal(web.result(8), result);
    assert.equal(web.timers.size, 0);
  }

  const invalid = createPage();
  for (const path of ['https://other.example.test' + ticketPath, '//' + ticketPath.slice(1),
    ticketPath + '&extra=value', ticketPath + '#fragment', ticketPath.slice(0, -1),
    ticketPath.replace('ticket', 'token'), '/_mytools/connect?ticket=%41' + 'A'.repeat(42)]) {
    assert.equal(invalid.exchange(4, path), 0, path);
  }
  assert.equal(invalid.exchange(5, ticketPath, publicPage + '/other'), 0);
  assert.equal(invalid.probe(5, publicPage + '/other'), 0);
  assert.equal(invalid.requests.length, 0);
  assert.equal(invalid.timers.size, 0);

  const denied = createPage();
  denied.exchange(6);
  denied.requests[0].resolve({ type: 'basic', status: 401 });
  await flush();
  assert.equal(denied.result(6), 2);
  assert.equal(denied.requests.length, 1);
  denied.exchange(7);
  denied.requests[1].resolve(redirect);
  await flush();
  denied.requests[2].resolve({ status: 401 });
  await flush();
  assert.equal(denied.result(7), 2);
  assert.deepEqual(denied.navigations, []);

  const stale = createPage();
  stale.probe(10);
  stale.requests[0].resolve({ status: 204 });
  stale.cancel(10);
  assert.equal(stale.requests[0].options.signal.aborted, true);
  stale.probe(11);
  stale.cancel(10);
  assert.equal(stale.requests[1].options.signal.aborted, false);
  await flush();
  assert.notEqual(stale.result(10), 1);
  assert.equal(stale.result(11), 0, 'The stale response cannot complete the active attempt');
  stale.requests[1].resolve({ status: 401 });
  await flush();
  assert.equal(stale.result(11), 3);
  assert.equal(stale.timers.size, 0);

  const moved = createPage();
  moved.exchange(20);
  moved.changeRoute(publicPage + '/other');
  moved.requests[0].resolve(redirect);
  await flush();
  assert.notEqual(moved.result(20), 1);
  assert.equal(moved.requests.length, 1);
  assert.equal(moved.timers.size, 0);

  const timeout = createPage();
  timeout.probe(30);
  assert.equal(timeout.timers.size, 1);
  const [{ fn, delay }] = timeout.timers.values();
  assert.ok(delay > 0);
  fn();
  assert.equal(timeout.requests[0].options.signal.aborted, true);
  await flush();
  assert.equal(timeout.result(30), 2);
  assert.equal(timeout.timers.size, 0);

  console.log('DSH web session renewal tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
