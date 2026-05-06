<script setup lang="ts">
import { useEditor, EditorContent } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import Image from '@tiptap/extension-image';
import Link from '@tiptap/extension-link';
import { watch, onBeforeUnmount } from 'vue';

interface Props {
  modelValue: string;
  placeholder?: string;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  placeholder: '请输入内容...'
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const editor = useEditor({
  content: props.modelValue,
  extensions: [
    StarterKit,
    Image.configure({
      inline: true,
      allowBase64: true
    }),
    Link.configure({
      openOnClick: false
    })
  ],
  onUpdate: ({ editor }) => {
    emit('update:modelValue', editor.getHTML());
  }
});

// Watch for external modelValue changes
watch(() => props.modelValue, (newValue) => {
  if (editor.value && newValue !== editor.value.getHTML()) {
    editor.value.commands.setContent(newValue, false);
  }
});

onBeforeUnmount(() => {
  editor.value?.destroy();
});

function addImage() {
  const url = window.prompt('请输入图片URL:');
  if (url) {
    editor.value?.chain().focus().setImage({ src: url }).run();
  }
}
</script>

<template>
  <div class="tiptap-editor">
    <div class="editor-toolbar">
      <button type="button" @click="editor?.chain().focus().toggleBold().run()" :class="{ active: editor?.isActive('bold') }">B</button>
      <button type="button" @click="editor?.chain().focus().toggleItalic().run()" :class="{ active: editor?.isActive('italic') }">I</button>
      <button type="button" @click="editor?.chain().focus().toggleStrike().run()" :class="{ active: editor?.isActive('strike') }">S</button>
      <button type="button" @click="editor?.chain().focus().toggleHeading({ level: 2 }).run()" :class="{ active: editor?.isActive('heading', { level: 2 }) }">H2</button>
      <button type="button" @click="editor?.chain().focus().toggleBulletList().run()" :class="{ active: editor?.isActive('bulletList') }">UL</button>
      <button type="button" @click="editor?.chain().focus().toggleOrderedList().run()" :class="{ active: editor?.isActive('orderedList') }">OL</button>
      <button type="button" @click="addImage">图片</button>
    </div>
    <EditorContent :editor="editor" class="editor-content" />
  </div>
</template>

<style scoped>
.tiptap-editor {
  border: 1px solid #d9d9d9;
  border-radius: 4px;
}

.editor-toolbar {
  padding: 8px;
  border-bottom: 1px solid #d9d9d9;
  display: flex;
  gap: 4px;
}

.editor-toolbar button {
  padding: 4px 8px;
  border: 1px solid #d9d9d9;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
}

.editor-toolbar button:hover {
  background: #f5f5f5;
}

.editor-toolbar button.active {
  background: #e6f7ff;
  border-color: #1890ff;
}

.editor-content {
  min-height: 200px;
  padding: 12px;
}

.editor-content :deep(.ProseMirror) {
  min-height: 180px;
  outline: none;
}

.editor-content :deep(.ProseMirror p) {
  margin: 0.5em 0;
}
</style>