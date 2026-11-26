package com.yuyutian.mytools.news.common;

import org.springframework.http.HttpStatus;

/** 新闻业务错误码，错误响应不包含来源正文、令牌或底层异常。 */
public enum ErrorCode {
    NEWS_001(HttpStatus.UNAUTHORIZED, "Authentication required"),
    NEWS_002(HttpStatus.BAD_REQUEST, "Invalid news request"),
    NEWS_003(HttpStatus.NOT_FOUND, "News resource not found"),
    NEWS_004(HttpStatus.CONFLICT, "Idempotency key payload conflict"),
    NEWS_005(HttpStatus.SERVICE_UNAVAILABLE, "News collection is disabled"),
    NEWS_006(HttpStatus.BAD_GATEWAY, "Source fetch failed"),
    NEWS_007(HttpStatus.UNPROCESSABLE_ENTITY, "Source document rejected"),
    NEWS_008(HttpStatus.BAD_REQUEST, "Source URL is not allowed"),
    NEWS_009(HttpStatus.INTERNAL_SERVER_ERROR, "News operation failed"),
    NEWS_010(HttpStatus.CONFLICT, "Source collection already in progress"),
    NEWS_011(HttpStatus.UNPROCESSABLE_ENTITY, "Candidate capacity exceeded"),
    NEWS_012(HttpStatus.TOO_MANY_REQUESTS, "Source collection is cooling down"),
    NEWS_013(HttpStatus.UNPROCESSABLE_ENTITY, "Execution node observation rejected"),
    NEWS_014(HttpStatus.CONFLICT, "Subscription revision conflict"),
    NEWS_015(HttpStatus.SERVICE_UNAVAILABLE, "News scheduler unavailable"),
    NEWS_016(HttpStatus.CONFLICT, "News execution lease is no longer valid"),
    NEWS_017(HttpStatus.BAD_GATEWAY, "News scheduler request rejected"),
    NEWS_018(HttpStatus.SERVICE_UNAVAILABLE, "News scheduling is disabled"),
    NEWS_019(HttpStatus.BAD_GATEWAY, "News task ended without an edition"),
    NEWS_020(HttpStatus.BAD_GATEWAY, "Region task ended without an observation"),
    NEWS_021(HttpStatus.SERVICE_UNAVAILABLE, "News delivery is disabled"),
    NEWS_022(HttpStatus.CONFLICT, "Delivery target revision conflict"),
    NEWS_023(HttpStatus.SERVICE_UNAVAILABLE, "Messaging service unavailable"),
    NEWS_024(HttpStatus.UNPROCESSABLE_ENTITY, "Delivery content or channel is unsupported"),
    NEWS_025(HttpStatus.CONFLICT, "Delivery state conflict"),
    NEWS_026(HttpStatus.GONE, "News content retention expired"),
    NEWS_027(HttpStatus.SERVICE_UNAVAILABLE, "News retention is disabled"),
    NEWS_028(HttpStatus.SERVICE_UNAVAILABLE, "News collection storage capacity exhausted"),
    NEWS_029(HttpStatus.SERVICE_UNAVAILABLE, "News control identity capacity exhausted"),
    NEWS_030(HttpStatus.SERVICE_UNAVAILABLE, "News source or region registry capacity exhausted"),
    NEWS_031(HttpStatus.SERVICE_UNAVAILABLE, "Manual weekly news is disabled"),
    NEWS_032(HttpStatus.CONFLICT, "Weekly news revision conflict"),
    NEWS_033(HttpStatus.UNPROCESSABLE_ENTITY, "Weekly report validation failed"),
    NEWS_034(HttpStatus.BAD_GATEWAY, "Weekly task ended without a report"),
    NEWS_035(HttpStatus.SERVICE_UNAVAILABLE, "Weekly news capacity exhausted"),
    NEWS_036(HttpStatus.GATEWAY_TIMEOUT, "Weekly news time budget exhausted");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** 返回响应状态码。 */
    public HttpStatus status() { return status; }

    /** 返回可公开的英文错误说明。 */
    public String message() { return message; }
}
