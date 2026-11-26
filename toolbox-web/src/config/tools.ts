import type { ToolCategory, WorksheetMode } from '../types';

/**
 * 工具注册表：左侧目录、首页入口、搜索索引与统计分组共用同一份定义。
 * 新增工具只需在此登记，不修改多处名单。
 */
export interface ToolDefinition {
  /** 稳定英文工具标识，用于统计与服务端 operation 映射。 */
  id: string;
  /** 一级目录分类。 */
  category: ToolCategory;
  /** 前端逻辑路由；配合 hash 路由使用。 */
  route: string;
  /** 界面名称的文案键，实际中文集中在 locales 中。 */
  labelKey: string;
  /** 一句话说明的文案键。 */
  descriptionKey: string;
  /** 图标名称，由 components 层的图标表解析为组件。 */
  icon: string;
  /** 是否对访客开放；关闭后从导航、搜索与统计分组中移除。 */
  enabled: boolean;
  /** 同一分类内的展示顺序。 */
  order: number;
  /** 字帖类工具对应的练习模式，PDF 工具为空。 */
  worksheetMode?: WorksheetMode;
}

/** 一级目录定义，驱动左侧导航与首页入口。 */
export interface CategoryDefinition {
  /** 目录标识，首页使用 home。 */
  id: ToolCategory | 'home';
  /** 目录路由。 */
  route: string;
  /** 目录名称的文案键。 */
  labelKey: string;
  /** 目录说明的文案键。 */
  descriptionKey: string;
  /** 图标名称。 */
  icon: string;
  /** 展示顺序。 */
  order: number;
}

/** PDF 工具在公开服务端对应的操作名，供服务端执行适配层使用。 */
export const PDF_OPERATIONS: Readonly<Record<string, string>> = Object.freeze({
  'pdf.images': 'IMAGES_TO_PDF',
  'pdf.text': 'TEXT_TO_PDF',
  'pdf.markdown': 'MARKDOWN_TO_PDF',
  'pdf.merge': 'PDF_MERGE',
  'pdf.split': 'PDF_SPLIT',
  'pdf.crop': 'PDF_PAGE_CUT',
  'pdf.render': 'PDF_TO_IMAGES',
  'pdf.extract': 'PDF_TO_TEXT'
});

/** 全部工具定义，顺序稳定，供注册表查询使用。 */
export const TOOL_DEFINITIONS: readonly ToolDefinition[] = Object.freeze([
  { id: 'pdf.images', category: 'pdf', route: '/pdf/images', labelKey: 'toolPdfImages', descriptionKey: 'toolPdfImagesDesc', icon: 'image', enabled: true, order: 10 },
  { id: 'pdf.text', category: 'pdf', route: '/pdf/text', labelKey: 'toolPdfText', descriptionKey: 'toolPdfTextDesc', icon: 'text', enabled: true, order: 20 },
  { id: 'pdf.markdown', category: 'pdf', route: '/pdf/markdown', labelKey: 'toolPdfMarkdown', descriptionKey: 'toolPdfMarkdownDesc', icon: 'markdown', enabled: true, order: 30 },
  { id: 'pdf.merge', category: 'pdf', route: '/pdf/merge', labelKey: 'toolPdfMerge', descriptionKey: 'toolPdfMergeDesc', icon: 'merge', enabled: true, order: 40 },
  { id: 'pdf.split', category: 'pdf', route: '/pdf/split', labelKey: 'toolPdfSplit', descriptionKey: 'toolPdfSplitDesc', icon: 'split', enabled: true, order: 50 },
  { id: 'pdf.crop', category: 'pdf', route: '/pdf/crop', labelKey: 'toolPdfCrop', descriptionKey: 'toolPdfCropDesc', icon: 'crop', enabled: true, order: 60 },
  { id: 'pdf.render', category: 'pdf', route: '/pdf/render', labelKey: 'toolPdfRender', descriptionKey: 'toolPdfRenderDesc', icon: 'render', enabled: true, order: 70 },
  { id: 'pdf.extract', category: 'pdf', route: '/pdf/extract', labelKey: 'toolPdfExtract', descriptionKey: 'toolPdfExtractDesc', icon: 'extract', enabled: true, order: 80 },
  { id: 'worksheet.chinese-dictation', category: 'copybook', route: '/copybook', labelKey: 'modeChineseDictation', descriptionKey: 'modeChineseDictationDesc', icon: 'pencil', enabled: true, order: 10, worksheetMode: 'chinese-dictation' },
  { id: 'worksheet.chinese-pinyin', category: 'copybook', route: '/copybook', labelKey: 'modeChinesePinyin', descriptionKey: 'modeChinesePinyinDesc', icon: 'pencil', enabled: true, order: 20, worksheetMode: 'chinese-pinyin' },
  { id: 'worksheet.english-dictation', category: 'words', route: '/words', labelKey: 'modeEnglishDictation', descriptionKey: 'modeEnglishDictationDesc', icon: 'book', enabled: true, order: 10, worksheetMode: 'english-dictation' },
  { id: 'worksheet.english-meaning', category: 'words', route: '/words', labelKey: 'modeEnglishMeaning', descriptionKey: 'modeEnglishMeaningDesc', icon: 'book', enabled: true, order: 20, worksheetMode: 'english-meaning' }
]);

/** 一级目录定义，首页单独一条。 */
export const CATEGORY_DEFINITIONS: readonly CategoryDefinition[] = Object.freeze([
  { id: 'home', route: '/', labelKey: 'home', descriptionKey: 'heroSub', icon: 'home', order: 0 },
  { id: 'pdf', route: '/pdf', labelKey: 'pdf', descriptionKey: 'pdfDesc', icon: 'document', order: 10 },
  { id: 'copybook', route: '/copybook', labelKey: 'copybook', descriptionKey: 'copyDesc', icon: 'pencil', order: 20 },
  { id: 'words', route: '/words', labelKey: 'words', descriptionKey: 'wordsDesc', icon: 'book', order: 30 }
]);

/** 按分类返回已启用工具，顺序固定。 */
export function toolsByCategory(category: ToolCategory): ToolDefinition[] {
  return TOOL_DEFINITIONS
    .filter(tool => tool.enabled && tool.category === category)
    .slice()
    .sort((left, right) => left.order - right.order);
}

/** 按标识查找已启用工具，未登记或已关闭时返回 undefined。 */
export function findTool(toolId: string | null | undefined): ToolDefinition | undefined {
  if (!toolId) return undefined;
  return TOOL_DEFINITIONS.find(tool => tool.id === toolId && tool.enabled);
}

/** 按分类与练习模式查找字帖工具。 */
export function findWorksheetTool(category: ToolCategory, mode: WorksheetMode): ToolDefinition | undefined {
  return TOOL_DEFINITIONS.find(tool => tool.enabled && tool.category === category && tool.worksheetMode === mode);
}
