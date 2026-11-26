import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTextbookQueryString, describeTextbook, gradesForSubject } from '../src/lib/admin-textbook-view';

/**
 * 教材列表的纯逻辑：查询串拼装、元数据展示与学科年级联动。
 * 组件渲染无法自动验证（项目没有挂载测试工具），因此这些可纯函数验证的部分必须覆盖。
 */

test('查询串忽略空值，且始终带分页参数', () => {
  const query = buildTextbookQueryString({ keyword: '', subject: '', status: '' });
  assert.equal(query.includes('keyword='), false, '空关键字不应产生空参数');
  assert.equal(query.includes('subject='), false, '空学科不应产生空参数');
  assert.equal(query.includes('page=1'), true);
  assert.equal(query.includes('pageSize=20'), true);
});

test('查询串包含全部有效条件', () => {
  const query = buildTextbookQueryString({ keyword: '统编', subject: 'chinese', grade: 3, semester: 'first', status: 'published', page: 2, pageSize: 50 });
  const params = new URLSearchParams(query);
  assert.equal(params.get('keyword'), '统编');
  assert.equal(params.get('subject'), 'chinese');
  assert.equal(params.get('grade'), '3');
  assert.equal(params.get('semester'), 'first');
  assert.equal(params.get('status'), 'published');
  assert.equal(params.get('page'), '2');
  assert.equal(params.get('pageSize'), '50');
});

test('草稿没有元数据时退回显示教材标识，不隐藏记录', () => {
  const display = describeTextbook('draft-book-1', null);
  assert.equal(display.title, 'draft-book-1');
  assert.equal(display.subtitle, '草稿（尚未发布）');
});

test('元数据不可解析时仍显示记录并标明原因', () => {
  const display = describeTextbook('broken-book', '{not json');
  assert.equal(display.title, 'broken-book');
  assert.equal(display.subtitle, '元数据不可解析');
});

test('完整元数据生成可读的标题、副标题与条数', () => {
  const display = describeTextbook('cn-ty-3-1', JSON.stringify({
    series: '统编语文', publisher: '人民教育出版社', editionLabel: '2024 年修订版',
    grade: 3, semester: 'first', availableEntryCount: 777, expectedEntryCount: 800
  }));
  assert.equal(display.title, '统编语文');
  assert.equal(display.subtitle, '人民教育出版社 · 2024 年修订版 · 三年级 · 上册');
  assert.equal(display.counts, '777 条 / 应收 800');
});

test('缺少应收条数时不显示多余的斜杠', () => {
  const display = describeTextbook('en-yl-3-1', JSON.stringify({
    series: '译林英语', publisher: '译林出版社', grade: 3, semester: 'second', availableEntryCount: 140
  }));
  assert.equal(display.counts, '140 条');
  assert.equal(display.subtitle.includes('三年级 · 下册'), true);
});

test('英语只提供 3—6 年级，语文提供 1—6 年级', () => {
  assert.deepEqual(gradesForSubject('english'), [3, 4, 5, 6]);
  assert.deepEqual(gradesForSubject('chinese'), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(gradesForSubject(null), [1, 2, 3, 4, 5, 6]);
});
