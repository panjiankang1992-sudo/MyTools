import { webcrypto } from 'node:crypto';
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { NInput, NSelect } from 'naive-ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick } from 'vue';
import type { EditableWorksheetPair } from '../../src/domain/worksheet-editable-pairs';
import type { AdminDraftResult } from '../../src/services/admin-textbooks-api';
import type { DraftEntry } from '../../src/lib/admin-textbook-draft';

const api = vi.hoisted(() => ({
  createTextbook: vi.fn(), fetchDraft: vi.fn(), saveDraft: vi.fn(), publishDraft: vi.fn(), rebaseDraft: vi.fn(),
  validateDraft: vi.fn(), translateEntriesForAdmin: vi.fn()
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), onBeforeRouteLeave: vi.fn() }));
vi.mock('../../src/services/admin-textbooks-api', () => api);

import TextbookEntryView from '../../src/views/admin/TextbookEntryView.vue';
import { useAdminAuthStore } from '../../src/stores/admin-auth';
import { AdminApiError } from '../../src/services/admin-api';

// 只替换双列子控件；页面、状态机、范围合并、摘要和补全逻辑都用实际实现。
const PairedRecorder = defineComponent({
  name: 'PairedRecorder',
  props: { subject: String, rows: Array, paginated: Boolean, autoAppend: Boolean },
  emits: ['update:rows', 'commit'],
  setup(props) { return () => h('div', { 'data-paired-recorder': '' }, JSON.stringify(props.rows)); }
});

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());

/** 保留可控异步边界，不以固定延时假装验证并发。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((accept, decline) => { resolve = accept; reject = decline; });
  return { promise, resolve, reject };
}

function entry(rowId: string, owner: string, text: string, listTypes = ['recognition']): DraftEntry {
  return { rowId, entryId: `entry-${rowId}`, unitId: `published-${owner}`, clientUnitId: owner,
    lessonId: null, clientLessonId: null, text, pinyin: 'shān', meaning: null,
    listTypes, confirmed: false, automaticInitialized: true };
}

function fixture(entries = [entry('r1', 'u1', '山')], revision = 4): AdminDraftResult {
  return { revision, baseContentVersion: 'version-1', draft: {
    editionId: 'book-1', subject: 'chinese', grade: 1, semester: 'first', series: '统编语文',
    sourceIds: ['original-source'], sources: [{ sourceId: 'original-source', title: '原书附录' }],
    preservedMetadata: { verifiedAt: '2026-10-01', customData: { edition: 'first' } },
    lessons: [{ lessonId: 'lesson-1', clientLessonId: 'cl1', title: '原始课文' }],
    units: [{ unitId: 'published-u1', clientUnitId: 'u1', title: '第一单元', order: 1 },
      { unitId: 'published-u2', clientUnitId: 'u2', title: '第二单元', order: 2 }], entries
  } };
}

function mountEntry(editionId?: string) {
  return mount(TextbookEntryView, {
    props: { editionId }, global: { stubs: { WorksheetPairedEditor: PairedRecorder } }
  });
}

async function select(wrapper: VueWrapper, label: string, value: unknown) {
  const control = wrapper.findAllComponents(NSelect).find(item => item.vm.$attrs['aria-label'] === label);
  expect(control, `必须提供 ${label} 选择`).toBeTruthy();
  control!.vm.$emit('update:value', value);
  await nextTick();
}

async function click(wrapper: VueWrapper, label: string) {
  const button = wrapper.findAll('button').find(item => item.text() === label);
  expect(button, `必须提供 ${label} 操作`).toBeTruthy();
  await button!.trigger('click');
}

function renderedRows(wrapper: VueWrapper): EditableWorksheetPair[] {
  return wrapper.findComponent(PairedRecorder).props('rows') as EditableWorksheetPair[];
}

async function inputRows(wrapper: VueWrapper, rows: EditableWorksheetPair[]) {
  wrapper.findComponent(PairedRecorder).vm.$emit('update:rows', rows);
  await nextTick();
}

async function selectScope(wrapper: VueWrapper, unit = 'u1', type = '识字表') {
  await select(wrapper, '单元', unit);
  await click(wrapper, type);
}

async function fillNew(wrapper: VueWrapper, subject = 'chinese') {
  await select(wrapper, '科目', subject);
  await select(wrapper, '年级', subject === 'chinese' ? 1 : 3);
  await select(wrapper, '学期', 'second');
  const name = wrapper.findComponent(NInput);
  name.vm.$emit('update:value', subject === 'chinese' ? '统编语文' : '译林英语');
  await nextTick();
  await selectScope(wrapper, subject === 'chinese' ? '第一单元' : 'Unit 1', subject === 'chinese' ? '识字表' : '单词表');
  await inputRows(wrapper, subject === 'chinese'
    ? [{ text: '山', pinyin: 'shān', automaticInitialized: true }]
    : [{ meaning: '苹果', english: 'apple', automaticInitialized: true }]);
}

beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset());
  setActivePinia(createPinia());
  const auth = useAdminAuthStore();
  auth.status = 'admin'; auth.userId = 'admin-1';
  vi.stubGlobal('crypto', webcrypto);
  api.createTextbook.mockResolvedValue({ editionId: 'created-book', draftId: 'draft-1', revision: 1 });
  api.saveDraft.mockResolvedValue({ revision: 5 });
  api.publishDraft.mockResolvedValue({ contentVersion: 'version-2', snapshotReloaded: true });
  api.translateEntriesForAdmin.mockResolvedValue([]);
  api.fetchDraft.mockResolvedValue(fixture());
  api.rebaseDraft.mockResolvedValue({ ...fixture(undefined, 6), baseContentVersion: 'version-2' });
});

describe('简洁教材录入真实页面', () => {
  it('保存发布先等待管理教材库补音，采用教材读音并保留人工修改', async () => {
    const lookup = deferred<Array<Record<string, unknown>>>();
    api.fetchDraft.mockResolvedValue(fixture([{ ...entry('r1', 'u1', '长'), pinyin: null, automaticInitialized: false }]));
    api.translateEntriesForAdmin.mockReturnValue(lookup.promise);
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.translateEntriesForAdmin).toHaveBeenCalledOnce());
    const [items, _signal, subject] = api.translateEntriesForAdmin.mock.calls[0]!;
    expect(subject).toBe('chinese');
    expect(api.saveDraft).not.toHaveBeenCalled();
    lookup.resolve([{ id: items[0].id, sourceText: '长', englishText: null, meaning: null, status: 'translated',
      pinyinSegments: [{ grapheme: '长', syllable: 'zhǎng' }] }]);
    await vi.waitFor(() => expect(api.saveDraft).toHaveBeenCalledOnce());
    expect(api.saveDraft.mock.calls[0]?.[2].entries[0]).toMatchObject({ text: '长', pinyin: 'zhǎng', automaticInitialized: true });
    await flushPromises();
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '行', pinyin: 'háng', automaticInitialized: true }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.saveDraft).toHaveBeenCalledTimes(2));
    expect(api.translateEntriesForAdmin).toHaveBeenCalledOnce();
    expect(api.saveDraft.mock.calls[1]?.[2].entries[0]).toMatchObject({ text: '行', pinyin: 'háng' });
  });

  it.each(['chinese', 'english'])('只填写基本选择及配对内容即可完成 %s 新建、发布、刷新', async subject => {
    let submitted: Record<string, unknown> = {};
    api.createTextbook.mockImplementation(async (payload: Record<string, unknown>) => {
      submitted = JSON.parse(JSON.stringify(payload)) as Record<string, unknown>;
      return { editionId: 'created-book', draftId: 'draft-1', revision: 1 };
    });
    api.fetchDraft.mockImplementation(async () => ({ draft: submitted, revision: 2, baseContentVersion: 'version-2' }));
    const wrapper = mountEntry();
    await fillNew(wrapper, subject);
    const editor = wrapper.findComponent(PairedRecorder);
    expect(editor.props('paginated')).toBe(false);
    expect(editor.props('autoAppend')).toBe(true);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('保存发布成功'));
    expect(api.createTextbook).toHaveBeenCalledOnce();
    expect(api.saveDraft).not.toHaveBeenCalled();
    expect(api.validateDraft).not.toHaveBeenCalled();
    expect(api.publishDraft).toHaveBeenCalledWith('created-book', {
      draftRevision: 1, baseContentVersion: null, changeNote: ''
    }, expect.any(String));
    expect(api.fetchDraft).toHaveBeenCalledWith('created-book');
    expect(api.createTextbook.mock.invocationCallOrder[0]).toBeLessThan(api.publishDraft.mock.invocationCallOrder[0]!);
    expect(api.publishDraft.mock.invocationCallOrder[0]).toBeLessThan(api.fetchDraft.mock.invocationCallOrder[0]!);
    const saved = (submitted.entries as DraftEntry[])[0]!;
    expect(saved.listTypes).toEqual([subject === 'chinese' ? 'recognition' : 'vocabulary']);
    expect(saved.confirmed).toBe(true);
    expect(saved.confirmedDigest).toMatch(/^[a-f0-9]{64}$/);
    expect(saved.clientUnitId).toBe((submitted.units as Array<{ clientUnitId: string }>)[0]!.clientUnitId);
    expect(saved.text).toBe(subject === 'chinese' ? '山' : 'apple');
    expect(wrapper.text()).not.toContain('保存草稿');
    expect(wrapper.text()).not.toContain('校验教材');
  });

  it('切换单元及类型保留完整草稿、来源、隐藏条目和未确认状态', async () => {
    const visible = { ...entry('r1', 'u1', '山'),
      sourceRefs: [{ sourceId: 'original-source', locator: '识字表', page: 51 }],
      preserved: { sourceRefs: [{ sourceId: 'original-source', locator: '原书', page: 51 }], senseId: 'sense-1' } };
    const hidden = entry('r-hidden', 'u2', '水');
    const anotherType = entry('r-vocabulary', 'u1', '山水', ['vocabulary']);
    const original = fixture([visible, hidden, anotherType]);
    api.fetchDraft.mockResolvedValue(original);
    api.publishDraft.mockRejectedValue(new Error('network'));
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '词语表');
    expect(renderedRows(wrapper).map(row => row.text)).toEqual(['山水']);
    await select(wrapper, '单元', 'u2');
    await click(wrapper, '识字表');
    expect(renderedRows(wrapper).map(row => row.text)).toEqual(['水']);
    await select(wrapper, '单元', 'u1');
    expect(renderedRows(wrapper)[0]?.text).toBe('高山');
    await inputRows(wrapper, [...renderedRows(wrapper), { text: '天', pinyin: 'tiān', automaticInitialized: true }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.saveDraft).toHaveBeenCalledOnce());
    const payload = api.saveDraft.mock.calls[0]![2] as Record<string, unknown>;
    for (const key of ['sourceIds', 'sources', 'preservedMetadata', 'lessons']) expect(payload[key]).toEqual(original.draft[key]);
    const saved = payload.entries as Array<DraftEntry & { sourceRefs?: unknown }>;
    expect(saved).toHaveLength(4);
    expect(saved.find(row => row.rowId === 'r1')).toMatchObject({ text: '高山', sourceRefs: visible.sourceRefs, preserved: visible.preserved });
    expect(saved.find(row => row.rowId === hidden.rowId)).toEqual(hidden);
    expect(saved.find(row => row.rowId === anotherType.rowId)).toEqual(anotherType);
    expect(saved.find(row => row.text === '天')).toMatchObject({ clientUnitId: 'u1', unitId: 'published-u1', listTypes: ['recognition'] });
    expect(saved.find(row => row.rowId === hidden.rowId)?.confirmed).toBe(false);
    expect(saved.find(row => row.rowId === anotherType.rowId)?.confirmed).toBe(false);
  });

  it('发布后刷新修订号及版本基线，再次编辑使用最新修订保存', async () => {
    api.fetchDraft.mockResolvedValueOnce(fixture()).mockResolvedValue({ ...fixture(undefined, 6), baseContentVersion: 'version-2' });
    api.saveDraft.mockResolvedValueOnce({ revision: 5 }).mockResolvedValueOnce({ revision: 7 });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.fetchDraft).toHaveBeenCalledTimes(2));
    await flushPromises();
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledTimes(2));
    expect(api.saveDraft.mock.calls[0]?.slice(0, 2)).toEqual(['book-1', 4]);
    expect(api.saveDraft.mock.calls[1]?.slice(0, 2)).toEqual(['book-1', 6]);
    expect(api.publishDraft.mock.calls[1]?.[1]).toMatchObject({ draftRevision: 7, baseContentVersion: 'version-2' });
  });

  it('连续点击保存发布只执行一组创建及发布请求', async () => {
    const pending = deferred<{ editionId: string; revision: number }>();
    api.createTextbook.mockReturnValue(pending.promise);
    const wrapper = mountEntry();
    await fillNew(wrapper);
    await click(wrapper, '保存发布');
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.createTextbook).toHaveBeenCalledOnce());
    expect(api.publishDraft).not.toHaveBeenCalled();
    pending.resolve({ editionId: 'created-book', revision: 1 });
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledOnce());
    expect(api.createTextbook).toHaveBeenCalledOnce();
  });

  it('发布失败保留输入，重试复用同一发布意图且不重复保存', async () => {
    api.publishDraft.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ snapshotReloaded: true });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('输入已保留'));
    expect(renderedRows(wrapper)[0]).toMatchObject({ text: '高山', pinyin: 'gāo shān' });
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '迟到编辑', pinyin: 'chí dào biān jí' }]);
    expect(renderedRows(wrapper)[0]?.text).toBe('高山');
    const firstPublish = api.publishDraft.mock.calls[0];
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledTimes(2));
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft.mock.calls[1]).toEqual(firstPublish);
  });

  it('草稿基线过期自动安全对齐一次，保留录入及新版条目并采用新基线发布', async () => {
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineMismatch' }, 409))
      .mockResolvedValueOnce({ contentVersion: 'version-3', snapshotReloaded: true });
    const aligned = { ...fixture([{ ...entry('r1', 'u1', '高山'), pinyin: 'gāo shān' }, entry('published-new', 'u1', '河')], 6), baseContentVersion: 'version-2' };
    api.rebaseDraft.mockResolvedValue(aligned);
    api.fetchDraft.mockResolvedValueOnce(fixture()).mockResolvedValue({ ...aligned, revision: 7, baseContentVersion: 'version-3' });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('保存发布成功'));
    expect(wrapper.text()).not.toContain('其他人');
    expect(renderedRows(wrapper)[0]).toMatchObject({ text: '高山', pinyin: 'gāo shān' });
    expect(renderedRows(wrapper)[1]?.text).toBe('河');
    expect(api.rebaseDraft).toHaveBeenCalledWith('book-1', 5, expect.any(String));
    expect(api.rebaseDraft).toHaveBeenCalledOnce();
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft).toHaveBeenCalledTimes(2);
    expect(api.publishDraft.mock.calls[1]?.[1]).toEqual({ draftRevision: 6, baseContentVersion: 'version-2', changeNote: '' });
    expect(api.publishDraft.mock.calls[1]?.[2]).not.toBe(api.publishDraft.mock.calls[0]?.[2]);
  });

  it('对齐网络结果未知时保留输入，下次重试同一标识且不重复保存', async () => {
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineMismatch' }, 409))
      .mockResolvedValueOnce({ contentVersion: 'version-3', snapshotReloaded: true });
    api.rebaseDraft.mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ ...fixture([{ ...entry('r1', 'u1', '高山'), pinyin: 'gāo shān' }], 6), baseContentVersion: 'version-2' });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('输入已保留'));
    const initialRebase = api.rebaseDraft.mock.calls[0];
    expect(renderedRows(wrapper)[0]?.text).toBe('高山');
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
    expect(api.publishDraft).toHaveBeenCalledOnce();
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledTimes(2));
    expect(api.rebaseDraft).toHaveBeenCalledTimes(2);
    expect(api.rebaseDraft.mock.calls[1]).toEqual(initialRebase);
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft.mock.calls[1]?.[1]).toMatchObject({ draftRevision: 6, baseContentVersion: 'version-2' });
  });

  it('连续发布基线变化时只自动对齐一次，不能无限重试或强行覆盖', async () => {
    api.publishDraft.mockRejectedValue(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineMismatch' }, 409));
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('草稿与已发布版本尚未同步'));
    expect(api.publishDraft).toHaveBeenCalledTimes(2);
    expect(api.rebaseDraft).toHaveBeenCalledOnce();
    expect(api.fetchDraft).toHaveBeenCalledOnce();
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
  });

  it('双方修改同一内容时显示具体冲突项，保留用户输入且不发布', async () => {
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineMismatch' }, 409));
    api.rebaseDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', {
      reason: 'rebaseConflict', conflicts: [{ path: 'entries.entry-r1.pinyin', entityId: 'entry-r1', field: 'pinyin', reason: 'bothChanged' }]
    }, 409));
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('无法自动合并'));
    expect(wrapper.text()).toContain('“高山”的拼音');
    expect(wrapper.text()).not.toContain('其他人');
    expect(renderedRows(wrapper)[0]).toMatchObject({ text: '高山', pinyin: 'gāo shān' });
    expect(api.publishDraft).toHaveBeenCalledOnce();
    expect(api.rebaseDraft).toHaveBeenCalledOnce();
    expect(api.fetchDraft).toHaveBeenCalledOnce();
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
  });

  it('退出账号后不接受对齐迟到结果，也不继续发布', async () => {
    const pending = deferred<AdminDraftResult>();
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineMismatch' }, 409));
    api.rebaseDraft.mockReturnValue(pending.promise);
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.rebaseDraft).toHaveBeenCalledOnce());
    useAdminAuthStore().clearLocal();
    await nextTick();
    pending.resolve(fixture(undefined, 6));
    await flushPromises();
    expect(api.publishDraft).toHaveBeenCalledOnce();
    expect(wrapper.findComponent(PairedRecorder).exists()).toBe(false);
    expect(wrapper.text()).not.toContain('保存发布成功');
  });

  it.each(['revisionConflict', 'revisionMismatch'])('真实修订冲突 %s 保留输入且不自动覆盖', async reason => {
    api.saveDraft.mockRejectedValue(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason }, 409));
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('教材草稿版本已变化'));
    expect(renderedRows(wrapper)[0]?.text).toBe('高山');
    expect(wrapper.text()).not.toContain('其他人');
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.fetchDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft).not.toHaveBeenCalled();
  });

  it('发布禁用不能解释为教材被人修改，配置恢复后复用原意图重试', async () => {
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_011', 'admin.publish.disabled', { reason: 'repositoryIsFiles' }, 409))
      .mockResolvedValueOnce({ snapshotReloaded: true });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('尚未启用教材发布'));
    expect(wrapper.text()).not.toContain('其他人');
    expect(wrapper.text()).not.toContain('重新读取');
    expect(renderedRows(wrapper)[0]?.text).toBe('山');
    const original = api.publishDraft.mock.calls[0];
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledTimes(2));
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft.mock.calls[1]).toEqual(original);
  });

  it('服务端明确拒绝重复请求标识后只更换发布键，不重新保存或改发布正文', async () => {
    api.publishDraft.mockRejectedValueOnce(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'idempotencyKeyReused' }, 409))
      .mockResolvedValueOnce({ snapshotReloaded: true });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(wrapper.text()).toContain('请求标识与之前的操作重复'));
    expect(renderedRows(wrapper)[0]?.text).toBe('山');
    const original = api.publishDraft.mock.calls[0]!;
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledTimes(2));
    expect(api.saveDraft).toHaveBeenCalledOnce();
    expect(api.publishDraft.mock.calls[1]?.slice(0, 2)).toEqual(original.slice(0, 2));
    expect(api.publishDraft.mock.calls[1]?.[2]).not.toBe(original[2]);
  });

  it('退出账号清除输入并拒绝未完成创建的迟到回包', async () => {
    const pending = deferred<{ editionId: string; revision: number }>();
    api.createTextbook.mockReturnValue(pending.promise);
    const wrapper = mountEntry();
    await fillNew(wrapper);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.createTextbook).toHaveBeenCalledOnce());
    useAdminAuthStore().clearLocal();
    await nextTick();
    expect(wrapper.findComponent(PairedRecorder).exists()).toBe(false);
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
    pending.resolve({ editionId: 'created-book', revision: 1 });
    await flushPromises();
    expect(api.publishDraft).not.toHaveBeenCalled();
    expect(api.fetchDraft).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain('保存发布成功');
  });

  it('会话失效后初次加载的迟到内容不重新进入页面', async () => {
    const pending = deferred<AdminDraftResult>();
    api.fetchDraft.mockReturnValue(pending.promise);
    const wrapper = mountEntry('book-1');
    expect(api.fetchDraft).toHaveBeenCalledOnce();
    useAdminAuthStore().clearLocal();
    await nextTick();
    pending.resolve(fixture());
    await flushPromises();
    expect(wrapper.text()).not.toContain('统编语文');
    expect(wrapper.findComponent(PairedRecorder).exists()).toBe(false);
    expect(wrapper.findAllComponents(NSelect).find(item => item.vm.$attrs['aria-label'] === '单元')?.props('options')).toEqual([]);
  });

  it('保存收到 401 冻结输入，同账号恢复后保留内容并能继续发布', async () => {
    api.saveDraft.mockRejectedValueOnce(new AdminApiError('AUTH_001', 'admin.auth.expired', undefined, 401))
      .mockResolvedValueOnce({ revision: 5 });
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{ ...renderedRows(wrapper)[0], text: '高山', pinyin: 'gāo shān' }]);
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(useAdminAuthStore().status).toBe('expired'));
    expect(renderedRows(wrapper)[0]).toMatchObject({ text: '高山', pinyin: 'gāo shān' });
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined();
    expect(api.publishDraft).not.toHaveBeenCalled();
    useAdminAuthStore().status = 'admin';
    await nextTick();
    expect(renderedRows(wrapper)[0]?.text).toBe('高山');
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.publishDraft).toHaveBeenCalledOnce());
    expect(api.saveDraft).toHaveBeenCalledTimes(2);
    expect((api.saveDraft.mock.calls[1]?.[2].entries as DraftEntry[])[0]?.text).toBe('高山');
  });

  it('换账号清空旧输入，旧账号加载迟到时不能覆盖新账号结果', async () => {
    const oldLoad = deferred<AdminDraftResult>();
    const newLoad = deferred<AdminDraftResult>();
    api.fetchDraft.mockReturnValueOnce(oldLoad.promise).mockReturnValueOnce(newLoad.promise);
    const wrapper = mountEntry('book-1');
    useAdminAuthStore().userId = 'admin-2';
    await nextTick();
    expect(api.fetchDraft).toHaveBeenCalledTimes(2);
    newLoad.resolve({ ...fixture(), draft: { ...fixture().draft, series: '新账号教材' } });
    await flushPromises();
    oldLoad.resolve({ ...fixture(), draft: { ...fixture().draft, series: '旧账号教材' } });
    await flushPromises();
    expect(wrapper.text()).toContain('新账号教材');
    expect(wrapper.text()).not.toContain('旧账号教材');
  });

  it('删除共用条目只移除当前表归属，其他类型中的内容仍可编辑', async () => {
    api.fetchDraft.mockResolvedValue(fixture([entry('shared', 'u1', '山', ['recognition', 'writing'])]));
    api.publishDraft.mockRejectedValue(new Error('offline'));
    const wrapper = mountEntry('book-1');
    await flushPromises();
    await selectScope(wrapper);
    await inputRows(wrapper, [{}]);
    await click(wrapper, '写字表');
    expect(renderedRows(wrapper)[0]?.text).toBe('山');
    await click(wrapper, '保存发布');
    await vi.waitFor(() => expect(api.saveDraft).toHaveBeenCalledOnce());
    const saved = api.saveDraft.mock.calls[0]?.[2].entries as DraftEntry[];
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({ rowId: 'shared', text: '山', listTypes: ['writing'] });
  });

  it('新空行首次输入不重建真实输入框，光标仍留在当前单元格', async () => {
    api.fetchDraft.mockResolvedValue(fixture([]));
    const wrapper = mount(TextbookEntryView, { props: { editionId: 'book-1' }, attachTo: document.body });
    await flushPromises();
    await selectScope(wrapper);
    const input = wrapper.find<HTMLTextAreaElement>('.pair-row .left-cell textarea');
    input.element.focus();
    expect(document.activeElement).toBe(input.element);
    await input.setValue('山');
    expect(wrapper.find('.pair-row .left-cell textarea').element).toBe(input.element);
    expect(document.activeElement).toBe(input.element);
  });
});
