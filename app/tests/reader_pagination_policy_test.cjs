const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.ReaderPaginationPolicy();
const settings = { fontFamily: 'system', fontSize: 19, lineHeight: 1.8, paragraphSpacing: 14, horizontalPadding: 24,
  orientation: 'system', brightness: 1, theme: 'paper',
  pageTurnMode: 'slide', comicDirection: 'ltr', comicPageMode: 'single', comicFitMode: 'contain',
  comicScale: 1, comicPreload: 2 };

const long = '段落内容。'.repeat(800);
const pages = policy.paginate([{ kind: 'heading', text: '标题', uri: '', level: 1 },
  { kind: 'paragraph', text: long, uri: '' }, { kind: 'image', text: '插图', uri: 'file://image.jpg' },
  { kind: 'quote', text: '引用', uri: '' }], '', settings);
if (pages.length < 5) throw new Error('Long structured chapter must paginate');
if (!pages.some(page => page.length === 1 && page[0].kind === 'image')) throw new Error('Image block page');
if (pages.flat().filter(block => block.kind === 'paragraph').map(block => block.text).join('') !== long) {
  throw new Error('Pagination must preserve paragraph text');
}

const screenText = '正文'.repeat(600);
const screenPages = policy.paginate([], screenText, settings, { width: 360, height: 700 });
if (screenPages.length < 4) throw new Error('Phone viewport pages must fit their visible text');
if (screenPages.flat().map(block => block.text).join('') !== screenText) {
  throw new Error('The final page must preserve the end of the chapter');
}
const finalPage = screenPages[screenPages.length - 1];
if (!finalPage[finalPage.length - 1].text.endsWith(screenText.slice(-16))) {
  throw new Error('The final page must remain reachable');
}

const largerFont = policy.paginate([], long, { ...settings, fontSize: 30 });
if (largerFont.length <= policy.paginate([], long, settings).length) throw new Error('Font size affects page capacity');
const largerSpacing = policy.paginate([], long, { ...settings, paragraphSpacing: 32 });
if (largerSpacing.length <= policy.paginate([], long, settings).length) throw new Error('Paragraph spacing affects capacity');
const widePages = policy.paginate([], long, settings, { width: 900, height: 700 });
if (widePages.length >= policy.paginate([], long, settings, { width: 360, height: 700 }).length) {
  throw new Error('Viewport width affects page capacity');
}
const tallPages = policy.paginate([], long, settings, { width: 360, height: 1200 });
if (tallPages.length >= policy.paginate([], long, settings, { width: 360, height: 500 }).length) {
  throw new Error('Viewport height affects page capacity');
}
const monoPages = policy.paginate([], long, { ...settings, fontFamily: 'monospace' });
if (monoPages.length <= policy.paginate([], long, settings).length) throw new Error('Font family affects capacity');
const accessibilityPages = policy.paginate([], long, settings, { width: 360, height: 700 }, 2);
if (accessibilityPages.length <= policy.paginate([], long, settings).length) {
  throw new Error('System font scale affects page capacity');
}
const boundedScalePages = policy.paginate([], long, settings, { width: 360, height: 700 }, 100);
const maxScalePages = policy.paginate([], long, settings, { width: 360, height: 700 }, 3.2);
if (boundedScalePages.length !== maxScalePages.length) throw new Error('System font scale must be bounded');
const emoji = '😀'.repeat(1000);
const emojiPages = policy.paginate([], emoji, settings);
if (emojiPages.flat().map(block => block.text).join('') !== emoji) throw new Error('Surrogate-safe split');

console.log('Reader pagination policy tests passed');
