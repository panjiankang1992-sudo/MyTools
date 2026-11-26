package com.yuyutian.mytools.rss.Model;

import jakarta.validation.constraints.*;
import java.util.List;

/** App 与 Gateway 共用的稳定 RSS 请求和响应模型。 */
public final class RssModels {
    private RssModels() { }
    /** 个人分组。 */
    public record Group(String id, String name, int sortOrder, long revision) { }
    /** 用户可见订阅状态，不包含内部租约或条件请求凭据。 */
    public record Subscription(String id, String groupId, String feedUrl, String title, String customTitle,
                               String siteUrl, String description, String status, int intervalMinutes,
                               boolean initialized, Long lastAttemptAt, Long lastSuccessAt, long nextFetchAt,
                               String lastErrorCode, boolean syncing, long revision, long unreadCount) { }
    /** 列表和详情使用相同身份，列表不携带大正文。 */
    public record Entry(String id, String subscriptionId, String sourceTitle, String title, String author,
                        String url, String summary, String contentHtml, String imageUrl, Long publishedAt,
                        Long sourceUpdatedAt, long discoveredAt, boolean read, boolean starred, long revision) { }
    /** 分页响应包含服务端生成的稳定游标。 */
    public record EntryPage(List<Entry> items, String nextCursor) { }
    /** 首页统计不暴露其他用户的订阅。 */
    public record Counts(long subscriptions, long unread, long starred) { }
    /** 查询条件由服务端再次验证。 */
    public record Filter(String subscriptionId, String groupId, String view, String q) { }
    /** 发现 URL 的输入。 */
    public record DiscoverRequest(@NotBlank @Size(max = 2048) String url) { }
    /** 首次导入策略在创建时固定，不修改已经存在的阅读状态。 */
    public record CreateSubscription(@NotBlank @Size(max = 2048) String url,
                                     @Size(max = 256) String title, @Size(max = 36) String groupId,
                                     boolean initialReadAll) { }
    /** 设置更新需要匹配服务端版本。 */
    public record UpdateSubscription(@NotNull @Min(0) Long expectedRevision,
                                     @NotNull @Size(max = 256) String title, @Size(max = 36) String groupId,
                                     @NotBlank String status, int intervalMinutes) { }
    /** 创建或编辑一个分组。 */
    public record GroupChange(@NotBlank @Size(max = 80) String name, @Min(0) int sortOrder, @Min(0) Long expectedRevision) { }
    /** 明确设置值，禁止重试导致 toggle 再次翻转。 */
    public record StateChange(@NotNull @Min(0) Long expectedRevision, @NotNull Boolean read, @NotNull Boolean starred) { }
    /** 批量标记只处理服务器快照中的范围。 */
    public record MarkRead(@Size(max = 36) String subscriptionId, @Size(max = 36) String groupId, @Size(max = 100) String q) { }
    /** 以 JSON 承载有界 OPML 文本，复用 App 已有认证请求通道。 */
    public record OpmlRequest(@NotBlank @Size(max = 2097152) String xml) { }
    /** 单项导入结果独立呈现，失败不覆盖已有设置。 */
    public record ImportItem(String url, String title, String group, String status, String subscriptionId, String errorCode) { }
}
