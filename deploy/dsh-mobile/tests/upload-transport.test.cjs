const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");

const source = fs.readFileSync(path.join(__dirname, "../upload.js"), "utf8");
const origin = "https://dsh-app.yuyutian.top";
const partsPath = "/_mytools/upload/part";
const statusPath = "/_mytools/upload/status";
const commitPath = "/_mytools/upload/commit";
const promptPath = "/api/session/prompt";
const filePath = "/api/fileUploads/upload";
const binaryPath = "/api/session/uploadFileBinary";
const partBytes = 262144;
const payload = JSON.stringify({ prompt: "你好🌍🙂".repeat(103000) });
const bytes = Buffer.from(payload);
const fetchOptions = {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: payload
};
const uploadId = "3d21c1ff-1e8e-437f-a397-802a639716e2";

function createClient(handle, options = {}) {
  const calls = [];
  const timers = new Map();
  function setTimer(handler, timeout) {
    const category = timeout >= 75000 ? timeout : "delay";
    if (!timers.has(category)) timers.set(category, new Set());
    const active = timers.get(category);
    const interval = category === 75000 ? (options.requestTimeout ?? 40) :
      category === 180000 ? (options.commitTimeout ?? 250) : 0;
    const token = setTimeout(() => {
      active.delete(token);
      handler();
    }, interval);
    active.add(token);
    return token;
  }

  function clearTimer(token) {
    clearTimeout(token);
    for (const active of timers.values()) active.delete(token);
  }

  const page = options.origin ?? origin;
  function nativeFetch(input, init) {
    const url = typeof input === "string" || input instanceof URL ? new URL(input, page) : new URL(input.url);
    const call = { input, init, path: url.pathname, url, thisValue: this };
    calls.push(call);
    return Promise.resolve().then(() => handle(call));
  }
  const window = { location: { origin: page }, fetch: nativeFetch, navigator: { userAgent: options.userAgent } };
  if ("uploadHook" in options) window.__DSH_FILE_UPLOAD__ = options.uploadHook;
  const context = vm.createContext({
    window, document: { baseURI: page + "/" }, AbortController, AbortSignal, Blob, DOMException, Headers,
    setTimeout: setTimer, clearTimeout: clearTimer, URL, TypeError, TextEncoder,
    crypto: { randomUUID: () => uploadId }
  });
  vm.runInContext(source, context);
  return { window, calls, nativeFetch, timers, install: () => vm.runInContext(source, context) };
}

function ready() {
  return new Response(null, { status: 204, headers: { "X-MyTools-Dsh-Upload": "1" } });
}

function partReceipt() {
  return new Response(null, { status: 204 });
}

function completed(status = 200) {
  return new Response('{"ok":true}', { status, headers: { "X-Dsh-Upload-State": "complete" } });
}

function pending() {
  return new Response(null, { status: 202, headers: { "X-Dsh-Upload-State": "pending" } });
}

function confirmedRelay(call) {
  if (call.path === statusPath) return ready();
  if (call.path === partsPath) return partReceipt();
  if (call.path === commitPath) return completed();
  assert.fail("Unexpected call " + call.path);
}

function waitForAbort(signal) {
  return new Promise((_, reject) => {
    if (signal.aborted) reject(signal.reason);
    else signal.addEventListener("abort", () => reject(signal.reason), { once: true });
  });
}

test("leaves other origins and unrelated fetch calls unchanged", async () => {
  const foreign = createClient(() => partReceipt(), { origin: "https://lan.example.test" });
  assert.equal(foreign.window.fetch, foreign.nativeFetch);

  const client = createClient(() => partReceipt());
  const wrapped = client.window.fetch;
  client.install();
  assert.equal(wrapped, client.window.fetch);
  const requests = [
    ["/api/session/uploadFileBinary", fetchOptions],
    ["/api/session/prompt?q=x", fetchOptions],
    ["https://example.test/api/session/prompt", fetchOptions],
    ["/api/session/prompt/other", fetchOptions],
    [promptPath, { ...fetchOptions, body: JSON.stringify({ prompt: "世界" }) }],
    [promptPath, { ...fetchOptions, body: "x".repeat(partBytes) }],
    [promptPath, { ...fetchOptions, method: "PUT" }],
    [promptPath, { ...fetchOptions, headers: { "Content-Type": "text/plain" } }],
    [promptPath, { ...fetchOptions, headers: { ...fetchOptions.headers, "X-Extra": "value" } }],
    [promptPath, { ...fetchOptions, credentials: "omit" }],
    [promptPath, { ...fetchOptions, mode: "no-cors" }],
    [promptPath, { ...fetchOptions, redirect: "manual" }],
    [promptPath, { ...fetchOptions, cache: "only-if-cached" }],
    [new Request(origin + promptPath, fetchOptions)]
  ];
  await Promise.all(requests.map(args => client.window.fetch(...args)));
  assert.equal(client.calls.length, requests.length);
  client.calls.forEach((call, index) => {
    assert.equal(call.input, requests[index][0]);
    assert.equal(call.init, requests[index][1]);
  });
});

test("only explicit capability absence falls back to the native request", async () => {
  for (const status of [404, 405, 501]) {
    const client = createClient(call => call.path === statusPath ?
      new Response(null, { status }) : completed());
    assert.equal((await client.window.fetch(promptPath, fetchOptions)).status, 200);
    assert.deepEqual(client.calls.map(({ path }) => path), [statusPath, promptPath]);
    assert.equal(client.calls[1].init, fetchOptions);
  }
  for (const status of [401, 403, 500, 503]) {
    const client = createClient(() => new Response(null, { status }));
    assert.equal((await client.window.fetch(promptPath, fetchOptions)).status, status);
    assert.ok(client.calls.every(call => call.path === statusPath));
    assert.equal(client.calls.length, status === 503 ? 3 : 1);
  }
  const malformed = createClient(() => new Response(null, { status: 204 }));
  await assert.rejects(malformed.window.fetch(promptPath, fetchOptions), /capability response/);
  assert.deepEqual(malformed.calls.map(({ path }) => path), [statusPath]);
});

test("retries a stalled part, preserving exact UTF-8 bytes and the four-part limit", async () => {
  const stored = new Map();
  const seen = new Map();
  let parallel = 0;
  let peak = 0;
  const client = createClient(async call => {
    if (call.path === statusPath) return ready();
    const headers = new Headers(call.init.headers);
    assert.equal(headers.get("X-Dsh-Upload-Id"), uploadId);
    assert.equal(Number(headers.get("X-Dsh-Upload-Size")), bytes.length);
    if (call.path === commitPath) {
      assert.equal(headers.get("X-Dsh-Upload-Target"), "prompt");
      const assembled = Buffer.concat([...stored.keys()].sort((a, b) => a - b).map(index => stored.get(index)));
      assert.deepEqual(assembled, bytes);
      return completed();
    }
    assert.equal(call.path, partsPath);
    assert.equal(call.init.method, "PUT");
    assert.equal(headers.get("Content-Type"), "application/octet-stream");
    assert.equal(call.init.credentials, "same-origin");
    parallel++;
    peak = Math.max(peak, parallel);
    try {
      const index = Number(headers.get("X-Dsh-Upload-Index"));
      const previous = seen.get(index);
      const chunk = Buffer.from(await call.init.body.arrayBuffer());
      if (previous) assert.deepEqual(previous, chunk);
      seen.set(index, chunk);
      if (index === 0 && !previous) return await waitForAbort(call.init.signal);
      if (index === 1 && !previous) return new Response(null, { status: 502 });
      assert.equal(chunk.length, Math.min(partBytes, bytes.length - index * partBytes));
      stored.set(index, chunk);
      return partReceipt();
    } finally {
      parallel--;
    }
  });
  const response = await client.window.fetch(promptPath, fetchOptions);
  assert.deepEqual(await response.json(), { ok: true });
  assert.equal(peak, 4);
  assert.equal(parallel, 0);
  const partCalls = client.calls.filter(({ path }) => path === partsPath);
  assert.equal(partCalls.length, stored.size + 2);
  assert.equal(client.calls.at(-1).path, commitPath);
});

test("polls asynchronous commit with the same ID, preserving the eventual response", async () => {
  let commits = 0;
  const client = createClient(call => {
    if (call.path !== commitPath) return confirmedRelay(call);
    assert.equal(call.init.method, "POST");
    assert.equal(call.init.headers["X-Dsh-Upload-Target"], "file-json");
    assert.equal(call.init.headers["X-Dsh-Upload-Id"], uploadId);
    commits++;
    if (commits === 1) return pending();
    if (commits === 2) throw new TypeError("Connection interrupted");
    // 上游自己的 202 是最终响应，必须和代理的 pending 区分。
    return completed(202);
  });
  const response = await client.window.fetch(new URL(origin + filePath), fetchOptions);
  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), { ok: true });
  assert.equal(commits, 3);
});

test("does not replay after an ambiguous commit, permanent part error or upstream terminal error", async () => {
  const unknown = createClient(call => call.path === commitPath ?
    new Response("unknown", { status: 409, headers: { "X-Dsh-Upload-State": "unknown" } }) : confirmedRelay(call));
  const result = await unknown.window.fetch(promptPath, fetchOptions);
  assert.equal(result.status, 409);
  assert.equal(await result.text(), "unknown");
  assert.equal(unknown.calls.filter(call => call.path === commitPath).length, 1);
  assert.ok(unknown.calls.every(call => call.path !== promptPath));

  const terminal = createClient(call => call.path === commitPath ? completed(503) : confirmedRelay(call));
  assert.equal((await terminal.window.fetch(promptPath, fetchOptions)).status, 503);
  assert.equal(terminal.calls.filter(call => call.path === commitPath).length, 1);

  const failure = createClient(call => call.path === partsPath ?
    new Response(null, { status: 500 }) : confirmedRelay(call));
  await assert.rejects(failure.window.fetch(promptPath, fetchOptions), /HTTP 500/);
  assert.equal(failure.calls.filter(call => call.path === partsPath).length, 4);
  assert.ok(failure.calls.every(call => call.path !== commitPath && call.path !== promptPath));
});

test("aborts outstanding requests on caller cancellation without retry or commit", async () => {
  const caller = new AbortController();
  const signals = [];
  const client = createClient(call => {
    if (call.path === statusPath) return ready();
    assert.equal(call.path, partsPath);
    signals.push(call.init.signal);
    if (signals.length === 4) caller.abort(new DOMException("Cancelled by caller", "AbortError"));
    return waitForAbort(call.init.signal);
  });
  await assert.rejects(client.window.fetch(promptPath, { ...fetchOptions, signal: caller.signal }),
    /Cancelled by caller/);
  assert.equal(signals.length, 4);
  assert.ok(signals.every(signal => signal.aborted));
  assert.ok(client.calls.every(call => call.path !== commitPath && call.path !== promptPath));
});

test("expires a continuously pending commit without restarting it", async () => {
  const client = createClient(call => call.path === commitPath ? pending() : confirmedRelay(call),
    { commitTimeout: 50 });
  await assert.rejects(client.window.fetch(promptPath, fetchOptions),
    { name: "TimeoutError", message: "DSH upload timed out." });
  const commits = client.calls.filter(call => call.path === commitPath);
  assert.ok(commits.length > 0);
  assert.ok(commits.every(call => call.init.headers["X-Dsh-Upload-Id"] === uploadId));
  assert.ok(client.calls.every(call => call.path !== promptPath));
  assert.ok([...client.timers.values()].every(active => active.size === 0));
});

test("installs the stock binary hook only for HarmonyOS ArkWeb and preserves existing hooks", () => {
  const desktop = createClient(confirmedRelay, { userAgent: "Mozilla/5.0 Chrome/149.0 Safari" });
  assert.equal("__DSH_FILE_UPLOAD__" in desktop.window, false);

  for (const userAgent of ["Mozilla/5.0 HarmonyOS 6.1", "Mozilla/5.0 ArkWeb/5.1"]) {
    const existing = { fetch: () => {}, setting: 12 };
    const before = createClient(confirmedRelay, { userAgent, uploadHook: existing });
    assert.equal(before.window.__DSH_FILE_UPLOAD__, existing);
    const client = createClient(confirmedRelay, { userAgent });
    assert.equal(typeof client.window.__DSH_FILE_UPLOAD__.fetch, "function");
  }
});

test("stages large binary bytes with the original allowed file metadata", async () => {
  const chunks = new Map();
  const originalBytes = Buffer.from(Array.from({ length: 2 * partBytes + 8 }, (_, index) => index % 256));
  const body = new Blob([originalBytes], { type: "application/pdf" });
  const client = createClient(async call => {
    if (call.path === statusPath) return ready();
    const headers = new Headers(call.init.headers);
    assert.equal(headers.get("X-Dsh-Upload-Id"), uploadId);
    assert.equal(Number(headers.get("X-Dsh-Upload-Size")), originalBytes.length);
    if (call.path === partsPath) {
      assert.equal(headers.get("X-Dsh-Upload-Query"), null);
      const index = Number(headers.get("X-Dsh-Upload-Index"));
      chunks.set(index, Buffer.from(await call.init.body.arrayBuffer()));
      return partReceipt();
    }
    assert.equal(call.path, commitPath);
    assert.equal(headers.get("X-Dsh-Upload-Target"), "file-binary");
    assert.equal(headers.get("X-Dsh-Upload-Query"), "sessionId=test-session&name=%E4%BD%A0%E5%A5%BD+%2B.pdf&mediaType=application%2Fpdf");
    assert.deepEqual(Buffer.concat([chunks.get(0), chunks.get(1), chunks.get(2)]), originalBytes);
    return completed(201);
  }, { userAgent: "Mozilla/5.0 ArkWeb/5.1" });
  const result = await client.window.__DSH_FILE_UPLOAD__.fetch(
    "api/session/uploadFileBinary?sessionId=test-session&name=%E4%BD%A0%E5%A5%BD%20%2B.pdf&mediaType=application%2Fpdf",
    { method: "POST", headers: { "Content-Type": "application/octet-stream" }, body });
  assert.equal(result.status, 201);
  assert.equal(await result.text(), '{"ok":true}');
  assert.equal(chunks.size, 3);
  assert.ok(client.calls.every(call => call.path !== binaryPath));
});

test("binary hook passes small files, streams and unsupported metadata to native fetch unchanged", async () => {
  const client = createClient(() => partReceipt(), { userAgent: "Mozilla/5.0 HarmonyOS" });
  const { fetch } = client.window.__DSH_FILE_UPLOAD__;
  const regular = {
    method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: new Blob([bytes])
  };
  const remote = binaryPath + "?sessionId=test-session";
  const requests = [
    [remote, { ...regular, body: new Blob(["small"]) }],
    [remote, { ...regular, body: new ReadableStream(), duplex: "half" }],
    [binaryPath + "?name=test.txt", regular],
    [remote + "&sessionId=also-test-session", regular],
    [remote + "&token=unexpected", regular],
    [remote + "&mediaType=not-a-media-type", regular],
    [remote + "&name=%0Dembedded", regular],
    [remote + "&name=%FF", regular],
    [remote + "&name=" + "x".repeat(1025), regular],
    ["https://example.test" + remote, regular]
  ];
  await Promise.all(requests.map(args => fetch(...args)));
  assert.equal(client.calls.length, requests.length);
  client.calls.forEach((call, index) => {
    assert.equal(call.input, requests[index][0]);
    assert.equal(call.init, requests[index][1]);
  });
});
