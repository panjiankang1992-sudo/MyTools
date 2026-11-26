const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = 'app/entry/src/main/ets/';
function extract(file, name) {
  const source = fs.readFileSync(root + file, 'utf8');
  const match = new RegExp(`  private (?:async )?${name}\\(`).exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf('\n  }', match.index) + 4);
}
function evaluate(source, globals = {}) {
  const context = vm.createContext({ console, ...globals });
  vm.runInContext(ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, context);
  return context;
}
const layoutSource = fs.readFileSync(root + 'features/media/MediaGalleryLayoutPolicy.ets', 'utf8')
  .replaceAll('export ', '');
const layout = new (evaluate(layoutSource + '\nglobalThis.Policy=MediaGalleryLayoutPolicy;').Policy)();
const items = Array.from({ length: 5 }, (_, i) => ({ itemId: String(i), directoryId: 'day' }));
layout.rememberSize('0', 100, 300);
layout.rememberSize('1', 300, 100);
layout.rememberSize('2', 300, 100);
let placements = layout.reconcile(items, []);
assert.deepEqual(Array.from(placements, x => x.column), [0, 1, 1, 1, 1], 'Shorter column absorbs landscape items');
assert.equal(placements[0].aspectRatio, 1 / 3);
layout.rememberSize('0', 200, 100);
const updated = layout.reconcile(items, placements);
assert.deepEqual(Array.from(updated, x => x.column), Array.from(placements, x => x.column), 'Late dimensions never move existing cards across columns');
assert.equal(updated[0].aspectRatio, 2);
const appended = layout.reconcile([...items, { itemId: '5', directoryId: 'day' },
  { itemId: '6', directoryId: 'other-day' }], updated);
assert.equal(appended[5].column, 0, 'New cards use current column heights');
assert.equal(appended[6].column, 0, 'Each group starts an independent layout');
for (const size of [[0, 100], [100, -1], [NaN, 20], [20, Infinity]]) {
  assert.equal(layout.rememberSize('bad', ...size), false);
}
layout.rememberSize('long', 10, 10000);
assert.equal(layout.reconcile([{ itemId: 'long', directoryId: 'day' }], [])[0].aspectRatio, 0.25);
assert.equal(layout.reconcile([], placements).length, 0, 'Refresh removes obsolete cards');

assert.equal(layout.returnScrollDelta(50, 160, 600), 0, 'Visible current card stays put');
assert.equal(layout.returnScrollDelta(2500, 200, 600), 2300, 'Next offscreen card moves to center');
assert.equal(layout.returnScrollDelta(-900, 200, 600), -1100, 'Previous offscreen card moves to center');
assert.equal(layout.returnScrollDelta(-80, 900, 600), 0, 'Tall card already fills viewport');
assert.equal(layout.returnScrollDelta(450, 900, 600), 450, 'Tall partial card aligns to top');

let response, encoded = 0;
class Encoder {
  encodeInto(text) { encoded++; return text.length === 0 ? undefined : new TextEncoder().encode(text); }
}
class ResponseError extends Error {}
const requestContext = evaluate(`class Client { ${extract('shared/network/AuthorizedApiClient.ets', 'request')} }; globalThis.Client=Client;`, {
  http: { createHttp: () => ({ on() {}, off() {}, destroy() {}, request: async () => response }) },
  util: { TextEncoder: Encoder }, BusinessResponseError: ResponseError, BusinessTransportError: ResponseError
});
const client = new requestContext.Client();
Object.assign(client, { authManager: { publicBusinessBaseUrl: () => 'https://public.test' },
  throwIfCancelled() {}, ResponseText: result => typeof result === 'string' ? result : JSON.stringify(result),
  policy: { responseEnvelope: (status, text) => status === 204 ? { code: '0000' } : JSON.parse(text) } });

const pageContext = evaluate(`class Page { ${['MediaVideoDetailProgress', 'PlayMediaVideoDetail']
  .map(name => extract('pages/Index.ets', name)).join('\n')} }; globalThis.Page=Page;`);
async function run() {
  for (const body of [undefined, null, '', {}, new ArrayBuffer(0)]) {
    response = { responseCode: 204, result: body };
    const result = await client.request('https://public.test', '/api/app/v1/media/items/fixture', 'DELETE', 'test');
    assert.equal(result.status, 204);
    assert.equal(result.envelope.code, '0000');
  }
  assert.equal(encoded, 0, '204 bypasses the native empty encoder and decoder for every SDK body shape');
  response = { responseCode: 200, result: '{"code":"0000"}' };
  assert.equal((await client.request('https://public.test', '/api/app/v1/media/items/fixture', 'GET', 'test')).envelope.code, '0000');
  assert.equal(encoded, 1, 'JSON responses still undergo normal decoding');

  const p = new pageContext.Page();
  const id = '12345678-1234-4123-8123-123456789abc';
  let opened;
  Object.assign(p, { mediaVideoDetail: { videoId: id, name: 'fixture.mp4', format: 'mp4', sizeBytes: 100, directoryName: 'test' },
    CatalogLocalMediaSource: () => ({ id: 'library' }), selectedMediaSourceId: 'old-source', localAccountScope: 'owner',
    mediaPlaybackProgress: [{ owner: 'owner', key: `7:library/media/${id}`, positionMs: 12000 }],
    mediaStatus: '', OpenRemoteMedia: async (item, resume) => { opened = { item, resume }; } });
  await p.PlayMediaVideoDetail();
  assert.equal(opened.item.path, `/media/${id}`);
  assert.equal(opened.resume, 12000, 'Detail uses the same catalog history and playback route');
  p.mediaVideoDetail = undefined; opened = undefined; await p.PlayMediaVideoDetail();
  assert.equal(opened, undefined, 'Closed detail cannot start playback');
  console.log('Media repair behavior tests passed (layout, empty DELETE, shared video entry)');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
