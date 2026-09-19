package com.yuyutian.mytools.video.common;
/** 单区间 HTTP Range 的解析结果：无区间、可满足区间或越界。 */
public record ByteRange(Kind kind, long start, long end) {
 /** 解析结果类别。 */
 public enum Kind {
  /** 没有 Range 头或不是可识别的单区间：按完整响应处理。 */
  ABSENT,
  /** 请求区间超出资源长度：必须回 416。 */
  UNSATISFIABLE,
  /** 命中的区间。 */
  SATISFIED
 }

 /**
  * 解析 `Range` 头。
  *
  * 只支持单区间（`bytes=start-end`、`bytes=start-`、`bytes=-suffix`）：多区间与语法错误都按
  * 完整响应处理，这是 RFC 允许的降级方式，也避免为播放器之外的花样请求实现多段拼接。
  *
  * @param header 客户端原始 Range 头，可为空
  * @param length 资源总长度
  * @return 解析结果
  */
 public static ByteRange parse(String header, long length) {
  if (header == null || !header.startsWith("bytes=")) return new ByteRange(Kind.ABSENT, 0, 0);
  String value = header.substring("bytes=".length()).trim();
  if (value.isEmpty() || value.contains(",")) return new ByteRange(Kind.ABSENT, 0, 0);
  int dash = value.indexOf('-');
  if (dash < 0) return new ByteRange(Kind.ABSENT, 0, 0);
  String first = value.substring(0, dash).trim();
  String second = value.substring(dash + 1).trim();
  try {
   long start;
   long end;
   if (first.isEmpty()) {
    // 后缀区间：最后 N 字节；N 为 0 视为越界，避免产生空 206。
    if (second.isEmpty()) return new ByteRange(Kind.ABSENT, 0, 0);
    long suffix = Long.parseLong(second);
    if (suffix <= 0 || length == 0) return new ByteRange(Kind.UNSATISFIABLE, 0, 0);
    start = Math.max(0, length - suffix);
    end = length - 1;
   } else {
    start = Long.parseLong(first);
    end = second.isEmpty() ? length - 1 : Long.parseLong(second);
    if (start < 0) return new ByteRange(Kind.ABSENT, 0, 0);
    // 起点落在资源之外属于"无法满足"，必须先于"end<start"判断，否则 `bytes=100-` 会被当成畸形头。
    if (start >= length || length == 0) return new ByteRange(Kind.UNSATISFIABLE, 0, 0);
    if (end < start) return new ByteRange(Kind.ABSENT, 0, 0);
    end = Math.min(end, length - 1);
   }
   return new ByteRange(Kind.SATISFIED, start, end);
  } catch (NumberFormatException e) {
   // 数值超出 long 或含非数字：按完整响应处理，不因为一个畸形头拒绝整个播放请求。
   return new ByteRange(Kind.ABSENT, 0, 0);
  }
 }

 /** 区间字节数。 */
 public long size() { return end - start + 1; }

 /**
  * 生成 `Content-Range` 值。
  *
  * @param length 资源总长度
  * @return 形如 `bytes 0-99/200` 的响应头值
  */
 public String contentRange(long length) { return "bytes " + start + "-" + end + "/" + length; }
}
