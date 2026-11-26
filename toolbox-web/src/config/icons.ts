import type { Component } from 'vue';
import {
  BookOutline, CreateOutline, DocumentTextOutline, GridOutline, HomeOutline,
  ImagesOutline, LayersOutline, LogoMarkdown, PrintOutline, ReaderOutline,
  ScanOutline, TextOutline
} from '@vicons/ionicons5';

/**
 * 工具注册表中的图标名称到组件的映射。
 * 注册表只保存图标名称，避免配置层直接依赖组件实现。
 */
const iconMap: Record<string, Component> = {
  home: HomeOutline,
  document: DocumentTextOutline,
  pencil: CreateOutline,
  book: BookOutline,
  image: ImagesOutline,
  text: TextOutline,
  markdown: LogoMarkdown,
  merge: LayersOutline,
  split: GridOutline,
  crop: ScanOutline,
  render: ImagesOutline,
  extract: ReaderOutline,
  print: PrintOutline
};

/** 按名称取得图标组件，未登记时回退为文档图标。 */
export function toolIcon(name: string): Component {
  return iconMap[name] ?? DocumentTextOutline;
}
