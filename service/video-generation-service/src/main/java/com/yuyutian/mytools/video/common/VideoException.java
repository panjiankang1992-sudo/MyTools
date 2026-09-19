package com.yuyutian.mytools.video.common;
/** 视频业务异常，只携带稳定错误码，不泄漏上游细节或存储路径。 */
public class VideoException extends RuntimeException {
 private final ErrorCode code;
 /** 以稳定错误码构造异常。 */
 public VideoException(ErrorCode code) { super(code.name()); this.code = code; }
 /** 返回稳定错误码。 */
 public ErrorCode code() { return code; }
}
