/** 阅读文档使用独立空白来源，所有主动内容由 CSP 禁止。 */
export class RssHtmlPolicy {
  /** ArkWeb 将 loadData 转成 data URL，仅放行当前组件生成的完整文档。 */
  static ownedDataUrl(url: string, base64: string): boolean {
    if (!base64 || !/^data:text\/html(?:;charset=UTF-8)?;base64,/i.test(url)) return false;
    try { return decodeURIComponent(url.slice(url.indexOf(',') + 1)) === base64; } catch (_) { return false; }
  }
  /** 仅系统浏览器能打开文章 HTTP(S) 链接。 */
  static external(url: string): boolean {
    return /^https?:\/\/[^\s/@]+(?:[/?#][^\s]*)?$/i.test(url) && !/[\u0000-\u0020]/.test(url);
  }
  /** 文本回退转义，避免摘要被解释为 HTML。 */
  static escape(text: string): string {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  /** 自动加载 HTTPS 图片，继续禁用脚本、表单、媒体、框架和其他网络请求。 */
  static document(body: string, summary: string, size: number): string {
    const font = Math.max(16, Math.min(24, size));
    const content = body ? body.replace(/<img\b/gi, '<img referrerpolicy="no-referrer"') : `<p>${RssHtmlPolicy.escape(summary)}</p>`;
    return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; connect-src 'none'; media-src 'none'; object-src 'none'"><style>html{color-scheme:light}body{margin:0;padding:12px 22px 40px;font-family:system-ui,sans-serif;font-size:${font}px;line-height:1.9;color:#1E2622;background:#F3F6F4;overflow-wrap:anywhere}p{margin:0 0 1.1em}img{display:block;max-width:100%;height:auto;border-radius:14px}a{color:#07865F}pre{white-space:pre-wrap;background:#E7ECE8;padding:14px;border-radius:10px}table{display:block;overflow:auto;max-width:100%}blockquote{margin:0;padding-left:16px;border-left:3px solid #0DA678;color:#59645E}h1,h2,h3{line-height:1.5}</style></head><body>${content}</body></html>`;
  }
}
