const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

// 使用真实临时磁盘模拟鸿蒙文件接口，子进程不会继承任何内存缓存。
function runtime() {
  const adapter = {
    OpenMode: { READ_ONLY: fs.constants.O_RDONLY, WRITE_ONLY: fs.constants.O_WRONLY,
      CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    accessSync: fs.existsSync,
    mkdirSync: p => fs.mkdirSync(p, { recursive: true }),
    statSync: p => { const s = fs.statSync(p); return { size: s.size, mtime: s.mtimeMs, isFile: () => s.isFile() }; },
    copyFileSync: fs.copyFileSync, renameSync: fs.renameSync, unlinkSync: fs.unlinkSync,
    rmdirSync: p => fs.rmSync(p, { recursive: true }), listFileSync: fs.readdirSync,
    readText: p => fs.promises.readFile(p, 'utf8'), readTextSync: p => fs.readFileSync(p, 'utf8'),
    openSync: (p, mode) => ({ fd: fs.openSync(p, mode) }),
    closeSync: file => fs.closeSync(typeof file === 'number' ? file : file.fd),
    writeSync: (fd, data) => fs.writeSync(fd, typeof data === 'string' ? data : new Uint8Array(data)),
    readSync: (fd, buffer, options = {}) => fs.readSync(fd, new Uint8Array(buffer), 0,
      options.length ?? buffer.byteLength, options.offset ?? null)
  };
  const box = { Error, fs: adapter, setTimeout, clearTimeout, console,
    http: { createHttp: () => { throw Error('Network must not be used'); } },
    util: { generateRandomUUID: () => crypto.randomUUID(),
      TextEncoder: class { encodeInto(s) { return new TextEncoder().encode(s); } },
      TextDecoder: { create: (encoding, options) => ({
        decodeToString: bytes => new TextDecoder(encoding, options).decode(bytes) }) } },
    MobiReaderNative: class { parse() { throw Error('Unexpected native parsing'); } },
    DigestTool: class { async digestText(s) { return crypto.createHash('sha256').update(s).digest('hex'); } }
  };
  vm.createContext(box);
  for (const name of ['ReaderShelfFileCache', 'ReaderPreparedDocumentCache', 'ReaderTextDocumentCache',
    'RemoteBookDownloadPolicy', 'TextPayloadPolicy', 'ReaderTextMetadataPolicy', 'ReaderContentLoader']) {
    const source = fs.readFileSync(`app/entry/src/main/ets/features/reader/${name}.ets`, 'utf8')
      .replace(/^import .*;\n/gm, '').replace(/export /g, '');
    vm.runInContext(ts.transpileModule(`(() => { ${source}\nglobalThis.${name} = ${name}; })();`, {
      compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, box);
  }
  return box;
}

const formats = ['txt', 'epub', 'cbz', 'pdf', 'mobi', 'azw3'];
const book = format => ({ id: format, sourceId: 'source', resourceUri: `/books/${format}`,
  format, cacheVersion: 'version-one' });
const open = (loader, context, source, format) => format === 'pdf' ? loader.prepareLocalPdf(context, source) :
  format === 'txt' ? loader.loadLocalText(context, source) :
  ['mobi', 'azw3'].includes(format) ? loader.loadLocalMobi(context, source, format) :
  loader.loadLocalArchive(context, source, format);
const fail = () => { throw Error('Cached open repeated parsing, extraction, indexing or copying'); };

async function restart(context) {
  const box = runtime();
  for (const format of formats.concat('large')) {
    const loader = new box.ReaderContentLoader();
    const identity = format === 'large' ? { ...book('txt'), id: 'large' } : book(format);
    const source = await loader.shelfFileCache.prepare(context, 'account', 'server', identity);
    assert.ok(source.startsWith(context.filesDir));
    loader.onParsing = fail;
    loader.parseTextBytes = fail;
    loader.indexLargeText = fail;
    loader.extractAndParse = fail;
    loader.copyLocalBookFile = fail;
    const document = await open(loader, context, source, identity.format);
    if (format === 'pdf') assert.equal(document, source);
    else if (format === 'large') {
      assert.ok(document.chapters.length > 1);
      const uri = document.chapters[1].resourceUri;
      assert.ok(loader.loadIndexedTextChapter(uri).includes('persistent body'));
      const ref = JSON.parse(decodeURIComponent(uri.substring(10)));
      ref.path = '/etc/passwd';
      assert.throws(() => loader.loadIndexedTextChapter('txt-index:' + encodeURIComponent(JSON.stringify(ref))));
    } else if (format === 'cbz') assert.equal(fs.readFileSync(document.imageUris[0].slice(7), 'utf8'), 'image');
    else {
      assert.match(document.chapters[0].content, /persistent body/);
      if (format !== 'txt') assert.equal(fs.readFileSync(document.chapters[0].blocks[0].uri.slice(7), 'utf8'), 'image');
    }
    loader.cleanup();
    assert.equal(fs.existsSync(source), true);
    if (format !== 'pdf') assert.ok(await new box.ReaderPreparedDocumentCache().read(source));
  }
  console.log('Fresh process: six formats and indexed TXT opened without network, parsing, extraction or copying');
}

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-persistence-'));
  const context = { filesDir: `${root}/files`, cacheDir: `${root}/cache` };
  fs.mkdirSync(context.filesDir); fs.mkdirSync(context.cacheDir);
  const box = runtime();
  try {
    for (const format of formats) {
      const loader = new box.ReaderContentLoader();
      await loader.shelfFileCache.prepare(context, 'account', 'server', book(format));
      const source = `${root}/original.${format}`;
      const bytes = format === 'txt' ? Buffer.from('Chapter 1\npersistent body\n') :
        format === 'pdf' ? Buffer.from('%PDF-1.7\ntest') : Buffer.alloc(100);
      if (['epub', 'cbz'].includes(format)) Buffer.from([0x50, 0x4b, 3, 4]).copy(bytes);
      if (['mobi', 'azw3'].includes(format)) bytes.write('BOOKMOBI', 60);
      fs.writeFileSync(source, bytes);
      // 原生解析器不在 Node 运行；仅首次解析用受控输出，缓存读写与重开使用真实实现。
      const parsed = () => {
        const image = `${loader.activeDirectory}/image.png`;
        fs.writeFileSync(image, 'image');
        return format === 'cbz' ? { kind: 'comic', chapters: [], imageUris: [`file://${image}`] } :
          { kind: 'text', coverUri: '', imageUris: [], chapters: [{ title: 'One', content: 'persistent body',
            blocks: [{ kind: 'image', uri: `file://${image}`, text: '' }] }] };
      };
      loader.extractAndParse = async () => parsed();
      loader.mobiReader.parse = parsed;
      await open(loader, context, source, format);
      loader.cleanup(); fs.unlinkSync(source);
    }
    // 大 TXT 使用真实索引和正文解码，而非仅构造一份能够反序列化的 JSON。
    const large = `${root}/large.txt`;
    fs.writeFileSync(large, 'persistent body\n'.repeat(1400000));
    const loader = new box.ReaderContentLoader();
    await loader.shelfFileCache.prepare(context, 'account', 'server', { ...book('txt'), id: 'large' });
    const indexed = await loader.loadLocalText(context, large);
    assert.ok(loader.loadIndexedTextChapter(indexed.chapters[1].resourceUri).includes('persistent body'));
    loader.cleanup(); fs.unlinkSync(large);
    fs.rmSync(context.cacheDir, { recursive: true });
    console.log(execFileSync(process.execPath, [__filename, '--restart', JSON.stringify(context)], { encoding: 'utf8' }).trim());

    const cache = new box.ReaderShelfFileCache();
    const source = await cache.prepare(context, 'account', 'server', book('epub'));
    const prepared = new box.ReaderPreparedDocumentCache();
    const recordPath = `${source}.prepared-v1/reader-document.json`;
    const original = fs.readFileSync(recordPath, 'utf8');
    fs.writeFileSync(recordPath, '{');
    assert.equal(await prepared.read(source), undefined);
    fs.writeFileSync(recordPath, original);
    const invalid = JSON.parse(original);
    invalid.document.chapters[0].blocks[0].uri = 'file:///etc/passwd';
    fs.writeFileSync(recordPath, JSON.stringify(invalid));
    assert.equal(await prepared.read(source), undefined);
    fs.writeFileSync(recordPath, original);
    fs.unlinkSync(JSON.parse(original).document.chapters[0].blocks[0].uri.slice(7));
    assert.equal(await prepared.read(source), undefined, 'Missing image must force raw-file fallback');
    assert.equal(cache.available(), true);
    cache.invalidate();
    assert.equal(fs.existsSync(source), false);
    assert.equal(fs.existsSync(`${source}.prepared-v1`), false);

    const txt = await cache.prepare(context, 'account', 'server', book('txt'));
    fs.appendFileSync(txt, 'changed');
    assert.equal(await prepared.read(txt), undefined, 'Changed source must invalidate parsed snapshot');
    for (const [scope, server, identity] of [['other', 'server', book('txt')], ['account', 'other', book('txt')],
      ['account', 'server', { ...book('txt'), cacheVersion: 'two' }]]) {
      assert.equal(await new box.ReaderShelfFileCache().prepare(context, scope, server, identity), '');
    }
    const trial = new box.ReaderContentLoader();
    const trialPath = `${root}/trial.txt`; fs.writeFileSync(trialPath, 'trial body');
    await trial.loadLocalText(context, trialPath);
    await trial.shelfFileCache.prepare(context, 'account', 'server', { ...book('txt'), id: 'trial' });
    assert.equal(await trial.retainOpenedBook(), true);
    assert.equal(trial.shelfFileCache.available(), true);
    const abandoned = new box.ReaderPreparedDocumentCache();
    let cancelled = false;
    abandoned.read = async () => { cancelled = true; return undefined; };
    const trialSource = trial.shelfFileCache.path();
    await abandoned.save(trialSource, { kind: 'text', chapters: [{ title: 'One', content: 'body' }], imageUris: [] },
      '', () => cancelled);
    assert.equal(fs.existsSync(`${trialSource}.prepared-v1`), false, 'Cancellation must not commit a late snapshot');
    console.log('Persistent snapshots: assets, corruption fallback, version/account/server isolation, removal and trial retention passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}

(process.argv[2] === '--restart' ? restart(JSON.parse(process.argv[3])) : main())
  .catch(error => { console.error(error); process.exitCode = 1; });
