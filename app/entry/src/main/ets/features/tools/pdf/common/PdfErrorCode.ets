/** 本机 PDF 工具错误码，禁止把 SDK 原始错误或密码显示到界面。 */
export class PdfErrorCode {
  static readonly INVALID: string = 'PDF_LOCAL_001';
  static readonly PASSWORD: string = 'PDF_LOCAL_002';
  static readonly FILE: string = 'PDF_LOCAL_003';
  static readonly LIMIT: string = 'PDF_LOCAL_004';
  static readonly WRITE: string = 'PDF_LOCAL_005';
  static readonly CANCELLED: string = 'PDF_LOCAL_006';
  static readonly NO_TEXT: string = 'PDF_LOCAL_007';
  static readonly MARKDOWN: string = 'PDF_LOCAL_008';
}
