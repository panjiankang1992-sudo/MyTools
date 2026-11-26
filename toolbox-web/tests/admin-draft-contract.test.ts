import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

/**
 * 跨端契约守卫：前端发给服务端的草稿字段，必须都被服务端声明。
 *
 * 背景（两次真实踩坑）：
 * - 第 78 轮：前端**少发** `idMapping` 的实值 → 服务端能力被废掉；
 * - 第 80 轮：前端**多发**一个字段 → 服务端 `fail-on-unknown-properties=true`，
 *   **每次保存草稿都 400 失败**。
 *
 * 两个方向都不是靠"看着像对"能发现的，因此这里直接比对两端的字段集合。
 */

/** 提取 Java record 的字段名。 */
function javaRecordFields(source: string, name: string): Set<string> {
  const match = new RegExp(`public record ${name}\\(([\\s\\S]*?)\\)\\s*\\{`).exec(source);
  if (!match) return new Set();
  // 去掉 Javadoc，避免把注释里的词当成字段。
  const body = match[1].replace(/\/\*\*[\s\S]*?\*\//g, '');
  return new Set(body.split(',')
    .map(part => part.trim().split(/\s+/).pop() ?? '')
    .filter(Boolean));
}

test('前端发送的草稿字段全部被服务端声明（防止保存整体失败）', async () => {
  const repoRoot = new URL('../../', import.meta.url);
  const serverSource = await readFile(
    new URL('service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/validation/AdminTextbookDraft.java', repoRoot),
    'utf8');
  const serverKeys = new Set([
    ...javaRecordFields(serverSource, 'AdminTextbookDraft'),
    ...javaRecordFields(serverSource, 'DraftUnit'),
    ...javaRecordFields(serverSource, 'DraftLesson'),
    ...javaRecordFields(serverSource, 'DraftEntry')
  ]);
  // 泛型参数会被解析成 "java.util.Map<String"，不是真实字段名，剔除。
  for (const key of [...serverKeys]) {
    if (key.includes('<') || key.includes('>')) serverKeys.delete(key);
  }

  const frontendSource = await readFile(
    new URL('../src/lib/admin-textbook-draft.ts', import.meta.url), 'utf8');
  const entryBlock = frontendSource.slice(
    frontendSource.indexOf('export interface DraftEntry'),
    frontendSource.indexOf('export function newRowId'));
  const frontendKeys = new Set(
    [...entryBlock.matchAll(/^\s{2}([a-zA-Z]+)\??:/gm)].map(match => match[1]));

  // 先确认**提取确实取到了东西**：若正则失效导致集合为空，下面的断言会假通过。
  assert.equal(serverKeys.size > 10, true, `服务端字段提取失败，只取到：${[...serverKeys].join(',')}`);
  assert.equal(frontendKeys.size > 0, true, `前端字段提取失败，只取到：${[...frontendKeys].join(',')}`);

  const unknown = [...frontendKeys].filter(key => !serverKeys.has(key));
  assert.deepEqual(unknown, [],
    `以下字段前端会发送但服务端未声明，会导致保存草稿整体失败：${unknown.join(', ')}`);
});

test('服务端草稿 DTO 的必填字段前端都提供了（防止少发导致能力失效）', async () => {
  const repoRoot = new URL('../../', import.meta.url);
  const serverSource = await readFile(
    new URL('service/toolbox-service/src/main/java/com/yuyutian/mytools/toolbox/service/admin/validation/AdminTextbookDraft.java', repoRoot),
    'utf8');
  const vueSource = await readFile(
    new URL('../src/views/admin/TextbookEditorView.vue', import.meta.url), 'utf8');
  const buildDraft = vueSource.slice(
    vueSource.indexOf('function buildDraft'),
    vueSource.indexOf('编辑器行变化'));

  assert.equal(buildDraft.trim().length > 0, true, '未能提取到 buildDraft 函数体');
  // 顶层整份草稿字段必须都在请求体里出现。
  for (const field of ['editionId', 'subject', 'grade', 'semester', 'coverageDeclaration',
    'units', 'lessons', 'entries', 'idMapping']) {
    assert.equal(buildDraft.includes(`${field}:`), true,
      `请求体缺少 ${field}：服务端保存的是整份草稿，缺字段会清掉已有值`);
  }
  // idMapping 尤其重要：固定发空对象会抹掉服务端持久化的 ID 映射。
  assert.equal(/idMapping:\s*\{\s*\}/.test(buildDraft), false,
    'idMapping 不得固定发送空对象——那会抹掉服务端持久化的正式 ID 映射');
  void serverSource;
});
