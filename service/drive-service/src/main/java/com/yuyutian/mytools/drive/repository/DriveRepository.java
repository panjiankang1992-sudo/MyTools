package com.yuyutian.mytools.drive.repository;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /** 查询账户。 @param id 账户标识 @return 账户 */
    public Optional<AccountView> findAccount(UUID id) {
        return jdbc.query("SELECT * FROM drive_account WHERE id=?", (rs,row)->account(rs), id.toString()).stream().findFirst();
    }

    /** 写入一个幂等索引批次。 @param account 账户 @param request 批次 @return 结果 */
    public IndexBatchView ingest(AccountView account, IndexBatchRequest request) {
        Cursor cursor = cursor(account.id());
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
                rs.getString("parent_path"), rs.getString("display_name"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                rs.getBoolean("directory"), rs.getTimestamp("modified_at") == null ? null : rs.getTimestamp("modified_at").toInstant(),
                rs.getString("content_sha256")), accountId.toString(), parentPath);
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
    private record Cursor(UUID runId,long generation,String lastBatchKey,String nextCursor,String status) { }
}
