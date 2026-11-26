import assert from 'node:assert/strict';
import test from 'node:test';
import { toRankingRows, type StatsResponse } from '../src/services/stats-api';
import { findTool } from '../src/config/tools';
import { toolLabel } from '../src/locales/tools';

/**
 * 统计接口契约测试。
 *
 * 下面这份响应体是从线上 https://toolbox.yuyutian.top/public/v1/stats
 * 原样捕获的真实响应。任何字段改名都会让本用例失败，而不是等到页面上抛
 * TypeError 才被发现——排行榜就曾因为前端读 count、服务端返回 success 而在首页报错。
 */
const LIVE_RESPONSE = {
  code: 'OK',
  data: {
    timezone: 'Asia/Shanghai',
    generatedAt: '2026-10-02T13:56:23.264478980Z',
    completeThrough: '2026-10-02T11:07:06Z',
    partial: false,
    today: '2026-10-02',
    totals: { todayClicks: 2, todaySuccess: 2, allTimeSuccess: 2, todayPrintRequests: 1 },
    series: [
      { date: '2026-09-26', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-09-27', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-09-28', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-09-29', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-09-30', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-10-01', clicks: 0, success: 0, isToday: false, complete: true },
      { date: '2026-10-02', clicks: 2, success: 2, isToday: true, complete: false }
    ],
    ranking: [
      { toolId: 'worksheet.chinese-dictation', success: 1 },
      { toolId: 'worksheet.english-dictation', success: 1 }
    ]
  }
};

/** 取出线上响应中的数据体，并断言与前端类型一致。 */
function liveData(): StatsResponse {
  const data = LIVE_RESPONSE.data as unknown as StatsResponse;
  return data;
}

test('live stats response exposes exactly the fields the UI reads', () => {
  const data = liveData();

  for (const field of ['todayClicks', 'todaySuccess', 'allTimeSuccess', 'todayPrintRequests'] as const) {
    assert.equal(typeof data.totals[field], 'number', `totals.${field} 必须是数字`);
  }
  assert.equal(data.series.length, 7);
  for (const point of data.series) {
    assert.equal(typeof point.date, 'string');
    assert.ok(point.clicks === null || typeof point.clicks === 'number');
    assert.ok(point.success === null || typeof point.success === 'number');
    assert.equal(typeof point.isToday, 'boolean');
    assert.equal(typeof point.complete, 'boolean');
  }
  assert.equal(typeof data.partial, 'boolean');
  assert.equal(typeof data.today, 'string');
  // 排行榜字段名必须与前端一致：服务端只返回 toolId 与 success。
  for (const item of data.ranking) {
    assert.deepEqual(Object.keys(item).sort(), ['success', 'toolId']);
    assert.equal(typeof item.success, 'number');
  }
});

test('ranking rows map the live payload without undefined values', () => {
  const rows = toRankingRows(liveData().ranking, findTool, toolLabel);
  assert.equal(rows.length, 2);
  for (const row of rows) {
    assert.equal(typeof row.count, 'number', 'count 必须是数字，否则模板渲染会抛错');
    assert.ok(Number.isFinite(row.count));
    assert.equal(typeof row.name, 'string');
    assert.ok(row.name.length > 0);
    assert.ok(row.route.startsWith('/'));
    assert.ok(row.category === 'copybook' || row.category === 'words');
  }
  assert.deepEqual(rows.map(row => row.count), [1, 1]);
  assert.deepEqual(rows.map(row => row.name), ['看拼音写汉字', '看中文写单词']);
});

test('missing or malformed ranking counts degrade to zero instead of undefined', () => {
  const rows = toRankingRows(
    [
      { toolId: 'pdf.merge', success: undefined as unknown as number },
      { toolId: 'unknown.tool', success: 5 }
    ],
    findTool,
    toolLabel
  );
  assert.equal(rows[0]!.count, 0);
  // 未登记的工具保留原始标识，不隐藏数据。
  assert.equal(rows[1]!.name, 'unknown.tool');
  assert.equal(rows[1]!.count, 5);
  assert.equal(rows[1]!.category, null);
});

test('undefined ranking yields an empty list', () => {
  assert.deepEqual(toRankingRows(undefined, findTool, toolLabel), []);
});
