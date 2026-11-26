/** 阅读元数据的展示规则，不处理或修改正文。 */
export class ReaderPresentationPolicy {
  /** 排除章节名称位置中的协议对象和独立接口地址，保留正常技术标题。 */
  public chapterLabel(value: string): string {
    const text = value.trim();
    // 协议对象可能已被服务端截断，不能仅依赖完整 JSON 解析。
    if (/^[{\[]\s*"[^"\n]+"\s*:/.test(text) || /^https?:\/\/\S+$/i.test(text)) return '';
    return text;
  }
}
