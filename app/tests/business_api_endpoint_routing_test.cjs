const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.argv[3]);
const sourceRoot = path.join(process.argv[2], 'entry/src/main/ets');
const publicRoot = 'https://mytools.example';
const lanRoot = 'http://192.168.1.2:23201';
const id = '123e4567-e89b-12d3-a456-426614174000';
const ticket = '0123456789abcdef0123456789abcdef';
const events = [];
let responseData;

// 系统网络与会话在测试中隔离，执行实际API封装和响应校验逻辑。
const auth = {
  active: publicRoot,
  normalizeBusinessBaseUrl: value => value.replace(/\/$/, ''),
  resolveBusinessBaseUrl() { return this.active; },
  async validAccessToken() { return 'test-token'; },
  async forceRefresh() { return 'test-token'; }
};
class AuthorizedApiClient {
  constructor(base, manager) { this.base = base; this.auth = manager; }
  async post(apiPath) { return this.reply(apiPath); }
  async postJson(apiPath) { return this.reply(apiPath); }
  reply(apiPath) {
    events.push(this.auth.resolveBusinessBaseUrl(this.base) + apiPath);
    return { code: '0000', data: structuredClone(responseData) };
  }
}
const http = {
  RequestMethod: { POST: 'POST', PUT: 'PUT' }, HttpDataType: { STRING: 'STRING' },
  createHttp() {
    return {
      on() {}, off() {}, destroy() {},
      async request(address) {
        events.push(address);
        return { responseCode: 200, result: JSON.stringify({ code: '0000', data: {} }) };
      }
    };
  }
};
const systemRequest = {
  async uploadFile(_context, config) {
    events.push(config.url);
    const handlers = {};
    return {
      off() {},
      on(name, callback) {
        handlers[name] = callback;
        if (name === 'fail') queueMicrotask(() => {
          handlers.headerReceive({ status: 200, 'content-type': 'application/json' });
          handlers.complete([]);
        });
      }
    };
  }
};
const util = { TextEncoder: class { encodeInto(value) { return new TextEncoder().encode(value); } } };
const cache = new Map();
function load(relativeFile) {
  const filename = path.resolve(sourceRoot, relativeFile);
  if (cache.has(filename)) return cache.get(filename).exports;
  const module = { exports: {} };
  cache.set(filename, module);
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText;
  function localRequire(name) {
    if (name.endsWith('/AuthorizedApiClient')) return { AuthorizedApiClient };
    if (name.endsWith('/AuthSessionManager')) return { AuthSessionManager: class {} };
    if (name === '@kit.NetworkKit') return { http };
    if (name === '@kit.ArkTS') return { util };
    if (name === '@ohos.util') return { default: util, ...util };
    if (name === '@ohos.request') return { default: systemRequest, ...systemRequest };
    if (name.startsWith('@')) return {};
    return load(path.resolve(path.dirname(filename), name + '.ets'));
  }
  new Function('require', 'module', 'exports', compiled)(localRequire, module, module.exports);
  return module.exports;
}

async function main() {
  const drive = new (load('features/drive/DriveApi.ets').DriveApi)(publicRoot, auth);
  const catalog = new (load('features/media/MediaCatalogApi.ets').MediaCatalogApi)(publicRoot, auth);
  const remote = new (load('features/media/RemoteMediaApi.ets').RemoteMediaApi)(publicRoot, auth);
  const audio = new (load('features/reader/AudiobookApi.ets').AudiobookApi)(publicRoot, auth);
  const video = new (load('features/tools/VideoGenerationApi.ets').VideoGenerationApi)(publicRoot, auth);
  const pdf = new (load('features/tools/pdf/PdfToolsApi.ets').PdfToolsApi)(publicRoot, auth);
  const download = new (load('features/tools/download/DownloadToolsApi.ets').DownloadToolsApi)(publicRoot, auth);
  const ebook = new (load('features/reader/EbookImportApi.ets').EbookImportApi)(publicRoot, auth);
  const expiresAt = new Date(Date.now() + 600000).toISOString();
  const source = { id: 'library', type: 'MYTOOLS_LIBRARY' };
  for (const root of [publicRoot, lanRoot, 'https://direct6.example.test', publicRoot]) {
    auth.active = root;
    responseData = { ticket, streamPath: `/api/app/v1/drive-tickets/${ticket}`, expiresAt,
      name: 'audio.mp3', mimeType: 'audio/mpeg', sizeBytes: 1 };
    assert.equal((await drive.openTicket(id, '/audio.mp3')).url, root + responseData.streamPath);
    responseData = { ticket, streamPath: `/api/app/v1/media/tickets/${ticket}`, expiresAt };
    assert.equal((await catalog.playTicket(id)).url, root + responseData.streamPath);
    assert.equal((await remote.resolvePlayback(source, `/media/${id}`)).url, root + responseData.streamPath);
    responseData = { ticket, streamPath: `/api/app/v1/audiobook-playback/tickets/${ticket}`, expiresAt };
    assert.equal((await audio.playback(id, 0)).url, root + responseData.streamPath);
    responseData = { ticket, streamPath: `/api/app/v1/audiobook-voice-preview/tickets/${ticket}`, expiresAt };
    assert.equal((await audio.voicePreview(id, { previewAvailable: true, provider: 'TEST', voiceType: 'narrator' })).url,
      root + responseData.streamPath);
    responseData = { ticket, downloadPath: `/api/app/v1/audiobook-export/tickets/${ticket}`, expiresAt };
    assert.equal((await audio.exportDownload(id, id)).url, root + responseData.downloadPath);
    responseData = { token: ticket, url: `/api/app/v1/video-generation/tickets/${ticket}` };
    assert.equal((await video.videoTicket(id)).url, root + responseData.url);
    responseData = { token: ticket, url: `/api/app/v1/video-generation/tickets/${ticket}?redirect=1` };
    if (root === lanRoot) await assert.rejects(() => video.videoTicket(id));
    await pdf.write('/plans', '{}', 'test-key');
    assert.equal(events.at(-1), `${root}/api/app/v1/pdf/plans`);
    await download.write('/messages', '{}', 'test-key');
    assert.equal(events.at(-1), `${root}/api/app/v1/download-tools/messages`);
    await remote.uploadFileOnce({}, '1', '/', 'file://test', 'test.epub', 'test-token');
    assert.equal(events.at(-1), `${root}/api/cloud/file?accountId=1`);
    await ebook.uploadOnce({}, 'file://test', 'test.epub', 'test-token');
    assert.equal(events.at(-1), `${root}/api/ebooks/import/upload`);
  }
  console.log('Business API endpoint routing tests passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
