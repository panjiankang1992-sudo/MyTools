package com.yuyutian.mytools.pdf.common;
/** PDF 工具稳定错误码。 */
public enum ErrorCode {
 PDF_001(422), PDF_002(415), PDF_003(413), PDF_004(422), PDF_005(403), PDF_006(422),
 PDF_007(410), PDF_008(409), PDF_009(503), PDF_010(422), PDF_011(422), PDF_012(410),
 PDF_013(422), PDF_014(401), PDF_015(404), PDF_016(429);
 private final int status;
 ErrorCode(int status) { this.status = status; }
 /** 返回 HTTP 状态。 */
 public int status() { return status; }
}
