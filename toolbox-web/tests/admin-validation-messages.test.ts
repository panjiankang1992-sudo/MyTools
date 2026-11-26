import assert from 'node:assert/strict';
import test from 'node:test';
import { VALIDATION_MESSAGES, formatIssue, validationMessage } from '../src/lib/admin-validation-messages';

/** 校验问题码的中文提示。重点：未收录的码必须原样显示，不能被藏起来。 */

test('已知问题码给出中文提示，而不是英文标识符', () => {
  // 这条规则在服务端**没有 detail**，此前用户看到的就是裸码。
  assert.equal(validationMessage('LESSON_UNIT_UNKNOWN').includes('课文'), true);
  assert.equal(/^[A-Z_]+$/.test(validationMessage('NAME_REQUIRED')), false, '不能把码本身当提示');
});

test('未收录的问题码原样显示（新规则的问题不能被静默吞掉）', () => {
  assert.equal(validationMessage('SOME_NEW_RULE_NOT_YET_MAPPED'), 'SOME_NEW_RULE_NOT_YET_MAPPED');
});

test('码缺失时给出可读的兜底文案', () => {
  assert.equal(validationMessage(null), '存在未说明的校验问题。');
  assert.equal(validationMessage(undefined), '存在未说明的校验问题。');
});

test('服务端给了 detail 时优先用它（它更具体）', () => {
  assert.equal(
    formatIssue({ code: 'COVERAGE_COMPLETE_NEEDS_EVIDENCE', detail: '还有 3 条未核对', fieldPath: 'coverageDeclaration' }),
    '还有 3 条未核对（coverageDeclaration）');
});

test('没有 detail 时用中文提示，并附上字段路径', () => {
  assert.equal(formatIssue({ code: 'SUBJECT_REQUIRED', fieldPath: 'subject' }),
    '请选择学科，例如语文或英语。（subject）');
});

test('没有 fieldPath 时不留下空括号', () => {
  assert.equal(formatIssue({ code: 'NAME_REQUIRED' }), '名称不能为空。');
});

test('映射表始终非空（防止文件被误清空后测试假通过）', () => {
  assert.equal(Object.keys(VALIDATION_MESSAGES).length > 15, true);
  for (const [code, text] of Object.entries(VALIDATION_MESSAGES)) {
    assert.equal(text.length > 0, true, `${code} 的提示不能为空`);
    // 真正要防的是把码当成提示和给出空文案，而不是文案里能不能有括号。
    assert.equal(/^[A-Z][A-Z_]*$/.test(text), false, `${code} 的提示不能是裸码`);
  }
});
