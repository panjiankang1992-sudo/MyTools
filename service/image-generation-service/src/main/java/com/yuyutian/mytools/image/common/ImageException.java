package com.yuyutian.mytools.image.common;
/** 只暴露稳定错误码的图片业务异常。 */
public class ImageException extends RuntimeException {
 private final ErrorCode code;
 /** 保存稳定错误码。 */
 public ImageException(ErrorCode code) { super(code.name()); this.code=code; }
 /** 返回错误码。 */
 public ErrorCode code() { return code; }
}
