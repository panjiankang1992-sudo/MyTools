package com.yuyutian.mytools.feed;

/** 允许测试注入固定响应，而不放宽生产网络边界。 */
@FunctionalInterface
public interface FeedTransport {
    /** 以有界无凭据请求获取订阅或发现页面。 */
    FeedModels.Fetch fetch(String url, String etag, String lastModified);
}
