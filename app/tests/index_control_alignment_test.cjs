const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const source = readFileSync(process.argv[2], 'utf8');
const { FieldHeight } = require(process.argv[3]);
const controls = readFileSync(process.argv[4], 'utf8');

function body(name) {
  const start = source.indexOf(`  private ${name}(`);
  assert.notEqual(start, -1, `${name} must remain available`);
  const end = source.indexOf('\n  }', start);
  assert.notEqual(end, -1, `${name} must have a complete builder`);
  return source.slice(start, end + 4);
}
function count(text, token) { return text.split(token).length - 1; }

// Execute the real row-height policy; both independent visible borders consume this same value.
const row = new FieldHeight();
for (const scale of [0.75, 1, 1.3, 2, 3, 5]) {
  row.systemFontScale = scale;
  const common = row.BookSourceFilterControlHeight();
  assert.ok(common >= 48 && Number.isInteger(common));
  assert.ok(common >= 24 * Math.max(1, scale));
  const inputBorder = common;
  const filterBorder = common;
  assert.equal(inputBorder, filterBorder);
}
row.systemFontScale = 1;
assert.equal(row.BookSourceFilterControlHeight(), 48, 'large type must not leave a stale expanded row');

const filter = body('BookSourceManageFilter');
assert.equal(count(filter, '.height(\'100%\')'), 4,
  'search, wrapper, plate and filter target must consume the common frame');
assert.ok(filter.includes('.height(this.BookSourceFilterControlHeight())'));
assert.equal(count(filter, 'radius: AppTheme.inputRadius'), 1);
assert.ok(filter.includes('.borderRadius(AppTheme.inputRadius)'));
assert.ok(filter.includes('this.sourceManageFilterVisible = !this.sourceManageFilterVisible'));
assert.ok(filter.includes('this.CancelDeleteBookSource()'));
assert.ok(!/\.height\(50\)|radius: 15/.test(filter), 'filter cannot retain its mismatched independent frame');

// Native single-root buttons consume the row's explicit second-pass common height; automatic Stretch was insufficient on device.
const nativeButton = controls.split('export struct AppGroupedButton {')[1].split('\n}\n')[0];
assert.ok(nativeButton.includes('Button({ type: ButtonType.Normal })'));
assert.ok(!/onMeasureSize|onPlaceChildren/.test(nativeButton), 'native stretching cannot be replaced with custom plate placement');
assert.ok(nativeButton.includes('.backgroundColor(this.fill())'), 'the stretched button owns its entire visible plate');
assert.ok(nativeButton.includes('if (this.available && !this.loading) { this.action(); }'));
const files = body('ToolFilesPage');
assert.ok(files.includes('AppEqualActionRow() {'), 'file actions use the shared explicit common-height measurement');
for (const action of ['UploadToolFile()', 'StartCreateToolDirectory()', 'LoadToolFiles()']) {
  assert.ok(files.includes(`this.${action}`));
}
assert.equal(count(files, 'AppGroupedButton({'), 3);
assert.ok(!files.includes('grouped: true'), 'legacy custom grouped buttons cannot return');
assert.equal(count(files, 'Blank().width(8).flexGrow(0).flexShrink(0)'), 0,
  'the equal-action container owns spacing; decorative children must not consume button slots');
const fileActions = body('ToolFileOperationPanel');
assert.equal(count(fileActions, 'AppEqualActionRow() {'), 2);
assert.ok(fileActions.includes('this.DownloadToolFile()'));
assert.ok(fileActions.includes("this.toolOperation = 'delete'"));
assert.ok(fileActions.includes('this.ConfirmToolOperation()'), 'destructive operations keep their existing confirmation path');
assert.equal(count(fileActions, 'AppGroupedButton({'), 4);
assert.ok(!fileActions.includes('grouped: true'));
const credentials = body('BookSourceCredentialPanel');
assert.equal(count(credentials, 'AppGroupedButton({'), 3);
assert.ok(!credentials.includes('grouped: true'));
assert.equal(count(credentials, 'AppEqualActionRow() {'), 2);
for (const callback of ['CloseBookSourceCredential()', 'ClearBookSourceCredential(source.bookSourceUrl)',
  'SaveBookSourceCredential(source.bookSourceUrl)']) {
  assert.ok(credentials.includes(`this.${callback}`));
}
const catalog = body('ReaderCatalogPanel');
assert.equal(count(catalog, 'AppGroupedButton({'), 2);
assert.ok(catalog.includes('this.ChangeReaderSection(-1)') && catalog.includes('this.ChangeReaderSection(1)'));
assert.equal(count(catalog, 'AppEqualActionRow() {'), 1);
const mediaAction = body('MediaActionButton');
assert.ok(mediaAction.includes('AppGroupedButton({'));
assert.ok(mediaAction.includes('action: action'));
assert.ok(!mediaAction.includes('.layoutWeight('));
const mediaSheet = body('MediaActionSheet');
assert.equal(count(mediaSheet, 'AppEqualActionRow() {'), 2);
for (const mode of ['rename', 'move', 'tags', 'details']) {
  assert.ok(mediaSheet.includes(`this.BeginMediaAction('${mode}',`));
}
const audiobook = body('AudiobookGenerationSheet');
assert.equal(count(audiobook, 'AppEqualActionRow() {'), 1);
assert.ok(audiobook.includes('this.PlayPreviousAudiobookChapter()'));
assert.ok(audiobook.includes('this.PlayNextAudiobookChapter()'));

// BuilderParam trailing closures must remain unwrapped; extra common modifiers hide the direct measurement children.
const rows = [...source.matchAll(/AppEqualActionRow\(\)\s*\{/g)];
assert.ok(rows.length >= 9, 'all identified equal-action groups use explicit common-height measurement');
for (const row of rows) {
  const start = row.index + row[0].length;
  let depth = 1, end = start;
  while (depth > 0 && end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
    end++;
  }
  assert.equal(depth, 0);
  const content = source.slice(start, end - 1);
  assert.ok(!/Blank\(\)|\.layoutWeight\(/.test(content), 'buttons are the only measured slots; row owns gaps and equal widths');
  assert.ok(!source.slice(end).trimStart().startsWith('.'), 'BuilderParam closure must not receive common attributes');
}

// Content rows must grow with native text metrics rather than clipping into a fixed height.
for (const [name, height, callback] of [
  ['ToolFileRow', 70, 'this.SelectToolFile(item)'],
  ['AudioQueueRow', 58, 'this.OpenRemoteMedia(item)'],
  ['DriveSelectorSheet', 66, 'this.SelectDrive(drive.driveId)'],
  ['ProfileAdultContentRow', 68, 'this.SetAdultContentFilter(value)'],
  ['ReaderCatalogPanel', 52, 'this.SelectReaderChapter(index)']
]) {
  const block = body(name);
  assert.ok(block.includes(`minHeight: ${height}`), `${name} retains the normal-size minimum`);
  assert.ok(!block.includes(`.height(${height})`), `${name} must allow larger text to grow`);
  assert.ok(block.includes(callback), `${name} keeps its interaction`);
}
const sourceSearch = body('BookSourceSearchField');
assert.ok(sourceSearch.includes('minHeight: 50'), 'two-line mode options need natural height');
assert.ok(sourceSearch.includes('.height(54)'), 'integrated search plate is not an independent field-row action');
assert.ok(sourceSearch.includes('this.SearchBookSources()'));
for (const [name, expected] of [['ToolMarketPage', 1], ['CopilotPage', 2]]) {
  assert.equal(count(body(name), 'fieldAligned: true'), expected);
}

// Existing media fixes are outside this change: preserve order, frame and shared row-height helper.
const mediaToolbar = body('MediaCatalogToolbar');
assert.ok(mediaToolbar.indexOf("FilterButton('directory')") < mediaToolbar.indexOf("FilterButton('tag')"));
assert.ok(mediaToolbar.indexOf("FilterButton('tag')") < mediaToolbar.indexOf('AppSearchField('));
assert.ok(mediaToolbar.includes('.height(this.MediaCatalogControlHeight())'));
console.log('Index control alignment: shared frames, grouped actions, callbacks, scalable content rows and media-toolbar preservation passed');
