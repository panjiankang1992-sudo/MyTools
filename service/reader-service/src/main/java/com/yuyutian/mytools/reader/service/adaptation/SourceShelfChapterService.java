package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ReaderRuntimeInvocation;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.model.adaptation.SourceLocator;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import com.yuyutian.mytools.reader.repository.adaptation.ShelfChapterRepository;
import com.yuyutian.mytools.reader.service.ReaderRuntimeClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 将书架身份、持久目录任务和有界运行时连接起来，网络调用始终位于事务之外。 */
@Service
public class SourceShelfChapterService implements ShelfChapterContentReader {
    private final ShelfChapterRepository repository;
    private final DiscoveryRepository sources;
    private final ReaderRuntimeClient runtime;
    private final SourceLocatorPolicy policy;
    private final SourceLocatorCipher cipher;
    private final ReaderShelfChapterProperties properties;

    /** 注入受控存储、运行时与安全边界，不接收客户端正文或 URL。 */
    public SourceShelfChapterService(ShelfChapterRepository repository, DiscoveryRepository sources, ReaderRuntimeClient runtime,
                                     SourceLocatorPolicy policy, SourceLocatorCipher cipher, ReaderShelfChapterProperties properties) {
        this.repository = repository;
        this.sources = sources;
        this.runtime = runtime;
        this.policy = policy;
        this.cipher = cipher;
        this.properties = properties;
    }

    /** 按本人书架元数据启动或合并准备任务，创建请求不等待运行时目录。 */
    public ShelfChapterModels.Capability ensure(long owner, UUID shelfId, String idempotencyKey) {
        requireEnabled();
        requireOutsideTransaction();
        AdaptationText.requireIdempotencyKey(idempotencyKey);
        var shelf = repository.shelf(owner, shelfId);
        // 网络小说通常没有文件后缀；允许准备未知格式目录，正文仍由受控运行时验证为文本。
        if (!"source".equals(shelf.origin()) || !List.of("txt", "epub", "mobi", "azw3", "unknown")
                .contains(shelf.format().toLowerCase(Locale.ROOT))) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        var source = sources.findExecutionSnapshot(owner, shelf.sourceUrl())
                .orElseThrow(() -> failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE));
        policy.validate(source.sourceUrl());
        SourceLocator.Address book = policy.validate(shelf.bookUrl());
        return repository.ensure(shelf, source, book);
    }

    /** 查询持久能力，即使新建关闭也不丢失准备状态。 */
    public ShelfChapterModels.Capability capability(long owner, UUID shelfId) {
        return repository.capability(owner, shelfId);
    }

    /** 完成一个后台目录准备任务，失败与重试均保留同一业务绑定。 */
    public boolean prepareOne(String workerId) {
        if (!properties.enabled() || !properties.runtimeEgressVerified()) {
            return false;
        }
        requireOutsideTransaction();
        repository.cleanupStaging();
        var claimed = repository.claim(workerId);
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        long prepareDeadline = System.nanoTime() + java.time.Duration.ofSeconds(properties.claimSeconds() - 5).toNanos();
        try {
            var source = sources.findExecutionSnapshot(claim.ownerId(), claim.sourceId(), claim.sourceVersion())
                    .orElseThrow(() -> failure(ErrorCode.CHAPTER_SOURCE_CHANGED));
            policy.validate(source.sourceUrl());
            var book = locate(claim.scope("BOOK"), claim.bookLocator());
            var catalog = runtime.catalog(claim.invocation(), source, book.value());
            List<ShelfChapterModels.StagedChapter> items = new ArrayList<>();
            var seen = new HashSet<String>();
            var checkedHosts = new HashSet<String>();
            for (int ordinal = 0; ordinal < catalog.chapters().size(); ordinal++) {
                if (System.nanoTime() >= prepareDeadline) {
                    throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
                }
                var chapter = catalog.chapters().get(ordinal);
                var address = policy.canonicalize(chapter.resourceUri());
                // 一次目录处理只对每个域名预检一次；实际正文获取前仍重新检查。
                if (checkedHosts.add(address.host())) {
                    policy.validate(address.value());
                }
                if (!seen.add(address.sha256())) {
                    throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
                }
                items.add(new ShelfChapterModels.StagedChapter(ordinal, chapter.title(), "TEXT",
                        cipher.seal(claim.scope("CHAPTER"), address)));
            }
            String manifest = ShelfChapterRepository.manifest(items.stream().map(ShelfChapterModels.StagedChapter::sha256).toList());
            repository.declareManifest(claim, items.size(), manifest);
            for (int offset = 0; offset < items.size(); offset += 200) {
                repository.stage(claim, items.subList(offset, Math.min(items.size(), offset + 200)));
            }
            repository.seal(claim, items.size(), manifest);
            repository.cleanupStaging();
        } catch (ChapterAdaptationException exception) {
            repository.fail(claim, exception.errorCode());
        } catch (RuntimeException exception) {
            // 异常正文可能含运行时资源地址，仅以稳定错误码记录补偿。
            repository.fail(claim, ErrorCode.RUNTIME_UNAVAILABLE);
        }
        return true;
    }

    /** 返回带签名和版本绑定游标的权威目录，结果不携带源地址。 */
    public ShelfChapterModels.Catalog catalog(long owner, UUID shelfId, int limit, String cursor) {
        Long expectedRevision = null;
        Long expectedBinding = null;
        int afterIndex = -1;
        if (cursor != null) {
            try {
                String[] fields = cipher.verifyCursor(cursor).split(":", -1);
                if (fields.length != 6 || Long.parseLong(fields[0]) != owner || !fields[1].equals(shelfId.toString())
                        || Long.parseLong(fields[5]) <= Instant.now().getEpochSecond()) {
                    throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
                }
                expectedBinding = Long.parseLong(fields[2]);
                expectedRevision = Long.parseLong(fields[3]);
                afterIndex = Integer.parseInt(fields[4]);
            } catch (IllegalArgumentException exception) {
                throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
            }
        }
        var page = repository.catalog(owner, shelfId, expectedRevision, afterIndex, limit);
        if (expectedBinding != null && expectedBinding != page.bindingRevision()) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        String next = page.nextCursor() == null ? null : cipher.signCursor(owner + ":" + shelfId + ":"
                + page.bindingRevision() + ":" + page.catalogRevision() + ":" + page.nextCursor() + ":"
                + Instant.now().plusSeconds(900).getEpochSecond());
        return new ShelfChapterModels.Catalog(shelfId, page.bindingRevision(), page.catalogRevision(), page.catalogSha256(), page.items(), next);
    }

    /** 按章节 ID 读取原章，先复核当前原站目录，再在短事务内回写实际正文摘要。 */
    public ShelfChapterModels.Content content(long owner, UUID shelfId, UUID chapterId) {
        requireEnabled();
        requireOutsideTransaction();
        var scope = repository.readScope(owner, shelfId, chapterId);
        var source = sources.findExecutionSnapshot(owner, scope.sourceId(), scope.sourceVersion())
                .orElseThrow(() -> failure(ErrorCode.CHAPTER_SOURCE_CHANGED));
        policy.validate(source.sourceUrl());
        var book = locate(scope.locatorScope("BOOK"), scope.book());
        var chapter = locate(scope.locatorScope("CHAPTER"), scope.chapter());
        if (!chapter.sha256().equals(scope.chapterKeySha256()) || !chapter.host().equals(scope.chapter().host())
                || !chapter.scheme().equals(scope.chapter().scheme())) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        var invocation = new ReaderRuntimeInvocation(owner, scope.sourceId(), scope.sourceVersion(), UUID.randomUUID());
        var catalog = runtime.catalog(invocation, source, book.value());
        List<String> hashes = new ArrayList<>();
        int matches = 0;
        for (int index = 0; index < catalog.chapters().size(); index++) {
            var item = catalog.chapters().get(index);
            var locator = policy.canonicalize(item.resourceUri());
            matches += locator.sha256().equals(chapter.sha256()) ? 1 : 0;
            hashes.add(new ShelfChapterModels.StagedChapter(index, item.title(), "TEXT",
                    new SourceLocator.Sealed("", locator.sha256(), locator.scheme(), locator.host())).sha256());
        }
        if (matches != 1 || !ShelfChapterRepository.manifest(hashes).equals(scope.catalogSha256())) {
            repository.markCatalogStale(scope);
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        var result = runtime.content(invocation, source, chapter.value());
        if (!"text".equals(result.kind())) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        int size = AdaptationText.requireText(result.text(), 1, 120000, ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        String sha = AdaptationText.sha256(result.text());
        repository.recordContentHash(scope, sha);
        return new ShelfChapterModels.Content(chapterId, scope.bindingRevision(), scope.catalogRevision(), result.text(), sha, size);
    }

    private void requireEnabled() {
        if (!properties.enabled() || !properties.runtimeEgressVerified()) {
            throw failure(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
    }

    private SourceLocator.Address locate(SourceLocator.Scope scope, SourceLocator.Sealed sealed) {
        var address = policy.validate(cipher.open(scope, sealed));
        if (!address.sha256().equals(sealed.sha256()) || !address.scheme().equals(sealed.scheme()) || !address.host().equals(sealed.host())) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        return address;
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Chapter network access cannot run inside a database transaction");
        }
    }

    private static ChapterAdaptationException failure(ErrorCode error) {
        return new ChapterAdaptationException(error);
    }
}
