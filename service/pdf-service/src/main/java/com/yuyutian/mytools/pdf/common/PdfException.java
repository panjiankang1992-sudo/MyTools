package com.yuyutian.mytools.pdf.common;
/** 只携带错误码的业务异常，不暴露文件内容和路径。 */
public class PdfException extends RuntimeException {
 private static final long serialVersionUID = 1L;
 private final ErrorCode code;
 /** 创建稳定业务错误。 */
 public PdfException(ErrorCode code) { super(code.name()); this.code = code; }
 /** 返回稳定错误码。 */
 public ErrorCode code() { return code; }
}
