package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 权威章节准备和只读查询契约，公开视图不携带资源地址。 */
public final class ShelfChapterModels {
    private ShelfChapterModels() {
    }

    /** 单书能力，仅返回可供客户端判断的状态与版本。 */
    public record Capability(UUID shelfBookId, String status, long bindingRevision, long catalogRevision,
                             String catalogSha256, String reasonCode, Integer pollAfterMs) {
    }

    /** 权威章节条目，不允许客户端按标题或序号猜测身份。 */
    public record Chapter(UUID chapterId, int index, String title, String contentKind,
                          String sourceSha256, boolean eligible, String ineligibleReason) {
    }

    /** 版本绑定的目录分页；游标签名在应用服务层处理。 */
    public record Catalog(UUID shelfBookId, long bindingRevision, long catalogRevision,
                          String catalogSha256, List<Chapter> items, String nextCursor) {
        /** 防止返回后修改目录集合。 */
        public Catalog {
            items = List.copyOf(items);
        }
    }

    /** 正文由服务端按章节身份获取，不修改原书章节或阅读进度。 */
    public record Content(UUID chapterId, long bindingRevision, long catalogRevision,
                          String text, String sha256, int codepointCount) {
        /** 防止日志递归输出完整小说。 */
        @Override
        public String toString() {
            return "ShelfChapterModels.Content[redacted]";
        }
    }

    /** 短租约中的目录准备任务，不含明文 URL。 */
    public record Claim(UUID dispatchId, long ownerId, UUID shelfBookId, UUID bindingId, long bindingRevision,
                        UUID sourceId, int sourceVersion, UUID invocationId, long epoch, String claimOwner,
                        Instant claimedUntil, int attempt, SourceLocator.Sealed bookLocator) {
        /** 返回本次任务安装规则使用的持久调用身份。 */
        public ReaderRuntimeInvocation invocation() {
            return new ReaderRuntimeInvocation(ownerId, sourceId, sourceVersion, invocationId);
        }

        /** 返回用途绑定的定位符加密上下文。 */
        public SourceLocator.Scope scope(String role) {
            return new SourceLocator.Scope(ownerId, bindingId, bindingRevision, sourceVersion, role);
        }
    }

    /** 已验证的目录 staging 条目，写入前摘要与字段必须一致。 */
    public record StagedChapter(int ordinal, String title, String contentKind, SourceLocator.Sealed locator) {
        /** 计算不依赖随机加密 nonce 的确定性条目摘要。 */
        public String sha256() {
            return AdaptationText.fingerprint("shelf-catalog-item-v1", List.of(Integer.toString(ordinal), title,
                    contentKind, locator.sha256()));
        }
    }
}
