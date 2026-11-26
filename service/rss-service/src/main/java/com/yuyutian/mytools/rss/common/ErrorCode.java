package com.yuyutian.mytools.rss.common;

/** RSS 对外稳定错误码与 HTTP 状态，禁止回传抓取异常正文。 */
public enum ErrorCode {
    RSS_001(400), RSS_002(422), RSS_003(502), RSS_004(422), RSS_005(404),
    RSS_006(413), RSS_007(429), RSS_008(422), RSS_009(409), RSS_010(401), RSS_011(500), RSS_012(422);
    private final int status;
    ErrorCode(int status) { this.status = status; }
    /** 返回协议状态。 */
    public int status() { return status; }
}
