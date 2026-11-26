package com.yuyutian.mytools.news.common;

import java.time.Instant;

/** 上游或本地采集冷却，保留下一次允许访问时刻。 */
public class SourceRateLimitException extends NewsException {
    private static final long serialVersionUID = 1L;
    private final Instant retryAt;

    /** 创建不携带响应正文或凭据的限流异常。 */
    public SourceRateLimitException(Instant retryAt) { super(ErrorCode.NEWS_012); this.retryAt = retryAt; }

    /** 获取下次允许访问时刻。 */
    public Instant retryAt() { return retryAt; }
}
