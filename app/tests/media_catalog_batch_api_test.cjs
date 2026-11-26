const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync('app/entry/src/main/ets/features/media/MediaCatalogApi.ets', 'utf8');
const calls = [];
let response = { items: [], total: 600, tags: [], directories: [] };
class Client { async get(url, cancellation) { calls.push({ url, cancellation }); return { data: response }; } }
const moduleValue = { exports: {} };
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS,
  target: ts.ScriptTarget.ES2020 } }).outputText;
new Function('require', 'module', 'exports', compiled)(name => {
  if (name.endsWith('/AuthorizedApiClient')) return { AuthorizedApiClient: Client };
  if (name.endsWith('/RemoteMediaResponseNormalizer')) return { RemoteMediaResponseNormalizer: class {} };
  return {};
}, moduleValue, moduleValue.exports);
(async () => {
  const api = new moduleValue.exports.MediaCatalogApi('https://example.test', { normalizeBusinessBaseUrl: x => x });
  for (const [mode, prefix] of [['gallery', 'image/'], ['video', 'video/'], ['audio', 'audio/']]) {
    const token = {};
    const result = await api.browsePage(mode, 'abc123', 'a&b', ' test ', 2, true, token);
    assert.equal(result.pageSize, 6); assert.equal(result.page, 2); assert.equal(result.total, 600);
    const query = new URL(calls.at(-1).url, 'https://example.test').searchParams;
    assert.equal(query.get('pageSize'), '6'); assert.equal(query.get('page'), '2');
    assert.equal(query.get('mimePrefix'), prefix); assert.equal(query.get('directoryId'), 'abc123');
    assert.equal(query.get('tag'), 'a&b'); assert.equal(query.get('keyword'), 'test');
    assert.equal(query.get('excludeAdult'), 'true'); assert.equal(calls.at(-1).cancellation, token);
  }
  assert.equal(calls.length, 3, 'One catalog request per batch; no filter or full-library walk');
  await api.gallery('abc123', 'a&b', ' test ', 2, true);
  assert.equal(calls.length, 4, 'Legacy 100-item cache must not collide with 6-item browse cache');
  response = { ...response, items: new Array(7).fill({}) };
  await assert.rejects(api.browsePage('gallery', '', '', '', 1, false), 'Oversized batch must be rejected');
  console.log('Media catalog batch API tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
