package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels.Claim;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels.StagedChapter;
import com.yuyutian.mytools.reader.model.adaptation.SourceLocator;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.SourceLocatorCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 书架绑定和目录投影仓储，所有变更遵循 shelf、binding、dispatch 的锁顺序。 */
@Repository
public class ShelfChapterRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final ReaderShelfChapterProperties properties;
    private final SourceLocatorCipher cipher;
    private final Clock clock;

    /** 使用应用事务管理器和生产时钟创建仓储。 */
    @Autowired
    public ShelfChapterRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
                                   ReaderShelfChapterProperties properties, SourceLocatorCipher cipher) {
        this(jdbc, mapper, manager, properties, cipher, Clock.systemUTC());
    }

    /** 允许隔离夹具注入可控时钟，测试租约接管而无需真实等待。 */
    public ShelfChapterRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
                                   ReaderShelfChapterProperties properties, SourceLocatorCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.properties = properties;
        this.cipher = cipher;
        this.clock = clock;
    }

    /** 已从本人书架读取的元数据，不作为公共 DTO。 */
    public record Shelf(UUID id, long ownerId, long version, String sourceUrl, String bookUrl, String origin, String format) {
        /** 避免地址及元数据进入默认日志。 */
        @Override
        public String toString() {
            return "ShelfChapterRepository.Shelf[redacted]";
        }
    }

    /** 查询本人未删除书架记录，其他用户和不存在对象使用相同错误。 */
    public Shelf shelf(long ownerId, UUID shelfId) {
        return loadShelf(ownerId, shelfId, "").orElseThrow(() -> failure(ErrorCode.READER_STATE_NOT_FOUND));
    }

    /** 返回单书当前准备能力，不把未封存目录作为可用内容。 */
    public ShelfChapterModels.Capability capability(long ownerId, UUID shelfId) {
        return transaction.execute(status -> {
            Shelf shelf = lockShelf(ownerId, shelfId);
            Map<String, Object> binding = binding(ownerId, shelfId);
            if (binding != null && number(binding, "bound_shelf_version") != shelf.version()) {
                return new ShelfChapterModels.Capability(shelfId, "UNAVAILABLE", number(binding, "binding_revision"),
                        number(binding, "catalog_revision"), null, ErrorCode.CHAPTER_SOURCE_CHANGED.code(), null);
            }
            return capabilityOf(shelfId, binding);
        });
    }

    /** 在短事务内建立绑定、加密 BOOK locator 和持久准备任务；不执行网络调用。 */
    public ShelfChapterModels.Capability ensure(Shelf expected, SourceExecutionSnapshot source, SourceLocator.Address book) {
        return transaction.execute(status -> {
            // 用户级锁只在创建时获取，随后仍按 shelf、binding、dispatch 顺序锁定。
            try {
                jdbc.update("INSERT INTO reader_chapter_preparation_owner_guard (owner_id) VALUES (?)", expected.ownerId());
            } catch (DuplicateKeyException exception) {
                // 已存在的用户锁行继续用于本次事务，不重复分配限额。
            }
            jdbc.queryForObject("SELECT owner_id FROM reader_chapter_preparation_owner_guard WHERE owner_id = ? FOR UPDATE",
                    Long.class, expected.ownerId());
            Shelf current = lockShelf(expected.ownerId(), expected.id());
            if (current.version() != expected.version() || !"source".equals(current.origin())) {
                throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
            }
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM book_source WHERE owner_id = ? AND id = ? AND current_version = ? AND enabled = TRUE
                    """, Integer.class, expected.ownerId(), source.id().toString(), source.version());
            if (count == null || count != 1) {
                throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
            }
            Map<String, Object> existing = binding(expected.ownerId(), expected.id());
            String bookKey = AdaptationText.fingerprint("source-book-v1", List.of(source.id().toString(), book.sha256()));
            boolean sameIdentity = existing != null && "SOURCE_RUNTIME".equals(existing.get("binding_type"))
                    && source.id().toString().equals(existing.get("source_id")) && number(existing, "source_version") == source.version()
                    && bookKey.equals(existing.get("source_book_key"));
            if (sameIdentity && List.of("PREPARING", "ACTIVE").contains(existing.get("status"))) {
                jdbc.update("UPDATE shelf_book_content_binding SET bound_shelf_version = ? WHERE owner_id = ? AND id = ?",
                        current.version(), current.ownerId(), existing.get("id"));
                return capabilityOf(expected.id(), existing);
            }
            Integer preparing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM shelf_book_content_binding b JOIN shelf_book s ON s.id = b.shelf_book_id AND s.owner_id = b.owner_id
                    WHERE b.owner_id = ? AND b.status = 'PREPARING' AND s.deleted = FALSE AND b.shelf_book_id <> ?
                    """, Integer.class, expected.ownerId(), expected.id().toString());
            if (preparing != null && preparing >= properties.maximumPreparingPerOwner()) {
                throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
            }
            UUID bindingId = existing == null ? UUID.randomUUID() : UUID.fromString(string(existing, "id"));
            long revision = existing == null ? 1 : number(existing, "binding_revision") + (sameIdentity ? 0 : 1);
            Instant now = now();
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO shelf_book_content_binding (id, owner_id, shelf_book_id, binding_type, source_id,
                            source_version, source_book_key, binding_revision, bound_shelf_version, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'SOURCE_RUNTIME', ?, ?, ?, ?, ?, 'PREPARING', ?, ?)
                        """, bindingId.toString(), expected.ownerId(), expected.id().toString(), source.id().toString(),
                        source.version(), bookKey, revision, current.version(), timestamp(now), timestamp(now));
            } else {
                // 旧章节保留身份与历史引用，只从当前目录撤下。
                jdbc.update("UPDATE shelf_book_chapter SET active = FALSE WHERE owner_id = ? AND shelf_book_id = ?",
                        expected.ownerId(), expected.id().toString());
                jdbc.update("""
                        UPDATE shelf_book_preparation_dispatch SET status = 'CANCELLED', claim_owner = NULL,
                            claimed_until = NULL, updated_at = ? WHERE owner_id = ? AND binding_id = ?
                            AND status IN ('PENDING_DISPATCH', 'DISPATCHED', 'RUNNING')
                        """, timestamp(now), expected.ownerId(), bindingId.toString());
                jdbc.update("""
                        UPDATE shelf_book_content_binding SET binding_type = 'SOURCE_RUNTIME', source_id = ?, source_version = ?,
                            source_book_key = ?, binding_revision = ?, bound_shelf_version = ?, status = 'PREPARING', media_item_id = NULL,
                            media_asset_id = NULL, media_content_sha256 = NULL, ebook_asset_id = NULL,
                            last_error_code = NULL, next_retry_at = NULL, updated_at = ? WHERE owner_id = ? AND id = ?
                        """, source.id().toString(), source.version(), bookKey, revision, current.version(), timestamp(now), expected.ownerId(), bindingId.toString());
            }
            var scope = new SourceLocator.Scope(expected.ownerId(), bindingId, revision, source.version(), "BOOK");
            if (!sameIdentity) {
                insertLocator(UUID.randomUUID(), scope, cipher.seal(scope, book), now);
            }
            Integer round = jdbc.queryForObject("SELECT COALESCE(MAX(preparation_round), 0) + 1 FROM shelf_book_preparation_dispatch"
                    + " WHERE owner_id = ? AND binding_id = ? AND binding_revision = ? AND phase = 'SOURCE_CATALOG'",
                    Integer.class, expected.ownerId(), bindingId.toString(), revision);
            UUID dispatchId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO shelf_book_preparation_dispatch (id, owner_id, binding_id, binding_revision, preparation_round, phase, status,
                        deterministic_request_id, invocation_id, next_dispatch_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'SOURCE_CATALOG', 'PENDING_DISPATCH', ?, ?, ?, ?, ?)
                    """, dispatchId.toString(), expected.ownerId(), bindingId.toString(), revision, round, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), timestamp(now), timestamp(now), timestamp(now));
            return capabilityOf(expected.id(), binding(expected.ownerId(), expected.id()));
        });
    }

    /** 发现有限候选后按统一锁顺序领取一个租约，返回后已释放数据库锁。 */
    public Optional<Claim> claim(String workerId) {
        var candidates = jdbc.queryForList("""
                SELECT d.id, d.owner_id, b.shelf_book_id FROM shelf_book_preparation_dispatch d
                JOIN shelf_book_content_binding b ON b.id = d.binding_id AND b.owner_id = d.owner_id
                JOIN shelf_book sb ON sb.id = b.shelf_book_id AND sb.owner_id = b.owner_id AND sb.deleted = FALSE
                WHERE d.phase = 'SOURCE_CATALOG' AND d.status IN ('PENDING_DISPATCH', 'RUNNING')
                    AND d.next_dispatch_at <= ? AND (d.claimed_until IS NULL OR d.claimed_until <= ?)
                ORDER BY d.next_dispatch_at, d.id LIMIT 8
                """, timestamp(now()), timestamp(now()));
        for (Map<String, Object> candidate : candidates) {
            Claim claimed = transaction.execute(status -> {
                long owner = number(candidate, "owner_id");
                UUID shelfId = UUID.fromString(string(candidate, "shelf_book_id"));
                var shelf = loadShelf(owner, shelfId, " FOR UPDATE SKIP LOCKED");
                if (shelf.isEmpty()) {
                    return null;
                }
                Map<String, Object> binding = binding(owner, shelfId);
                var rows = jdbc.queryForList("""
                        SELECT * FROM shelf_book_preparation_dispatch WHERE owner_id = ? AND id = ?
                            AND status IN ('PENDING_DISPATCH', 'RUNNING') AND next_dispatch_at <= ?
                            AND (claimed_until IS NULL OR claimed_until <= ?) FOR UPDATE
                        """, owner, string(candidate, "id"), timestamp(now()), timestamp(now()));
                if (rows.isEmpty()) {
                    return null;
                }
                Map<String, Object> dispatch = rows.getFirst();
                if (binding == null || !"PREPARING".equals(binding.get("status"))
                        || number(binding, "binding_revision") != number(dispatch, "binding_revision")) {
                    jdbc.update("UPDATE shelf_book_preparation_dispatch SET status = 'CANCELLED', claim_owner = NULL,"
                            + " claimed_until = NULL WHERE owner_id = ? AND id = ?", owner, string(dispatch, "id"));
                    return null;
                }
                if (number(dispatch, "dispatch_attempt_count") >= properties.maximumAttempts()) {
                    markBroken(owner, binding, dispatch, ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
                    return null;
                }
                if (number(binding, "bound_shelf_version") != shelf.get().version()) {
                    markBroken(owner, binding, dispatch, ErrorCode.CHAPTER_SOURCE_CHANGED);
                    return null;
                }
                UUID bindingId = UUID.fromString(string(binding, "id"));
                Instant until = now().plusSeconds(properties.claimSeconds());
                long epoch = number(dispatch, "dispatch_epoch") + 1;
                int attempt = Math.toIntExact(number(dispatch, "dispatch_attempt_count") + 1);
                jdbc.update("""
                        UPDATE shelf_book_preparation_dispatch SET status = 'RUNNING', claim_owner = ?, claimed_until = ?,
                            dispatch_epoch = ?, dispatch_attempt_count = ?, updated_at = ? WHERE owner_id = ? AND id = ?
                        """, workerId, timestamp(until), epoch, attempt, timestamp(now()), owner, string(dispatch, "id"));
                return new Claim(UUID.fromString(string(dispatch, "id")), owner, shelfId, bindingId,
                        number(binding, "binding_revision"), UUID.fromString(string(binding, "source_id")),
                        Math.toIntExact(number(binding, "source_version")), UUID.fromString(string(dispatch, "invocation_id")),
                        epoch, workerId, until, attempt, bookLocator(owner, binding));
            });
            if (claimed != null) {
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    /** 追加有界 staging 批次，同序号同摘要重放成功，冲突不得覆盖。 */
    public void stage(Claim claim, List<StagedChapter> items) {
        if (items.isEmpty() || items.size() > 200) {
            throw failure(ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
        }
        transaction.executeWithoutResult(status -> {
            requireClaim(claim);
            Integer declaredCount = jdbc.queryForObject("SELECT catalog_item_count FROM shelf_book_preparation_dispatch"
                    + " WHERE owner_id = ? AND id = ?", Integer.class, claim.ownerId(), claim.dispatchId().toString());
            if (declaredCount == null) {
                throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
            }
            for (StagedChapter item : items) {
                AdaptationText.requireText(item.title(), 1, 500, ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
                if (item.ordinal() < 0 || item.ordinal() >= declaredCount || !"TEXT".equals(item.contentKind())) {
                    throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
                }
                cipher.open(claim.scope("CHAPTER"), item.locator());
                var existing = jdbc.queryForList("""
                        SELECT item_sha256 FROM shelf_book_catalog_projection_staging
                        WHERE owner_id = ? AND dispatch_id = ? AND ordinal = ?
                        """, claim.ownerId(), claim.dispatchId().toString(), item.ordinal());
                if (!existing.isEmpty()) {
                    if (!item.sha256().equals(existing.getFirst().get("item_sha256"))) {
                        throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
                    }
                    continue;
                }
                jdbc.update("""
                        INSERT INTO shelf_book_catalog_projection_staging (id, owner_id, dispatch_id, binding_id,
                            binding_revision, ordinal, stable_chapter_key_sha256, title, content_kind,
                            locator_ciphertext, locator_sha256, item_sha256, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), claim.ownerId(), claim.dispatchId().toString(), claim.bindingId().toString(),
                        claim.bindingRevision(), item.ordinal(), item.locator().sha256(), item.title(), item.contentKind(),
                        item.locator().ciphertext(), item.locator().sha256(), item.sha256(), timestamp(now()));
            }
            jdbc.update("UPDATE shelf_book_preparation_dispatch SET claimed_until = ?, updated_at = ? WHERE owner_id = ? AND id = ?",
                    timestamp(now().plusSeconds(properties.claimSeconds())), timestamp(now()), claim.ownerId(), claim.dispatchId().toString());
        });
    }

    /** 在写入批次前冻结完整原站目录清单，恢复不能换成较短目录掩盖缺章。 */
    public void declareManifest(Claim claim, int count, String manifest) {
        if (count < 1 || count > 50000) {
            throw failure(ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
        }
        AdaptationText.requireSha256(manifest, ErrorCode.CHAPTER_SOURCE_CHANGED);
        transaction.executeWithoutResult(status -> {
            requireClaim(claim);
            var row = jdbc.queryForMap("SELECT catalog_item_count, catalog_manifest_sha256 FROM shelf_book_preparation_dispatch"
                    + " WHERE owner_id = ? AND id = ?", claim.ownerId(), claim.dispatchId().toString());
            if (row.get("catalog_item_count") != null) {
                if (number(row, "catalog_item_count") != count || !manifest.equals(row.get("catalog_manifest_sha256"))) {
                    throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
                }
                return;
            }
            jdbc.update("""
                    UPDATE shelf_book_preparation_dispatch SET catalog_item_count = ?, catalog_manifest_sha256 = ?,
                        catalog_canonicalization_version = 'shelf-catalog-v1' WHERE owner_id = ? AND id = ?
                    """, count, manifest, claim.ownerId(), claim.dispatchId().toString());
        });
    }

    /** 一次原子发布完整目录，任何缺章、摘要冲突或旧租约都不进入正式章节。 */
    public void seal(Claim claim, int expectedCount, String expectedManifest) {
        if (expectedCount < 1 || expectedCount > 50000) {
            throw failure(ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
        }
        transaction.executeWithoutResult(status -> {
            Map<String, Object> binding = requireClaim(claim);
            var declared = jdbc.queryForMap("SELECT catalog_item_count, catalog_manifest_sha256 FROM shelf_book_preparation_dispatch"
                    + " WHERE owner_id = ? AND id = ?", claim.ownerId(), claim.dispatchId().toString());
            if (declared.get("catalog_item_count") == null || number(declared, "catalog_item_count") != expectedCount
                    || !expectedManifest.equals(declared.get("catalog_manifest_sha256"))) {
                throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
            }
            var staged = jdbc.queryForList("""
                    SELECT * FROM shelf_book_catalog_projection_staging WHERE owner_id = ? AND dispatch_id = ?
                    ORDER BY ordinal LIMIT 50001
                    """, claim.ownerId(), claim.dispatchId().toString());
            List<String> hashes = new ArrayList<>();
            List<StagedChapter> items = new ArrayList<>();
            for (int index = 0; index < staged.size(); index++) {
                Map<String, Object> row = staged.get(index);
                if (number(row, "ordinal") != index) {
                    throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
                }
                var sealed = new SourceLocator.Sealed(string(row, "locator_ciphertext"), string(row, "locator_sha256"), "", "");
                URI address = URI.create(cipher.open(claim.scope("CHAPTER"), sealed));
                var item = new StagedChapter(index, string(row, "title"), string(row, "content_kind"),
                        new SourceLocator.Sealed(sealed.ciphertext(), sealed.sha256(), address.getScheme(), address.getHost()));
                if (!item.sha256().equals(row.get("item_sha256")) || !sealed.sha256().equals(row.get("stable_chapter_key_sha256"))) {
                    throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
                }
                hashes.add(item.sha256());
                items.add(item);
            }
            if (staged.size() != expectedCount || !manifest(hashes).equals(expectedManifest)) {
                throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
            }
            long revision = number(binding, "catalog_revision") + 1;
            publishChapters(claim, items, revision);
            int updated = jdbc.update("""
                    UPDATE shelf_book_preparation_dispatch SET status = 'SUCCEEDED', catalog_canonicalization_version = 'shelf-catalog-v1',
                        catalog_item_count = ?, catalog_manifest_sha256 = ?, claim_owner = NULL, claimed_until = NULL, updated_at = ?
                    WHERE owner_id = ? AND id = ? AND claimed_until > ?
                    """, expectedCount, expectedManifest, timestamp(now()), claim.ownerId(), claim.dispatchId().toString(), timestamp(now()));
            if (updated != 1) {
                throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
            }
            jdbc.update("""
                    UPDATE shelf_book_content_binding SET status = 'ACTIVE', catalog_revision = ?, catalog_sha256 = ?,
                        last_error_code = NULL, next_retry_at = NULL, updated_at = ? WHERE owner_id = ? AND id = ?
                    """, revision, expectedManifest, timestamp(now()), claim.ownerId(), claim.bindingId().toString());
        });
    }

    /** 仅当前租约可记录失败或安排补偿；迟到 worker 不改变新执行状态。 */
    public void fail(Claim claim, ErrorCode error) {
        try {
            transaction.executeWithoutResult(status -> {
                Map<String, Object> binding = requireClaim(claim, false);
                if (claim.attempt() >= properties.maximumAttempts() || error == ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE
                        || error == ErrorCode.CHAPTER_SOURCE_CHANGED || error == ErrorCode.ADAPTATION_CATALOG_TOO_LARGE) {
                    markBroken(claim.ownerId(), binding, Map.of("id", claim.dispatchId().toString()), error);
                    return;
                }
                jdbc.update("""
                        UPDATE shelf_book_preparation_dispatch SET status = 'PENDING_DISPATCH', last_error_code = ?,
                            next_dispatch_at = ?, claim_owner = NULL, claimed_until = NULL, updated_at = ? WHERE owner_id = ? AND id = ?
                        """, error.code(), timestamp(now().plusSeconds(1L << claim.attempt())), timestamp(now()),
                        claim.ownerId(), claim.dispatchId().toString());
            });
        } catch (ChapterAdaptationException exception) {
            if (exception.errorCode() != ErrorCode.ADAPTATION_EXECUTION_FENCED && exception.errorCode() != ErrorCode.READER_STATE_NOT_FOUND) {
                throw exception;
            }
        }
    }

    /** 计算有序目录摘要，调用方必须先固定数量和每项摘要。 */
    public static String manifest(List<String> hashes) {
        List<String> fields = new ArrayList<>();
        fields.add(Integer.toString(hashes.size()));
        fields.addAll(hashes);
        return AdaptationText.fingerprint("shelf-catalog-v1", fields);
    }

    private void publishChapters(Claim claim, List<StagedChapter> items, long catalogRevision) {
        Map<String, String> existing = new HashMap<>();
        jdbc.queryForList("SELECT id, chapter_key_sha256 FROM shelf_book_chapter WHERE owner_id = ? AND content_binding_id = ?"
                        + " AND binding_revision = ?", claim.ownerId(), claim.bindingId().toString(), claim.bindingRevision())
                .forEach(row -> existing.put(string(row, "chapter_key_sha256"), string(row, "id")));
        jdbc.update("UPDATE shelf_book_chapter SET active = FALSE WHERE owner_id = ? AND shelf_book_id = ?",
                claim.ownerId(), claim.shelfBookId().toString());
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<Object[]> locatorInserts = new ArrayList<>();
        Map<String, UUID> locatorIds = new HashMap<>();
        jdbc.queryForList("""
                SELECT id, locator_sha256 FROM shelf_book_source_locator WHERE owner_id = ? AND binding_id = ?
                    AND binding_revision = ? AND source_version = ? AND locator_scope = 'CHAPTER'
                """, claim.ownerId(), claim.bindingId().toString(), claim.bindingRevision(), claim.sourceVersion())
                .forEach(row -> locatorIds.put(string(row, "locator_sha256"), UUID.fromString(string(row, "id"))));
        for (StagedChapter item : items) {
            UUID locatorId = locatorIds.get(item.locator().sha256());
            if (locatorId == null) {
                locatorId = UUID.randomUUID();
                locatorIds.put(item.locator().sha256(), locatorId);
                locatorInserts.add(new Object[]{locatorId.toString(), claim.ownerId(), claim.bindingId().toString(),
                        claim.bindingRevision(), claim.sourceVersion(), "CHAPTER", item.locator().ciphertext(), item.locator().sha256(),
                        item.locator().scheme(), item.locator().host(), timestamp(now())});
            }
            String chapterId = existing.get(item.locator().sha256());
            if (chapterId == null) {
                inserts.add(new Object[]{UUID.randomUUID().toString(), claim.ownerId(), claim.shelfBookId().toString(),
                        claim.bindingId().toString(), claim.bindingRevision(), catalogRevision, item.ordinal(), item.locator().sha256(),
                        item.title(), "SOURCE_CATALOG_KEY", locatorId.toString(), claim.sourceVersion(), "TEXT", true,
                        timestamp(now()), timestamp(now())});
            } else {
                updates.add(new Object[]{catalogRevision, item.ordinal(), item.title(), timestamp(now()), claim.ownerId(), chapterId});
            }
        }
        insertBatches("""
                INSERT INTO shelf_book_source_locator (id, owner_id, binding_id, binding_revision, source_version, locator_scope,
                    locator_ciphertext, locator_sha256, allowed_scheme, allowed_host, created_at) VALUES
                """, locatorInserts);
        insertBatches("""
                INSERT INTO shelf_book_chapter (id, owner_id, shelf_book_id, content_binding_id, binding_revision, catalog_revision,
                    chapter_index, chapter_key_sha256, chapter_title, locator_kind, source_locator_id, source_version,
                    content_kind, active, created_at, updated_at)
                VALUES
                """, inserts);
        jdbc.batchUpdate("UPDATE shelf_book_chapter SET catalog_revision = ?, chapter_index = ?, chapter_title = ?,"
                + " active = TRUE, updated_at = ? WHERE owner_id = ? AND id = ?", updates);
    }

    private void insertBatches(String fixedPrefix, List<Object[]> rows) {
        // SQL 结构只由固定调用点及占位符构成，所有实际内容仍通过绑定参数传递。
        for (int offset = 0; offset < rows.size(); offset += 200) {
            int count = Math.min(200, rows.size() - offset);
            int width = rows.get(offset).length;
            String tuple = "(" + String.join(",", java.util.Collections.nCopies(width, "?")) + ")";
            Object[] parameters = new Object[count * width];
            for (int row = 0; row < count; row++) {
                System.arraycopy(rows.get(offset + row), 0, parameters, row * width, width);
            }
            jdbc.update(fixedPrefix + String.join(",", java.util.Collections.nCopies(count, tuple)), parameters);
        }
    }

    private Map<String, Object> requireClaim(Claim claim) {
        return requireClaim(claim, true);
    }

    private Map<String, Object> requireClaim(Claim claim, boolean requireSourceState) {
        Shelf shelf = lockShelf(claim.ownerId(), claim.shelfBookId());
        Map<String, Object> binding = binding(claim.ownerId(), claim.shelfBookId());
        var rows = jdbc.queryForList("""
                SELECT id FROM shelf_book_preparation_dispatch WHERE owner_id = ? AND id = ? AND binding_id = ?
                    AND binding_revision = ? AND status = 'RUNNING' AND claim_owner = ? AND dispatch_epoch = ? AND claimed_until > ? FOR UPDATE
                """, claim.ownerId(), claim.dispatchId().toString(), claim.bindingId().toString(), claim.bindingRevision(),
                claim.claimOwner(), claim.epoch(), timestamp(now()));
        if (binding == null || rows.isEmpty() || !claim.bindingId().toString().equals(binding.get("id"))
                || number(binding, "binding_revision") != claim.bindingRevision() || !"PREPARING".equals(binding.get("status"))) {
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
        Integer sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM book_source WHERE owner_id = ? AND id = ? AND enabled = TRUE",
                Integer.class, claim.ownerId(), claim.sourceId().toString());
        if (requireSourceState && (number(binding, "bound_shelf_version") != shelf.version() || sourceCount == null || sourceCount != 1
                || !claim.sourceId().toString().equals(binding.get("source_id")) || claim.sourceVersion() != number(binding, "source_version"))) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        return binding;
    }

    /** 取文期间冻结的服务端身份，网络完成后还要复核当前修订。 */
    public record ReadScope(long ownerId, UUID shelfBookId, UUID chapterId, UUID bindingId, long bindingRevision,
                            long catalogRevision, String catalogSha256, UUID sourceId, int sourceVersion,
                            String chapterKeySha256, SourceLocator.Sealed book, SourceLocator.Sealed chapter) {
        /** 返回指定用途的关联加密身份。 */
        public SourceLocator.Scope locatorScope(String role) {
            return new SourceLocator.Scope(ownerId, bindingId, bindingRevision, sourceVersion, role);
        }
    }

    /** 短事务读取当前目录页，调用方负责验签绑定此版本的游标。 */
    public ShelfChapterModels.Catalog catalog(long owner, UUID shelfId, Long expectedRevision, int afterIndex, int limit) {
        if (limit < 1 || limit > 500 || afterIndex < -1 || afterIndex >= 50000) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        return transaction.execute(status -> {
            Map<String, Object> binding = requireReady(owner, shelfId);
            long revision = number(binding, "catalog_revision");
            if (expectedRevision != null && expectedRevision != revision) {
                throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
            }
            List<ShelfChapterModels.Chapter> chapters = jdbc.query("""
                    SELECT * FROM shelf_book_chapter WHERE owner_id = ? AND shelf_book_id = ? AND content_binding_id = ?
                        AND binding_revision = ? AND catalog_revision = ? AND active = TRUE AND chapter_index > ?
                    ORDER BY chapter_index, id LIMIT ?
                    """, (row, ordinal) -> new ShelfChapterModels.Chapter(UUID.fromString(row.getString("id")), row.getInt("chapter_index"),
                    row.getString("chapter_title"), row.getString("content_kind"), row.getString("content_sha256"),
                    "TEXT".equals(row.getString("content_kind")), "TEXT".equals(row.getString("content_kind")) ? null
                            : ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE.code()), owner, shelfId.toString(), binding.get("id"),
                    binding.get("binding_revision"), revision, afterIndex, limit + 1);
            boolean more = chapters.size() > limit;
            var page = chapters.subList(0, Math.min(limit, chapters.size()));
            // 仓储只返回内部末尾序号，应用服务必须签名后才能暴露游标。
            return new ShelfChapterModels.Catalog(shelfId, number(binding, "binding_revision"), revision,
                    string(binding, "catalog_sha256"), page, more ? Integer.toString(page.getLast().index()) : null);
        });
    }

    /** 按用户、书架与章节读取可信定位符，禁止按标题或索引回落。 */
    public ReadScope readScope(long owner, UUID shelfId, UUID chapterId) {
        return transaction.execute(status -> {
            Map<String, Object> binding = requireReady(owner, shelfId);
            var rows = jdbc.queryForList("""
                    SELECT c.chapter_key_sha256, l.locator_ciphertext, l.locator_sha256, l.allowed_scheme, l.allowed_host
                    FROM shelf_book_chapter c JOIN shelf_book_source_locator l ON l.id = c.source_locator_id
                        AND l.owner_id = c.owner_id AND l.binding_id = c.content_binding_id
                        AND l.binding_revision = c.binding_revision AND l.source_version = c.source_version
                    WHERE c.owner_id = ? AND c.shelf_book_id = ? AND c.id = ? AND c.active = TRUE
                        AND c.binding_revision = ? AND c.catalog_revision = ? AND c.content_kind = 'TEXT' AND l.locator_scope = 'CHAPTER'
                    """, owner, shelfId.toString(), chapterId.toString(), binding.get("binding_revision"), binding.get("catalog_revision"));
            if (rows.size() != 1) {
                throw failure(ErrorCode.READER_STATE_NOT_FOUND);
            }
            Map<String, Object> row = rows.getFirst();
            return new ReadScope(owner, shelfId, chapterId, UUID.fromString(string(binding, "id")), number(binding, "binding_revision"),
                    number(binding, "catalog_revision"), string(binding, "catalog_sha256"), UUID.fromString(string(binding, "source_id")),
                    Math.toIntExact(number(binding, "source_version")), string(row, "chapter_key_sha256"), bookLocator(owner, binding),
                    new SourceLocator.Sealed(string(row, "locator_ciphertext"), string(row, "locator_sha256"),
                            string(row, "allowed_scheme"), string(row, "allowed_host")));
        });
    }

    /** 网络返回后按原作用域记录最新摘要，旧目录、重绑或移出书架都不能回写。 */
    public void recordContentHash(ReadScope scope, String sha256) {
        AdaptationText.requireSha256(sha256, ErrorCode.CHAPTER_SOURCE_CHANGED);
        transaction.executeWithoutResult(status -> {
            Map<String, Object> binding = requireReady(scope.ownerId(), scope.shelfBookId());
            requireReadRevision(scope, binding);
            int updated = jdbc.update("""
                    UPDATE shelf_book_chapter SET content_sha256 = ?, updated_at = ? WHERE owner_id = ? AND shelf_book_id = ?
                        AND id = ? AND content_binding_id = ? AND binding_revision = ? AND catalog_revision = ? AND active = TRUE
                    """, sha256, timestamp(now()), scope.ownerId(), scope.shelfBookId().toString(), scope.chapterId().toString(),
                    scope.bindingId().toString(), scope.bindingRevision(), scope.catalogRevision());
            if (updated != 1) {
                throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
            }
        });
    }

    /** 原站目录变化时标记过期，下一次 ensure 用新准备轮次刷新但保留稳定章节 ID。 */
    public void markCatalogStale(ReadScope scope) {
        transaction.executeWithoutResult(status -> {
            lockShelf(scope.ownerId(), scope.shelfBookId());
            jdbc.update("""
                    UPDATE shelf_book_content_binding SET status = 'STALE', last_error_code = ?, updated_at = ?
                    WHERE owner_id = ? AND id = ? AND binding_revision = ? AND catalog_revision = ? AND status = 'ACTIVE'
                    """, ErrorCode.CHAPTER_CATALOG_STALE.code(), timestamp(now()), scope.ownerId(), scope.bindingId().toString(),
                    scope.bindingRevision(), scope.catalogRevision());
        });
    }

    /** 分批回收已成功目录的临时副本，不删除权威章节、locator 或业务历史。 */
    public void cleanupStaging() {
        var rows = jdbc.queryForList("""
                SELECT s.id, s.owner_id FROM shelf_book_catalog_projection_staging s
                JOIN shelf_book_preparation_dispatch d ON d.id = s.dispatch_id AND d.owner_id = s.owner_id
                WHERE d.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') ORDER BY s.created_at, s.id LIMIT 200
                """);
        jdbc.batchUpdate("DELETE FROM shelf_book_catalog_projection_staging WHERE owner_id = ? AND id = ?",
                rows.stream().map(row -> new Object[]{row.get("owner_id"), row.get("id")}).toList());
    }

    private Map<String, Object> requireReady(long owner, UUID shelfId) {
        Shelf shelf = lockShelf(owner, shelfId);
        Map<String, Object> binding = binding(owner, shelfId);
        if (binding == null || !"ACTIVE".equals(binding.get("status")) || !"SOURCE_RUNTIME".equals(binding.get("binding_type"))) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        if (number(binding, "bound_shelf_version") != shelf.version()) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        return binding;
    }

    private void requireReadRevision(ReadScope scope, Map<String, Object> binding) {
        if (!scope.bindingId().toString().equals(binding.get("id")) || scope.bindingRevision() != number(binding, "binding_revision")
                || scope.catalogRevision() != number(binding, "catalog_revision")) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
    }

    private void insertLocator(UUID id, SourceLocator.Scope scope, SourceLocator.Sealed sealed, Instant now) {
        jdbc.update("""
                INSERT INTO shelf_book_source_locator (id, owner_id, binding_id, binding_revision, source_version, locator_scope,
                    locator_ciphertext, locator_sha256, allowed_scheme, allowed_host, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), scope.ownerId(), scope.bindingId().toString(), scope.bindingRevision(), scope.sourceVersion(), scope.role(),
                sealed.ciphertext(), sealed.sha256(), sealed.scheme(), sealed.host(), timestamp(now));
    }

    private SourceLocator.Sealed bookLocator(long ownerId, Map<String, Object> binding) {
        var rows = jdbc.queryForList("""
                SELECT * FROM shelf_book_source_locator WHERE owner_id = ? AND binding_id = ? AND binding_revision = ?
                    AND source_version = ? AND locator_scope = 'BOOK'
                """, ownerId, binding.get("id"), binding.get("binding_revision"), binding.get("source_version"));
        if (rows.size() != 1) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        Map<String, Object> row = rows.getFirst();
        return new SourceLocator.Sealed(string(row, "locator_ciphertext"), string(row, "locator_sha256"),
                string(row, "allowed_scheme"), string(row, "allowed_host"));
    }

    private void markBroken(long owner, Map<String, Object> binding, Map<String, Object> dispatch, ErrorCode error) {
        jdbc.update("UPDATE shelf_book_preparation_dispatch SET status = 'FAILED', last_error_code = ?, claim_owner = NULL,"
                + " claimed_until = NULL, updated_at = ? WHERE owner_id = ? AND id = ?", error.code(), timestamp(now()), owner, dispatch.get("id"));
        jdbc.update("UPDATE shelf_book_content_binding SET status = 'BROKEN', last_error_code = ?, updated_at = ?"
                + " WHERE owner_id = ? AND id = ?", error.code(), timestamp(now()), owner, binding.get("id"));
    }

    private Optional<Shelf> loadShelf(long owner, UUID shelf, String lockSuffix) {
        return jdbc.query("SELECT * FROM shelf_book WHERE owner_id = ? AND id = ? AND deleted = FALSE" + lockSuffix,
                (row, ordinal) -> {
                    JsonNode metadata = json(row.getString("metadata_json"));
                    return new Shelf(shelf, owner, row.getLong("version"), metadata.path("sourceId").asText(""),
                            metadata.path("resourceUri").asText(""), metadata.path("origin").asText(""), metadata.path("format").asText(""));
                }, owner, shelf.toString()).stream().findFirst();
    }

    private Shelf lockShelf(long owner, UUID shelf) {
        return loadShelf(owner, shelf, " FOR UPDATE").orElseThrow(() -> failure(ErrorCode.READER_STATE_NOT_FOUND));
    }

    private Map<String, Object> binding(long owner, UUID shelf) {
        return jdbc.queryForList("SELECT * FROM shelf_book_content_binding WHERE owner_id = ? AND shelf_book_id = ?",
                owner, shelf.toString()).stream().findFirst().orElse(null);
    }

    private ShelfChapterModels.Capability capabilityOf(UUID shelf, Map<String, Object> binding) {
        if (binding == null) {
            return new ShelfChapterModels.Capability(shelf, "UNAVAILABLE", 0, 0, null, ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE.code(), null);
        }
        String status = switch (string(binding, "status")) {
            case "ACTIVE" -> "READY";
            case "PREPARING" -> "PREPARING";
            case "BROKEN" -> "BROKEN";
            default -> "UNAVAILABLE";
        };
        return new ShelfChapterModels.Capability(shelf, status, number(binding, "binding_revision"), number(binding, "catalog_revision"),
                (String) binding.get("catalog_sha256"), (String) binding.get("last_error_code"), "PREPARING".equals(status) ? 1500 : null);
    }

    private JsonNode json(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            return node.isTextual() ? mapper.readTree(node.textValue()) : node;
        } catch (JsonProcessingException exception) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String string(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static ChapterAdaptationException failure(ErrorCode error) {
        return new ChapterAdaptationException(error);
    }
}
