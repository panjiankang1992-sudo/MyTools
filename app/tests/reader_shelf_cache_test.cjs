const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const files = new Map();
let now = 172800000;
const fakeFs = {
  accessSync: p => files.has(p), mkdirSync: p => files.set(p, { size: 0, mtime: 0 }),
  statSync: p => { if (!files.has(p)) throw Error('missing'); return files.get(p); },
  copyFileSync: (a, b) => files.set(b, { ...fakeFs.statSync(a), mtime: now }),
  renameSync: (a, b) => { files.set(b, files.get(a)); files.delete(a); },
  unlinkSync: p => files.delete(p),
  listFileSync: p => [...files.keys()].filter(k => k.startsWith(p + '/')).map(k => k.slice(p.length + 1))
};
const source = fs.readFileSync('app/entry/src/main/ets/features/reader/ReaderShelfFileCache.ets', 'utf8')
  .replace(/^import .*;\n/gm, '').replace('export class', 'class');
const ctx = { fs: fakeFs, Date: { now: () => now }, util: { generateRandomUUID: () => 'unique' },
  DigestTool: class { async digestText(value) { return crypto.createHash('sha256').update(value).digest('hex'); } } };
vm.createContext(ctx);
vm.runInContext(ts.transpileModule(source + '\nglobalThis.Cache = ReaderShelfFileCache;', {
  compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, ctx);
(async () => {
  const context = { cacheDir: '/private-cache', filesDir: '/private-files' };
  const book = { id: 'book', sourceId: 'source', resourceUri: '/book.txt', cacheVersion: 'v1', format: 'txt' };
  const cache = new ctx.Cache();
  assert.equal(await cache.prepare(context, 'owner-a', 'endpoint', book), '');
  files.set('/download', { size: 1000, mtime: now }); cache.save('/download');
  const hit = await cache.prepare(context, 'owner-a', 'endpoint', book);
  assert.ok(hit.startsWith(context.filesDir + '/'));
  assert.ok(hit.endsWith('.txt'));
  assert.equal(cache.available(), true);
  assert.equal(await new ctx.Cache().prepare(context, 'owner-b', 'endpoint', book), '');
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'other', book), '');
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', { ...book, cacheVersion: 'v2' }), '');
  cache.invalidate(); assert.equal(files.has(hit), false);
  cache.save('/download'); now += 86400000;
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', book), hit);
  now += 30 * 86400000;
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', book), hit);
  const legacyBook = { ...book, id: 'legacy' };
  const legacyKey = JSON.stringify(['owner-a', 'endpoint', legacyBook.id, legacyBook.sourceId,
    legacyBook.resourceUri, legacyBook.cacheVersion, legacyBook.format, Math.floor(now / 86400000)]);
  const legacyPath = `${context.cacheDir}/reader-shelf-files-v1/${crypto.createHash('sha256').update(legacyKey).digest('hex')}.txt`;
  files.set(legacyPath, { size: 1000, mtime: now });
  const migrated = await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', legacyBook);
  assert.ok(migrated.endsWith('.txt'));
  assert.notEqual(migrated, legacyPath);
  assert.equal(files.has(legacyPath), false);
  now += 86400000;
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', legacyBook), migrated);
  files.set(migrated, { size: 0, mtime: now });
  assert.equal(await new ctx.Cache().prepare(context, 'owner-a', 'endpoint', legacyBook), '');
  assert.equal(files.has(migrated), false);
  for (let i = 0; i < 12; i++) {
    await cache.prepare(context, 'owner-a', 'endpoint', { ...book, id: String(i) }); cache.save('/download');
  }
  assert.equal([...files.keys()].filter(p => /[a-f0-9]{64}\.txt$/.test(p)).length, 13);
  await cache.prepare(context, 'owner-a', 'endpoint', { ...book, id: 'oversize' });
  files.set('/large', { size: 501 * 1024 * 1024, mtime: now }); cache.save('/large');
  assert.equal(await cache.prepare(context, 'owner-a', 'endpoint', { ...book, id: 'oversize' }), '');
  const index = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
  assert.match(index, /@State bookModeIndex: number = 3/);
  assert.match(index, /ForEach\(\[3, 0, 1, 2\]/);
  assert.match(index, /if \(this.bookModeIndex === 3\) return this.books/);
  assert.match(index, /return \[origin\]\.concat\(ordered\)/);
  assert.ok(index.indexOf('const cachedFile = await loader.shelfFileCache.prepare') < index.indexOf('descriptor = await api.resolveBookPlayback'));
  console.log('Shelf navigation, persistent cache hits, legacy migration, isolation, bounds and invalidation passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
