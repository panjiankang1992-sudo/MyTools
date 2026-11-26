import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  describePreviewScope, previewScopeFailureMessage, previewScopeQuery
} from '../src/lib/admin-preview-scope.ts';

/**
 * 设计 164 行：「预览字帖使用当前草稿的已选范围」。
 *
 * 后端 `GET /textbooks/{id}/draft/preview` 支持 `nodeKind`／`nodeKey`／`draftRevision`
 * （范围不存在 → 404 `previewScopeNotFound`）。这里锁住三件事：
 * - 整册**两个参数都不传**（与后端"缺省即整册"的契约一致）；
 * - 单元／课文**真的把范围发出去**，不发就会被静默当成整册；
 * - 文案说的是"本次预览真的只包含什么"，而不是"后端不支持过滤"那句过时的话。
 */

test('整册范围：参数为空对象（两个都不传），文案标注整册且没有限制提示', () => {
  assert.deepEqual(previewScopeQuery({ kind: 'all', id: '' }), {});
  const scope = describePreviewScope({ kind: 'all', id: '' });
  assert.equal(scope.label, '整册');
  assert.equal(scope.wholeBook, true);
  assert.equal(scope.notice, '');
});

test('单元范围：发出 nodeKind=unit 与 nodeKey，文案说明预览只包含该单元', () => {
  assert.deepEqual(previewScopeQuery({ kind: 'unit', id: 'cu1' }), { nodeKind: 'unit', nodeKey: 'cu1' });
  const unit = describePreviewScope({ kind: 'unit', id: 'cu1' }, { unitTitle: '第一单元' });
  assert.equal(unit.label, '单元：第一单元');
  assert.equal(unit.wholeBook, false);
  assert.equal(unit.notice, '本次预览只包含该单元及其课文与条目。');
});

test('课文范围：发出 nodeKind=lesson 与 nodeKey，并把父单元带进标签', () => {
  assert.deepEqual(previewScopeQuery({ kind: 'lesson', id: 'cl1' }), { nodeKind: 'lesson', nodeKey: 'cl1' });
  const lesson = describePreviewScope(
    { kind: 'lesson', id: 'cl1' },
    { lessonTitle: '天地人', unitTitle: '第一单元' }
  );
  assert.equal(lesson.label, '课文：天地人（第一单元）');
  assert.equal(lesson.notice, '本次预览只包含该课文及其所属单元（第一单元）。');
  // 父单元缺失时仍然如实说明"含所属单元"，只是不编造单元名。
  const orphan = describePreviewScope({ kind: 'lesson', id: 'cl1' }, { lessonTitle: '天地人' });
  assert.equal(orphan.label, '课文：天地人');
  assert.equal(orphan.notice, '本次预览只包含该课文及其所属单元。');
});

test('标题缺失时用占位名，不编造标题', () => {
  assert.equal(describePreviewScope({ kind: 'unit', id: 'cu1' }).label, '单元：未命名单元');
  assert.equal(describePreviewScope({ kind: 'lesson', id: 'cl1' }, { lessonTitle: '  ' }).label, '课文：未命名课文');
});

test('范围标识为空也照样发出去：由后端如实报 404，不允许前端悄悄变成整册', () => {
  // 若这里返回 {}，范围无效就会静默退化成整册——正是要防的"假装过滤"。
  assert.deepEqual(previewScopeQuery({ kind: 'unit', id: '' }), { nodeKind: 'unit', nodeKey: '' });
});

test('404 previewScopeNotFound 翻成明确提示，且点名是哪种范围不见了', () => {
  const unit = previewScopeFailureMessage({ reason: 'previewScopeNotFound', nodeKind: 'unit', nodeKey: 'cu1' });
  assert.equal(typeof unit, 'string');
  assert.equal(unit?.includes('预览范围不存在'), true);
  assert.equal(unit?.includes('单元'), true);
  // 必须写明不会退化成整册，用户才知道自己看到的不是"整册被当成选中范围"。
  assert.equal(unit?.includes('不会退化成整册'), true);

  const lesson = previewScopeFailureMessage({ reason: 'previewScopeNotFound', nodeKind: 'lesson', nodeKey: 'cl1' });
  assert.equal(lesson?.includes('课文'), true);
});

test('其他失败不冒充范围错误（返回 null，交给通用错误处理）', () => {
  assert.equal(previewScopeFailureMessage(undefined), null);
  assert.equal(previewScopeFailureMessage({ reason: 'revisionMismatch' }), null);
  assert.equal(previewScopeFailureMessage({ reason: 'draftMissing' }), null);
});
