package com.yuyutian.mytools.news.Model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 引用证据的实时保留状态独立于不可变日报。 */
public final class EvidenceModels {
    private EvidenceModels() { }
    /** 仅说明本地证据是否可用，不代表外部网页仍在线或新闻已经核实。 */
    public enum Status { AVAILABLE, RETENTION_EXPIRED, UNAVAILABLE }
    /** 缺失或身份不匹配的证据不返回推测的到期时间。 */
    public record Item(String articleId, Status status, Instant expiresAt) { }
    /** 快照只包含本人日报已引用的证据，查询不会延长保留期。 */
    public record Snapshot(UUID editionId, Instant checkedAt, List<Item> items) { }
}
