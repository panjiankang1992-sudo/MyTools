const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

const mode = { FileOpenMode: 0, FileOpenMultipleMode: 1, FileOpenFolderMode: 2, FileSaveMode: 3 };
const mime = { IMAGE_TYPE: 'image/*', VIDEO_TYPE: 'video/*', IMAGE_VIDEO_TYPE: '*/*' };
const compiled = ts.transpileModule(fs.readFileSync(process.argv[2], 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
const tick = () => new Promise(resolve => setImmediate(resolve));

function setup() {
  const menus = [], documents = [], media = [];
  const predict = (list, options) => new Promise((resolve, reject) => list.push({ options, resolve, reject }));
  const imports = {
    '@ohos.file.photoAccessHelper': { default: {
      PhotoSelectOptions: class {}, PhotoViewMIMETypes: mime,
      PhotoViewPicker: class { select(options) { return predict(media, options); } }
    } },
    '@ohos.file.picker': { default: {
      DocumentSelectOptions: class {},
      DocumentViewPicker: class { select(options) { return predict(documents, options); } }
    } },
    '../../theme/AppTheme': { AppTheme: { textPrimary: '#111111' } }
  };
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: name => {
    assert.ok(imports[name], `Unexpected import ${name}`);
    return imports[name];
  }, $r: value => value, FileSelectorMode: mode });
  const chooser = new exports.DshWebFileChooser({}, { showActionMenu: options => predict(menus, options) });
  return { chooser, menus, documents, media };
}

function request(fileMode = mode.FileOpenMultipleMode, types = [], capture = false) {
  const replies = [];
  const event = {
    fileSelector: { getMode: () => fileMode, isCapture: () => capture, getAcceptType: () => types },
    result: { handleFileList: values => replies.push([...values]) }
  };
  return { event, replies };
}

(async () => {
  // DSH 实际的多选输入未声明 accept。两类系统选择器均需可直接使用。
  const general = setup();
  const files = request();
  assert.equal(general.chooser.show(files.event, () => true), true);
  assert.equal(general.menus[0].options.buttons.length, 2);
  general.menus[0].resolve({ index: 1 });
  await tick();
  assert.ok(general.documents[0].options.maxSelectNumber > 1);
  const documentUri = 'file://docs/storage/Users/currentUser/report%20final.pdf';
  const anotherUri = 'file://docs/storage/Users/currentUser/spec.txt';
  general.documents[0].resolve([documentUri, 'https://example.test/file', anotherUri, documentUri, '/private/file']);
  await tick();
  assert.deepEqual(files.replies, [[documentUri, anotherUri]]);

  const photos = request();
  assert.equal(general.chooser.show(photos.event, () => true), true);
  general.menus[1].resolve({ index: 0 });
  await tick();
  const photoUri = 'file://media/Photo/17/IMG_001/IMG_001.jpg';
  const videoUri = 'file://media/Photo/18/VID_002/VID_002.mp4';
  assert.equal(general.media[0].options.MIMEType, mime.IMAGE_VIDEO_TYPE);
  assert.equal(general.media[0].options.isPhotoTakingSupported, true);
  general.media[0].resolve({ photoUris: [photoUri, videoUri] });
  await tick();
  assert.deepEqual(photos.replies, [[photoUri, videoUri]]);

  const single = request(mode.FileOpenMode, [' image/* ']);
  general.chooser.show(single.event, () => true);
  assert.equal(general.media[1].options.MIMEType, mime.IMAGE_TYPE);
  assert.equal(general.media[1].options.maxSelectNumber, 1);
  general.media[1].resolve({ photoUris: [photoUri, videoUri] });
  await tick();
  assert.deepEqual(single.replies, [[photoUri]]);

  // 外部文档及忙时请求不能回落到系统默认文件窗口。
  const stale = setup();
  const rejected = request();
  assert.equal(stale.chooser.show(rejected.event, () => false), true);
  assert.deepEqual(rejected.replies, [[]]);
  assert.equal(stale.menus.length, 0);
  const atMenu = request(), busy = request();
  stale.chooser.show(atMenu.event, () => true);
  assert.equal(stale.chooser.show(busy.event, () => true), true);
  assert.deepEqual(busy.replies, [[]]);
  stale.chooser.close();
  stale.menus[0].resolve({ index: 0 });
  await tick();
  assert.equal(stale.media.length, 0);
  assert.deepEqual(atMenu.replies, [[]]);

  const atPicker = request(mode.FileOpenMode, ['video/*']), next = request();
  let current = true;
  stale.chooser.show(atPicker.event, () => current);
  stale.chooser.close();
  current = false;
  stale.chooser.show(next.event, () => true);
  stale.media[0].resolve({ photoUris: [videoUri] });
  stale.menus[1].reject(new Error('cancelled'));
  await tick();
  stale.chooser.close();
  assert.deepEqual(atPicker.replies, [[]]);
  assert.deepEqual(next.replies, [[]]);

  const changed = setup();
  const oldPage = request(mode.FileOpenMode, ['image/*']);
  current = true;
  changed.chooser.show(oldPage.event, () => current);
  current = false;
  changed.media[0].resolve({ photoUris: [photoUri] });
  await tick();
  assert.deepEqual(oldPage.replies, [[]]);

  // 特定扩展名、目录、保存和拍摄继续由 ArkWeb 保留原生选择语义。
  for (const special of [request(mode.FileOpenMode, ['.pdf']), request(mode.FileOpenFolderMode),
    request(mode.FileSaveMode), request(mode.FileOpenMode, ['image/*'], true)]) {
    assert.equal(general.chooser.show(special.event, () => true), false);
    assert.deepEqual(special.replies, []);
  }
  console.log('PASS: DSH generic document and media choices, multi-select, cancellation, and owner isolation');
})().catch(error => { console.error(error); process.exitCode = 1; });
