// 工具注册表使用的名称与说明文案，实际中文集中在此管理。
export const toolText = {
  "toolPdfImages": "\u56fe\u7247\u8f6c PDF",
  "toolPdfImagesDesc": "\u591a\u5f20\u7167\u7247\uff0c\u6574\u7406\u6210\u4e00\u4efd\u6587\u6863",
  "toolPdfText": "\u6587\u672c\u8f6c PDF",
  "toolPdfTextDesc": "\u8f93\u5165\u6587\u5b57\uff0c\u751f\u6210\u6e05\u6670\u7684\u7eb8\u9762",
  "toolPdfMarkdown": "Markdown \u8f6c PDF",
  "toolPdfMarkdownDesc": "\u628a\u8f7b\u91cf\u7b14\u8bb0\u5bfc\u51fa\u4e3a\u6587\u6863",
  "toolPdfMerge": "\u5408\u5e76 PDF",
  "toolPdfMergeDesc": "\u6309\u4f60\u7684\u987a\u5e8f\uff0c\u5408\u6210\u4e00\u4efd",
  "toolPdfSplit": "\u6587\u4ef6\u62c6\u5206",
  "toolPdfSplitDesc": "\u6309\u9875\u7801\u5206\u7ec4\uff0c\u62c6\u6210\u591a\u4efd\u6587\u4ef6",
  "toolPdfCrop": "\u9875\u9762\u5206\u5272",
  "toolPdfCropDesc": "\u5de6\u53f3\u6216\u4e0a\u4e0b\uff0c\u628a\u4e00\u9875\u5206\u6210\u4e24\u9875",
  "toolPdfRender": "PDF \u8f6c\u56fe\u7247",
  "toolPdfRenderDesc": "\u6309\u9700\u9009\u9875\uff0c\u5bfc\u51fa\u6e05\u6670\u56fe\u7247",
  "toolPdfExtract": "PDF \u63d0\u53d6\u6587\u672c",
  "toolPdfExtractDesc": "\u63d0\u53d6\u6587\u5b57\u5c42\uff0c\u65b9\u4fbf\u590d\u5236\u6574\u7406",
  "modeChineseDictation": "\u770b\u62fc\u97f3\u5199\u6c49\u5b57",
  "modeChineseDictationDesc": "\u62fc\u97f3\u63d0\u793a + \u7a7a\u767d\u7530\u5b57\u683c",
  "modeChinesePinyin": "\u770b\u6c49\u5b57\u5199\u62fc\u97f3",
  "modeChinesePinyinDesc": "\u6c49\u5b57\u63d0\u793a + \u7a7a\u767d\u56db\u7ebf\u683c",
  "modeEnglishDictation": "\u770b\u4e2d\u6587\u5199\u5355\u8bcd",
  "modeEnglishDictationDesc": "\u4e2d\u6587\u63d0\u793a + \u7a7a\u767d\u56db\u7ebf\u4e09\u683c",
  "modeEnglishMeaning": "\u770b\u82f1\u6587\u5199\u4e2d\u6587",
  "modeEnglishMeaningDesc": "\u82f1\u6587\u63d0\u793a + \u6c49\u4e49\u6a2a\u7ebf",
} as const;

/** 按文案键读取工具文案；缺失时回退为键名，便于发现遗漏。 */
export function toolLabel(key: string): string {
  return (toolText as Record<string, string>)[key] ?? key;
}
