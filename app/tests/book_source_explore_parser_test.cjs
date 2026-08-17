const parserModule = require(process.argv[2]);
const parser = new parserModule.BookSourceExploreParser();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(value, message) {
  let failed = false;
  try { parser.parse(value); } catch (_) { failed = true; }
  equal(failed, true, message);
}

equal(parser.parse('/rank/{{page}}'), [{ title: '发现', url: '/rank/{{page}}' }], 'Direct URL');
equal(parser.parse('全部::/all/{{page}}\n玄幻::/fantasy/{{page}}'), [
  { title: '全部', url: '/all/{{page}}' },
  { title: '玄幻', url: '/fantasy/{{page}}' }
], 'Line categories');
equal(parser.parse('分组::\n排行::/rank'), [{ title: '排行', url: '/rank' }], 'Empty heading URL');
equal(parser.parse(JSON.stringify([
  { title: '榜单', url: '/rank', style: { ignored: true } },
  { title: '标题', url: '' }
])), [{ title: '榜单', url: '/rank' }], 'JSON categories');
rejected('缺少分隔符\n排行::/rank', 'Malformed line category');
rejected('坏\u0001标题::/rank', 'Control character title');
rejected('分类::/rank\n'.repeat(101), 'Category quota');
rejected('[{"title":"排行",]', 'Malformed JSON');

console.log('Book source explore parser tests passed');
