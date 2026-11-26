import assert from 'node:assert/strict';
import test from 'node:test';
import {
  addLesson, addUnit, detachRemovedLessons, detachRemovedUnit, moveNode, nextClientId, removeLesson, removeUnit,
  renameLesson, renameUnit, renderTree, resolvePasteTarget, type Outline
} from '../src/lib/admin-textbook-outline';

/**
 * 单元／课文目录树的纯逻辑。
 * 重点：临时标识不与既有冲突、课文只在同单元内移动、删除单元不留下悬空引用。
 */

/** 构造一个两单元四课文的目录。 */
function sample(): Outline {
  return {
    units: [
      { unitId: null, clientUnitId: 'u1', title: '第一单元', order: 1 },
      { unitId: null, clientUnitId: 'u2', title: '第二单元', order: 2 }
    ],
    lessons: [
      { lessonId: null, clientLessonId: 'l1', clientUnitId: 'u1', title: '天地人', order: 1 },
      { lessonId: null, clientLessonId: 'l2', clientUnitId: 'u1', title: '金木水火土', order: 2 },
      { lessonId: null, clientLessonId: 'l3', clientUnitId: 'u2', title: '秋天', order: 1 }
    ]
  };
}

test('新增单元追加到末尾并给定顺序', () => {
  const next = addUnit(sample(), '第三单元');
  assert.equal(next.units.length, 3);
  assert.equal(next.units[2].title, '第三单元');
  assert.equal(next.units[2].order, 3);
});

test('临时标识避让既有标识，删除后再新增不冲突', () => {
  const outline = sample();
  // u1、u2 已存在；新增应得到 u3 而不是重复的 u2
  const added = addUnit(outline, '新单元');
  assert.equal(added.units[2].clientUnitId, 'u3');
  // 删除一个后再新增，长度变小但标识不能被复用
  const afterRemove = removeUnit(added, 'u2').outline;
  const readded = addUnit(afterRemove, '再新增');
  const ids = readded.units.map(unit => unit.clientUnitId);
  assert.equal(new Set(ids).size, ids.length, `标识不应重复：${ids.join(',')}`);
});

test('新增课文归属到指定单元且顺序按同级计算', () => {
  const next = addLesson(sample(), 'u1', '口耳目');
  const added = next.lessons[next.lessons.length - 1];
  assert.equal(added.clientUnitId, 'u1');
  assert.equal(added.order, 3, 'u1 下已有两课，新课顺序应为 3');
});

test('课文可以在未分单元下新建', () => {
  const next = addLesson(sample(), '', '未分单元课文');
  const added = next.lessons[next.lessons.length - 1];
  assert.equal(added.clientUnitId, '');
});

test('删除单元会一并删除其下课文，并返回被移除的课文标识', () => {
  const result = removeUnit(sample(), 'u1');
  assert.equal(result.outline.units.length, 1);
  assert.equal(result.outline.units[0].clientUnitId, 'u2');
  assert.equal(result.outline.lessons.length, 1);
  assert.equal(result.outline.lessons[0].clientLessonId, 'l3');
  assert.deepEqual(result.removedLessonIds.sort(), ['l1', 'l2']);
});

test('删除单元后剩余单元顺序被重排', () => {
  const result = removeUnit(sample(), 'u1');
  assert.equal(result.outline.units[0].order, 1);
});

test('课文移动只在同一单元内，不跨单元', () => {
  const moved = moveNode(sample(), 'lesson', 'l1', 1);
  const u1 = moved.lessons.filter(lesson => lesson.clientUnitId === 'u1');
  const u2 = moved.lessons.filter(lesson => lesson.clientUnitId === 'u2');
  assert.equal(u1.length, 2, 'u1 仍应有两课');
  assert.equal(u2.length, 1, 'u2 不应被影响');
  assert.equal(u1[0].clientLessonId, 'l2');
  assert.equal(u1[1].clientLessonId, 'l1');
});

test('越界移动不发生改变', () => {
  const outline = sample();
  const moved = moveNode(outline, 'unit', 'u1', -1);
  assert.deepEqual(moved.units.map(unit => unit.clientUnitId), ['u1', 'u2']);
});

test('重命名不影响标识与顺序', () => {
  const renamed = renameUnit(sample(), 'u1', '第一单元（修订）');
  assert.equal(renamed.units[0].title, '第一单元（修订）');
  assert.equal(renamed.units[0].clientUnitId, 'u1');
  const renamedLesson = renameLesson(sample(), 'l1', '天地人（改）');
  assert.equal(renamedLesson.lessons[0].title, '天地人（改）');
  assert.equal(renamedLesson.lessons[0].clientLessonId, 'l1');
});

test('删除课文只删该课', () => {
  const next = removeLesson(sample(), 'l1');
  assert.equal(next.lessons.length, 2);
  assert.equal(next.lessons.some(lesson => lesson.clientLessonId === 'l1'), false);
});

test('渲染树把未分单元的课文单独成组', () => {
  const outline = addLesson(sample(), '', '散课');
  const tree = renderTree(outline);
  assert.equal(tree.length, 3);
  assert.equal(tree[2].unit, null);
  assert.equal(tree[2].lessons[0].title, '散课');
});

test('删除单元后，原属其下课的条目被解除课文归属', () => {
  const entries = [
    { rowId: 'r1', clientLessonId: 'l1', lessonId: 'lesson-1' },
    { rowId: 'r2', clientLessonId: 'l3', lessonId: 'lesson-3' }
  ];
  const cleaned = detachRemovedLessons(entries, ['l1', 'l2']);
  assert.equal(cleaned[0].clientLessonId, null);
  assert.equal(cleaned[0].lessonId, null);
  // 不属于被删课文的条目保持不动
  assert.equal(cleaned[1].clientLessonId, 'l3');
  assert.equal(cleaned[1].lessonId, 'lesson-3');
});

test('nextClientId 在标识被占用时继续向后找', () => {
  assert.equal(nextClientId([], 'u'), 'u1');
  assert.equal(nextClientId(['u1', 'u2'], 'u'), 'u3');
  // 长度只有 1，但 u2 已被占用，必须跳到 u3
  assert.equal(nextClientId(['u2'], 'u'), 'u3');
});

test('向尚无内容的课文粘贴时，仍取到该课文真正所属的单元', () => {
  const outline = sample();
  // 注意：这里不传任何已有条目——课文还没有内容时正是这种情形。
  const target = resolvePasteTarget(outline, { kind: 'lesson', id: 'l1' });
  assert.equal(target.clientUnitId, 'u1', '必须从目录取单元，而不是退化成未分单元');
  assert.equal(target.clientLessonId, 'l1');
});

test('粘贴到单元与全部内容时的目标', () => {
  const outline = sample();
  assert.deepEqual(resolvePasteTarget(outline, { kind: 'unit', id: 'u2' }),
    { clientUnitId: 'u2', clientLessonId: null });
  assert.deepEqual(resolvePasteTarget(outline, { kind: 'all', id: '' }),
    { clientUnitId: '', clientLessonId: null });
});

test('课文标识不存在时退化为未分单元，而非抛错', () => {
  const target = resolvePasteTarget(sample(), { kind: 'lesson', id: 'l-none' });
  assert.equal(target.clientUnitId, '');
  assert.equal(target.clientLessonId, 'l-none');
});

test('删除单元后，其下条目被解绑到未分单元而不是留下悬空引用', () => {
  const entries = [
    { rowId: 'r1', clientUnitId: 'u1', clientLessonId: 'l1', text: '山' },
    { rowId: 'r2', clientUnitId: 'u2', clientLessonId: 'l3', text: '水' }
  ];
  const detached = detachRemovedUnit(entries, 'u1');
  // 关键：不能继续指向已删除的 u1。
  assert.equal(detached[0].clientUnitId, '');
  assert.equal(detached[0].clientLessonId, null);
  // 内容保留——删除单元不该毁掉用户已录入的内容。
  assert.equal(detached[0].text, '山');
  // 其他单元不受影响。
  assert.equal(detached[1].clientUnitId, 'u2');
  assert.equal(detached[1].clientLessonId, 'l3');
});
