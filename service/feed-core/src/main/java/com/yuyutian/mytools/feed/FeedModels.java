package com.yuyutian.mytools.feed;

import java.time.Instant;
import java.util.List;

/** 与持久化和业务服务解耦的订阅交换模型。 */
public final class FeedModels {
    private FeedModels() { }
    /** 条目身份独立于正文哈希，来源日期允许缺失。 */
    public record Entry(String identityType, String identityHash, String sourceId, String url,
                        String title, String author, String summary, String contentHtml,
                        Instant publishedAt, Instant updatedAt, String contentHash, String imageUrl) { }
    /** 解析后的 Feed 元数据与有序条目。 */
    public record Feed(String title, String siteUrl, String description, List<Entry> entries) { }
    /** 页面显式声明的订阅候选。 */
    public record Candidate(String url, String title) { }
    /** 条件 HTTP 请求结果，禁止把内部异常或凭据塞入响应。 */
    public record Fetch(int status, byte[] body, String finalUrl, String etag, String lastModified,
                        String retryAfter, String contentType) { }
    /** 不发起网络请求的 OPML 导入候选。 */
    public record Outline(String url, String title, String group) { }
}
