import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import WorksheetPairedEditor from '../../src/components/worksheet/WorksheetPairedEditor.vue';
import type { EditableWorksheetPair } from '../../src/domain/worksheet-editable-pairs';

enableAutoUnmount(afterEach);

/** 模拟管理页每次输入回填行号；保留真实编辑器与真实文本框。 */
function mountEditor(initial: EditableWorksheetPair[], autoAppend = true, subject: 'chinese' | 'english' = 'chinese') {
  const rows = ref(initial);
  let nextId = 100;
  const Host = defineComponent({
    setup() {
      return () => h(WorksheetPairedEditor, {
        subject, rows: rows.value, paginated: false, autoAppend,
        'onUpdate:rows': (next: EditableWorksheetPair[]) => {
          rows.value = next.map(row => row.text || row.pinyin || row.meaning || row.english
            ? { ...row, tokenId: row.tokenId ?? `row-${nextId++}` } : row);
        }
      });
    }
  });
  return { wrapper: mount(Host, { attachTo: document.body }), rows };
}

/** 从原来的 DOM 节点继续输入，焦点和节点身份必须始终相同。 */
async function typeInto(input: HTMLTextAreaElement, value: string): Promise<void> {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
  expect(document.activeElement).toBe(input);
  expect(input.isConnected).toBe(true);
}

describe('双列编辑器保持连续输入的 DOM 身份', () => {
  it('首次按键分配 tokenId 后连续输入仍在同一文本框', async () => {
    const { wrapper, rows } = mountEditor([{}]);
    const input = wrapper.findAll('textarea')[0]!.element;
    input.focus();

    await typeInto(input, '春');
    await typeInto(input, '春天');
    await typeInto(input, '春天来了');

    expect(wrapper.findAll('textarea')[0]!.element).toBe(input);
    expect(rows.value[0]!.text).toBe('春天来了');
    expect(rows.value[0]!.tokenId).toBeTruthy();
  });

  it('自动新增虚拟末行在首次输入及填完两栏后均保持焦点', async () => {
    const { wrapper, rows } = mountEditor([{ tokenId: 'first', text: '山', pinyin: 'shān' }]);
    const left = wrapper.findAll('textarea')[2]!.element;
    left.focus();
    await typeInto(left, '水');
    expect(wrapper.findAll('.pair-row')).toHaveLength(3);
    expect(rows.value).toHaveLength(2);
    await typeInto(left, '水面');

    const right = wrapper.findAll('textarea')[3]!.element;
    right.focus();
    await typeInto(right, 'shuǐ miàn');

    expect(wrapper.findAll('.pair-row')).toHaveLength(3);
    expect(wrapper.findAll('textarea')[2]!.element).toBe(left);
    expect(wrapper.findAll('textarea')[3]!.element).toBe(right);
    expect(rows.value[1]!.text).toBe('水面');
    const nextBlank = wrapper.findAll('textarea')[4]!.element;
    expect(nextBlank).not.toBe(left);
    expect(nextBlank.value).toBe('');
    nextBlank.focus();
    await typeInto(nextBlank, '火');
    expect(rows.value.map(row => row.text)).toEqual(['山', '水面', '火']);
  });

  it.each([
    ['chinese', 0, '春天'], ['chinese', 1, 'chūn tiān'],
    ['english', 0, '苹果'], ['english', 1, 'apple']
  ] as const)('%s 第 %i 栏单独输入即显示备用行，并保留原节点焦点', async (subject, column, value) => {
    const { wrapper, rows } = mountEditor([{}], true, subject);
    const input = wrapper.findAll('textarea')[column]!.element;
    input.focus();
    await typeInto(input, ' ');
    expect(wrapper.findAll('.pair-row')).toHaveLength(1);
    await typeInto(input, value);
    expect(wrapper.findAll('.pair-row')).toHaveLength(2);
    expect(rows.value).toHaveLength(1);
    expect(wrapper.findAll('textarea')[column]!.element).toBe(input);
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0);
    await typeInto(input, '');
    expect(wrapper.findAll('.pair-row')).toHaveLength(1);
  });

  it('删除前行不会把后行的文本框、焦点或内容错误复用', async () => {
    const { wrapper, rows } = mountEditor([
      { text: '山', pinyin: 'shān' }, { text: '水', pinyin: 'shuǐ' }, { text: '火', pinyin: 'huǒ' }
    ], false);
    const second = wrapper.findAll('textarea')[2]!.element;
    second.focus();
    await wrapper.get('button[aria-label="删除第 1 行"]').trigger('click');
    await nextTick();

    expect(wrapper.findAll('textarea')[0]!.element).toBe(second);
    expect(document.activeElement).toBe(second);
    await typeInto(second, '水面');
    expect(rows.value.map(row => row.text)).toEqual(['水面', '火']);
    expect(rows.value.map(row => row.pinyin)).toEqual(['shuǐ', 'huǒ']);
  });

  it('自动补全式对象展开与业务行重建均保留已聚焦节点', async () => {
    const { wrapper, rows } = mountEditor([{}]);
    const input = wrapper.findAll('textarea')[0]!.element;
    input.focus();
    await typeInto(input, '山');

    rows.value = rows.value.map(row => ({ ...row, pinyin: 'shān', automaticInitialized: true }));
    await flushPromises();
    expect(wrapper.findAll('textarea')[0]!.element).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(wrapper.findAll('.pair-row')).toHaveLength(2);

    rows.value = rows.value.map(row => ({ tokenId: row.tokenId, text: row.text, pinyin: row.pinyin }));
    await nextTick();
    await typeInto(input, '山水');
    expect(rows.value[0]!.pinyin).toBe('shān');
  });

  it('从虚拟末行粘贴多行继续保留当前文本框及空行对应', async () => {
    const { wrapper, rows } = mountEditor([{ tokenId: 'first', text: '山', pinyin: 'shān' }]);
    const input = wrapper.findAll('textarea')[2]!.element;
    input.focus();
    await typeInto(input, '水\n\n火');

    expect(wrapper.findAll('textarea')[2]!.element).toBe(input);
    expect(rows.value.map(row => row.text ?? '')).toEqual(['山', '水', '', '火']);
    expect(rows.value.map(row => row.pinyin ?? '')).toEqual(['shān', '', '', '']);
    expect(wrapper.findAll('.pair-row')).toHaveLength(5);
  });
});
