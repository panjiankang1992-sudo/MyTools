package com.yuyutian.mytools.drive.repository;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Drive 账户与索引仓储。 */
@Repository
public class DriveRepository {
    private final JdbcTemplate jdbc;
    /** 创建仓储。 @param jdbc JDBC 模板 */
    public DriveRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 幂等登记账户。 @param request 请求 @return 账户 */
    public AccountView register(RegisterAccountRequest request) {
        List<AccountView> existing = jdbc.query("SELECT * FROM drive_account WHERE owner_id=? AND external_account_id=?",
            (rs, row) -> account(rs), request.ownerId(), request.externalAccountId());
        if (!existing.isEmpty()) {
            Integer matching = jdbc.queryForObject("SELECT COUNT(*) FROM drive_account WHERE owner_id=? AND external_account_id=? AND display_name=? AND provider_type=? AND provider_secret_ref=? AND remote_key=? AND read_only=? AND enabled=?",
                Integer.class, request.ownerId(), request.externalAccountId(), request.displayName(), request.providerType(),
                request.providerSecretRef(), request.remoteKey(), request.readOnly(), request.enabled());
            if (matching == null || matching != 1) throw new IllegalStateException("drive account idempotency conflict");
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.update("INSERT INTO drive_account VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", id.toString(), request.ownerId(),
            request.externalAccountId(), request.displayName(), request.providerType(), request.providerSecretRef(),
            request.remoteKey(), request.readOnly(), request.enabled(), 0L, Timestamp.from(now), Timestamp.from(now));
        return findAccount(id).orElseThrow();
    }

    /** 迁移一个冻结的旧账户批次。 @param request 迁移批次 @return 批次证据 */
    public LegacyAccountMigrationResult migrateLegacyAccounts(LegacyAccountMigrationBatch request) {
        MessageDigest collection = digest();
        int accepted = 0;
        int skipped = 0;
        List<LegacyAccountMigrationItem> items = request.items().stream()
            .sorted(Comparator.comparing(LegacyAccountMigrationItem::sourceSystem)
                .thenComparingLong(LegacyAccountMigrationItem::legacyAccountId))
            .toList();
        for (LegacyAccountMigrationItem item : items) {
            String payloadSha256 = legacyAccountDigest(item);
            updateDigest(collection, item.sourceSystem(), Long.toString(item.legacyAccountId()), payloadSha256);
            List<LegacyMigrationRow> existing = jdbc.query("""
                SELECT migration_key,payload_sha256,target_account_id
                FROM drive_account_migration WHERE source_system=? AND legacy_account_id=?
                """, (resultSet, rowNumber) -> new LegacyMigrationRow(
                    resultSet.getString("migration_key"), resultSet.getString("payload_sha256"),
                    UUID.fromString(resultSet.getString("target_account_id"))),
                item.sourceSystem(), item.legacyAccountId());
            if (!existing.isEmpty()) {
                LegacyMigrationRow row = existing.getFirst();
                if (!request.migrationKey().equals(row.migrationKey())
                        || !payloadSha256.equals(row.payloadSha256())) {
                    throw new IllegalStateException("drive account migration conflict");
                }
                skipped++;
                continue;
            }
            AccountView account = register(item.account());
            jdbc.update("""
                INSERT INTO drive_account_migration
                    (migration_key,source_system,legacy_account_id,payload_sha256,target_account_id,created_at)
                VALUES (?,?,?,?,?,?)
                """, request.migrationKey(), item.sourceSystem(), item.legacyAccountId(), payloadSha256,
                account.id().toString(), Timestamp.from(Instant.now()));
            accepted++;
        }
        return new LegacyAccountMigrationResult(request.migrationKey(), request.dryRun(), items.size(),
            accepted, skipped, 0, HexFormat.of().formatHex(collection.digest()));
    }

    /** 查询一次正式旧账户迁移的目标集合证据。 @param migrationKey 迁移键 @return 集合证据 */
    public LegacyAccountMigrationEvidence legacyAccountMigrationEvidence(String migrationKey) {
        MessageDigest collection = digest();
        long[] count = {0};
        jdbc.query("""
            SELECT source_system,legacy_account_id,payload_sha256
            FROM drive_account_migration WHERE migration_key=?
            ORDER BY source_system,legacy_account_id
            """, resultSet -> {
                updateDigest(collection, resultSet.getString("source_system"),
                    Long.toString(resultSet.getLong("legacy_account_id")),
                    resultSet.getString("payload_sha256"));
                count[0]++;
            }, migrationKey);
        return new LegacyAccountMigrationEvidence(migrationKey, count[0],
            HexFormat.of().formatHex(collection.digest()));
    }

    private String legacyAccountDigest(LegacyAccountMigrationItem item) {
        RegisterAccountRequest account = item.account();
        MessageDigest payload = digest();
        updateDigest(payload, Long.toString(account.ownerId()), account.externalAccountId(),
            account.displayName(), account.providerType(), account.providerSecretRef(), account.remoteKey(),
            Boolean.toString(account.readOnly()), Boolean.toString(account.enabled()));
        return HexFormat.of().formatHex(payload.digest());
    }

    private record LegacyMigrationRow(String migrationKey, String payloadSha256, UUID targetAccountId) { }

    /** 查询账户。 @param id 账户标识 @return 账户 */
    public Optional<AccountView> findAccount(UUID id) {
        return jdbc.query("SELECT * FROM drive_account WHERE id=?", (rs,row)->account(rs), id.toString()).stream().findFirst();
    }

    /** 查询所有者的账户。 @param ownerId 所有者标识 @return 账户列表 */
    public List<AccountView> listAccounts(long ownerId) {
        return jdbc.query("SELECT * FROM drive_account WHERE owner_id=? ORDER BY display_name,id",
            (rs,row)->account(rs),ownerId);
    }

    /** 绑定 Storage Gateway Provider。 @param accountId 账户 @param providerId Provider 标识 */
    public void bindStorageProvider(UUID accountId, UUID providerId) {
        List<String> existing = jdbc.query("SELECT storage_provider_id FROM drive_storage_provider_binding WHERE account_id=?",
                (rs, row) -> rs.getString(1), accountId.toString());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(providerId.toString())) {
                throw new IllegalStateException("drive storage provider binding conflict");
            }
            return;
        }
        Instant now = Instant.now();
        jdbc.update("INSERT INTO drive_storage_provider_binding VALUES (?,?,?,?)", accountId.toString(),
                providerId.toString(), Timestamp.from(now), Timestamp.from(now));
    }

    /** 查询绑定的 Storage Gateway Provider。 @param accountId 账户 @return Provider 标识 */
    public Optional<UUID> findStorageProvider(UUID accountId) {
        return jdbc.query("SELECT storage_provider_id FROM drive_storage_provider_binding WHERE account_id=?",
                (rs, row) -> UUID.fromString(rs.getString(1)), accountId.toString()).stream().findFirst();
    }

    /** 查询待绑定 Storage Provider 的安全账户分页。 @param afterId 游标 @param limit 数量 @return 分页 */
    public StorageMigrationPage listStorageMigrationAccounts(UUID afterId,int limit) {
        String sql="""
                SELECT id, remote_key, provider_secret_ref, enabled FROM drive_account
                WHERE (? IS NULL OR id > ?) ORDER BY id LIMIT ?
                """;
        List<StorageMigrationAccount> items=jdbc.query(sql,(rs,row)->new StorageMigrationAccount(
            UUID.fromString(rs.getString("id")),rs.getString("remote_key"),
            rs.getString("provider_secret_ref"),rs.getBoolean("enabled")),
            afterId==null?null:afterId.toString(),afterId==null?null:afterId.toString(),limit);
        UUID next=items.size()<limit?null:items.getLast().id();
        return new StorageMigrationPage(List.copyOf(items),next);
    }

    /** 计算当前有效索引的确定性摘要。 @param accountId 账户 @return 数量和摘要 */
    public IndexDigest indexDigest(UUID accountId) {
        MessageDigest digest=digest(); long[] count={0};
        jdbc.query("""
                SELECT remote_path,display_name,directory,size_bytes,modified_at,content_sha256
                FROM drive_item_index WHERE account_id=? AND deleted=FALSE ORDER BY remote_path
                """,rs->{
            updateDigest(digest,rs.getString("remote_path"),rs.getString("display_name"),
                Boolean.toString(rs.getBoolean("directory")),Long.toString(rs.getLong("size_bytes")),
                rs.getTimestamp("modified_at")==null?"":rs.getTimestamp("modified_at").toInstant()
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),
                Objects.toString(rs.getString("content_sha256"),"")); count[0]++;
        },accountId.toString());
        return new IndexDigest(count[0],HexFormat.of().formatHex(digest.digest()));
    }

    /** 写入一个幂等索引批次。 @param account 账户 @param request 批次 @return 结果 */
    public IndexBatchView ingest(AccountView account, IndexBatchRequest request) {
        Cursor cursor = cursor(account.id());
        Integer accepted = jdbc.queryForObject("SELECT COUNT(*) FROM drive_index_batch WHERE account_id=? AND run_id=? AND batch_key=?",
            Integer.class, account.id().toString(), request.runId().toString(), request.batchKey());
        if (accepted != null && accepted > 0) {
            long existingGeneration = cursor == null ? account.indexGeneration() : cursor.generation();
            String existingStatus = cursor == null ? "SUCCEEDED" : cursor.status();
            return new IndexBatchView(request.runId(), existingGeneration,
                cursor == null ? null : cursor.nextCursor(), existingStatus, 0);
        }
        long generation;
        if (cursor == null || !cursor.runId().equals(request.runId())) {
            if (cursor != null && "RUNNING".equals(cursor.status())) throw new IllegalStateException("index run is active");
            generation = account.indexGeneration() + 1;
            Instant now = Instant.now();
            if (cursor == null) {
                jdbc.update("INSERT INTO drive_index_cursor VALUES (?,?,?,?,?,'RUNNING',?,?)", account.id().toString(),
                    request.runId().toString(), generation, null, null, Timestamp.from(now), Timestamp.from(now));
            } else {
                jdbc.update("UPDATE drive_index_cursor SET run_id=?,generation=?,last_batch_key=NULL,next_cursor=NULL,status='RUNNING',started_at=?,updated_at=? WHERE account_id=?",
                    request.runId().toString(), generation, Timestamp.from(now), Timestamp.from(now), account.id().toString());
            }
        } else {
            generation = cursor.generation();
            if (request.batchKey().equals(cursor.lastBatchKey()))
                return new IndexBatchView(request.runId(), generation, cursor.nextCursor(), cursor.status(), 0);
            if (!"RUNNING".equals(cursor.status())) throw new IllegalStateException("index run is complete");
        }
        Instant now = Instant.now();
        for (IndexItem item : request.items()) upsert(account.id(), generation, item, now);
        jdbc.update("INSERT INTO drive_index_batch VALUES (?,?,?,?,?)", account.id().toString(),
            request.runId().toString(), request.batchKey(), request.items().size(), Timestamp.from(now));
        String status = request.complete() ? "SUCCEEDED" : "RUNNING";
        jdbc.update("UPDATE drive_index_cursor SET last_batch_key=?,next_cursor=?,status=?,updated_at=? WHERE account_id=?",
            request.batchKey(), request.nextCursor(), status, Timestamp.from(now), account.id().toString());
        if (request.complete()) {
            jdbc.update("UPDATE drive_item_index SET deleted=TRUE,updated_at=? WHERE account_id=? AND generation<>? AND deleted=FALSE",
                Timestamp.from(now), account.id().toString(), generation);
            jdbc.update("UPDATE drive_account SET index_generation=?,updated_at=? WHERE id=?", generation,
                Timestamp.from(now), account.id().toString());
        }
        return new IndexBatchView(request.runId(), generation, request.nextCursor(), status, request.items().size());
    }

    /** 查询直接子项。 @param accountId 账户 @param parentPath 父路径 @return 子项 */
    public List<ItemView> list(UUID accountId, String parentPath) {
        return jdbc.query("SELECT * FROM drive_item_index WHERE account_id=? AND parent_path=? AND deleted=FALSE ORDER BY directory DESC,display_name,id",
            (rs,row)->new ItemView(UUID.fromString(rs.getString("id")), rs.getString("remote_id"), rs.getString("remote_path"),
                rs.getString("parent_path"), rs.getString("display_name"), inferredMimeType(
                        rs.getString("mime_type"), rs.getString("display_name")), rs.getLong("size_bytes"),
                rs.getBoolean("directory"), rs.getTimestamp("modified_at") == null ? null : rs.getTimestamp("modified_at").toInstant(),
                rs.getString("content_sha256")), accountId.toString(), parentPath);
    }

    /** 当远端未返回 MIME 时，根据文件名补齐常见类型。 */
    private static String inferredMimeType(String mimeType, String displayName) {
        if (mimeType != null && !mimeType.isBlank()) return mimeType;
        String inferred = URLConnection.guessContentTypeFromName(displayName);
        return inferred == null ? "" : inferred;
    }
    /** 创建或读取索引刷新操作。 @param operationId 操作标识 @param accountId 账户标识 @param taskId 任务标识 @param idempotencyKey 幂等键 @return 操作 */
    public OperationView saveIndexOperation(UUID operationId, UUID accountId, UUID taskId, String idempotencyKey) {
        OperationView existing=findOperationByIdempotencyKey(idempotencyKey).orElse(null);
        if(existing!=null) {
            if(!existing.accountId().equals(accountId)||!existing.taskInstanceId().equals(taskId))
                throw new IllegalStateException("drive operation idempotency conflict");
            return existing;
        }
        Instant now=Instant.now();
        jdbc.update("INSERT INTO drive_operation VALUES (?,?,?,?,?,'{}',NULL,?,?)",operationId.toString(),
            accountId.toString(),"INDEX_ACCOUNT",idempotencyKey,"PENDING",Timestamp.from(now),Timestamp.from(now));
        jdbc.update("INSERT INTO drive_task_binding VALUES (?,?,?)",operationId.toString(),taskId.toString(),Timestamp.from(now));
        return findOperation(operationId).orElseThrow();
    }

    /**
     * 创建或读取 Storage 托管的 Drive 操作。
     *
     * @param operation Storage 操作
     * @param accountId 来源账户
     * @param idempotencyKey Drive 幂等键
     * @return Drive 操作
     */
    public OperationView saveStorageOperation(StorageOperationView operation, UUID accountId, String idempotencyKey) {
        OperationView existing = findOperationByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.id().equals(operation.id()) || !existing.accountId().equals(accountId)) {
                throw new IllegalStateException("drive operation idempotency conflict");
            }
            return existing;
        }
        Instant now = Instant.now();
        jdbc.update("INSERT INTO drive_operation VALUES (?,?,?,?,?,'{}',NULL,?,?)", operation.id().toString(),
                accountId.toString(), operation.operationType(), idempotencyKey, operation.status(),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO drive_task_binding VALUES (?,?,?)", operation.id().toString(),
                operation.taskInstanceId().toString(), Timestamp.from(now));
        return findOperation(operation.id()).orElseThrow();
    }

    /** 查询操作。 @param operationId 操作标识 @return 操作 */
    public Optional<OperationView> findOperation(UUID operationId) {
        return jdbc.query("""
            SELECT o.*,b.task_instance_id FROM drive_operation o JOIN drive_task_binding b ON b.operation_id=o.id
            WHERE o.id=?
            """,(rs,row)->operation(rs),operationId.toString()).stream().findFirst();
    }

    /** 按幂等键查询操作。 @param idempotencyKey 幂等键 @return 操作 */
    public Optional<OperationView> findOperationByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
            SELECT o.*,b.task_instance_id FROM drive_operation o JOIN drive_task_binding b ON b.operation_id=o.id
            WHERE o.idempotency_key=?
            """,(rs,row)->operation(rs),idempotencyKey).stream().findFirst();
    }

    /** 更新操作状态。 @param operationId 操作标识 @param status 状态 @return 操作 */
    public OperationView updateOperationStatus(UUID operationId,String status) {
        return updateOperationStatus(operationId, status, null);
    }

    /**
     * 更新操作状态和错误码。
     *
     * @param operationId 操作标识
     * @param status 状态
     * @param errorCode 错误码
     * @return 操作
     */
    public OperationView updateOperationStatus(UUID operationId, String status, String errorCode) {
        jdbc.update("UPDATE drive_operation SET status=?,error_code=?,updated_at=? WHERE id=?", status, errorCode,
            Timestamp.from(Instant.now()), operationId.toString());
        return findOperation(operationId).orElseThrow();
    }
    /** 结束未完成的索引运行。 @param accountId 账户 @param runId 运行 @param status 终态 */
    public void finishRun(UUID accountId,UUID runId,String status) {
        int updated=jdbc.update("UPDATE drive_index_cursor SET status=?,updated_at=? WHERE account_id=? AND run_id=? AND status='RUNNING'",
            status,Timestamp.from(Instant.now()),accountId.toString(),runId.toString());
        if(updated==0) {
            Cursor cursor=cursor(accountId);
            if(cursor==null||!cursor.runId().equals(runId)||!status.equals(cursor.status()))
                throw new IllegalStateException("drive index run is not active");
        }
    }

    private void upsert(UUID accountId, long generation, IndexItem item, Instant now) {
        String pathHash = sha256(item.remotePath());
        List<String> matchedPaths = jdbc.query("SELECT remote_path FROM drive_item_index WHERE account_id=? AND path_sha256=?",
            (rs,row)->rs.getString(1), accountId.toString(), pathHash);
        if (!matchedPaths.isEmpty() && !matchedPaths.getFirst().equals(item.remotePath()))
            throw new IllegalStateException("drive path hash collision");
        int updated = jdbc.update("UPDATE drive_item_index SET remote_id=?,parent_path=?,display_name=?,mime_type=?,size_bytes=?,directory=?,modified_at=?,content_sha256=?,generation=?,deleted=FALSE,updated_at=? WHERE account_id=? AND path_sha256=?",
            item.remoteId(), item.parentPath(), item.displayName(), item.mimeType(), item.sizeBytes(), item.directory(),
            item.modifiedAt()==null?null:Timestamp.from(item.modifiedAt()), item.contentSha256(), generation, Timestamp.from(now),
            accountId.toString(), pathHash);
        if (updated == 0) jdbc.update("INSERT INTO drive_item_index VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,?,?)",
            UUID.randomUUID().toString(), accountId.toString(), item.remoteId(), item.remotePath(), pathHash, item.parentPath(),
            item.displayName(), item.mimeType(), item.sizeBytes(), item.directory(), item.modifiedAt()==null?null:Timestamp.from(item.modifiedAt()),
            item.contentSha256(), generation, Timestamp.from(now), Timestamp.from(now));
    }
    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable",exception); }
    }
    private void updateDigest(MessageDigest digest,String... values) {
        for(String value:values) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private Cursor cursor(UUID id) {
        return jdbc.query("SELECT * FROM drive_index_cursor WHERE account_id=?", (rs,row)->new Cursor(
            UUID.fromString(rs.getString("run_id")), rs.getLong("generation"), rs.getString("last_batch_key"),
            rs.getString("next_cursor"), rs.getString("status")), id.toString()).stream().findFirst().orElse(null);
    }
    private AccountView account(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AccountView(UUID.fromString(rs.getString("id")), rs.getLong("owner_id"), rs.getString("external_account_id"),
            rs.getString("display_name"), rs.getString("provider_type"), rs.getString("remote_key"), rs.getBoolean("read_only"),
            rs.getBoolean("enabled"), rs.getLong("index_generation"));
    }
    private OperationView operation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OperationView(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("account_id")),
            UUID.fromString(rs.getString("task_instance_id")),rs.getString("operation_type"),rs.getString("status"),
            rs.getString("error_code"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    private record Cursor(UUID runId,long generation,String lastBatchKey,String nextCursor,String status) { }
}
