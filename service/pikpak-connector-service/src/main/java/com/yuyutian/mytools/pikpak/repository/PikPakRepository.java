package com.yuyutian.mytools.pikpak.repository;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static com.yuyutian.mytools.pikpak.common.ErrorCode.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PikPak 账户、操作检查点与对象仓储。 */
@Repository
public class PikPakRepository {
    private final JdbcTemplate jdbc;

    /** 创建仓储。 @param jdbc JDBC 模板 */
    public PikPakRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等登记账户。 @param request 请求 @return 账户 */
    public Account registerAccount(RegisterAccountRequest request) {
        Optional<Account> existing = findAccountByExternalKey(request.externalKey());
        if (existing.isPresent()) {
            Account account = existing.get();
            if (!account.storageProviderId().equals(request.storageProviderId())
                    || !account.secretRef().equals(request.secretRef())
                    || !account.remoteKey().equals(request.remoteKey())
                    || !account.offlineRoot().equals(request.offlineRoot())
                    || !account.readyRoot().equals(request.readyRoot())
                    || account.enabled() != request.enabled()
                    || account.stableSeconds() != request.stableSeconds()) {
                throw new IllegalStateException(ACCOUNT_CONFLICT.code());
            }
            return account;
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO pikpak_account
            (id,external_key,storage_provider_id,secret_ref,remote_key,offline_root,ready_root,enabled,
             created_at,updated_at,stable_seconds) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, id.toString(),
            request.externalKey(), request.storageProviderId().toString(), request.secretRef(),
            request.remoteKey(), request.offlineRoot(), request.readyRoot(), request.enabled(),
            Timestamp.from(now), Timestamp.from(now), request.stableSeconds());
        return requireAccount(id);
    }

    /** 查询账户。 @param id 账户标识 @return 账户 */
    public Account requireAccount(UUID id) {
        return jdbc.query("SELECT * FROM pikpak_account WHERE id=?", this::mapAccount, id.toString())
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException(ACCOUNT_NOT_FOUND.code()));
    }

    /** 查询幂等操作。 @param key 幂等键 @return 操作 */
    public Optional<Operation> findOperationByKey(String key) {
        return jdbc.query("SELECT * FROM pikpak_offline_operation WHERE idempotency_key=?",
            this::mapOperation, key).stream().findFirst();
    }

    /** 查询操作。 @param id 操作标识 @return 操作 */
    public Operation requireOperation(UUID id) {
        return jdbc.query("SELECT * FROM pikpak_offline_operation WHERE id=?", this::mapOperation,
            id.toString()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException(OPERATION_NOT_FOUND.code()));
    }

    /** 创建操作。 @param operation 操作 @return 已保存操作 */
    public Operation insertOperation(Operation operation) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO pikpak_offline_operation
            (id,account_id,idempotency_key,business_type,business_id,input_sha256,work_token,phase,
             stable_signature,stable_since,remote_job_id,error_code,version,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,'CREATED',NULL,NULL,NULL,NULL,0,?,?)
            """, operation.id().toString(), operation.accountId().toString(), operation.idempotencyKey(),
            operation.businessType(), operation.businessId(), operation.inputSha256(), operation.workToken(),
            Timestamp.from(now), Timestamp.from(now));
        return requireOperation(operation.id());
    }

    /** 原子推进阶段。 @param current 当前操作 @param phase 新阶段 @param signature 签名 @param stableSince 稳定起点 @param jobId 远端任务 @param errorCode 错误码 @return 新操作 */
    public Operation transition(Operation current, String phase, String signature, Instant stableSince,
                                Long jobId, String errorCode) {
        int changed = jdbc.update("""
            UPDATE pikpak_offline_operation SET phase=?,stable_signature=?,stable_since=?,remote_job_id=?,
            error_code=?,version=version+1,updated_at=? WHERE id=? AND version=?
            """, phase, signature, stableSince == null ? null : Timestamp.from(stableSince), jobId,
            errorCode, Timestamp.from(Instant.now()), current.id().toString(), current.version());
        if (changed != 1) {
            throw new IllegalStateException(OPERATION_CONCURRENT_UPDATE.code());
        }
        appendEvent(current.id(), phase, errorCode);
        return requireOperation(current.id());
    }

    /** 替换一次稳定对象快照。 @param operationId 操作标识 @param items 对象 */
    public void replaceItems(UUID operationId, List<RemoteItem> items) {
        jdbc.update("DELETE FROM pikpak_operation_item WHERE operation_id=?", operationId.toString());
        for (RemoteItem item : items) {
            jdbc.update("INSERT INTO pikpak_operation_item VALUES (?,?,?,?,?)", operationId.toString(),
                item.remoteFileId(), item.relativePath(), item.sizeBytes(), item.modifiedAt());
        }
    }

    /** 读取稳定对象。 @param operationId 操作标识 @return 对象 */
    public List<RemoteItem> listItems(UUID operationId) {
        return jdbc.query("""
            SELECT remote_file_id,relative_path,size_bytes,modified_at FROM pikpak_operation_item
            WHERE operation_id=? ORDER BY relative_path,remote_file_id
            """, (rs, row) -> new RemoteItem(rs.getString(1), rs.getString(2), rs.getLong(3),
            rs.getString(4)), operationId.toString());
    }

    private Optional<Account> findAccountByExternalKey(String key) {
        return jdbc.query("SELECT * FROM pikpak_account WHERE external_key=?", this::mapAccount, key)
            .stream().findFirst();
    }

    private Account mapAccount(ResultSet rs, int row) throws SQLException {
        return new Account(UUID.fromString(rs.getString("id")), rs.getString("external_key"),
            UUID.fromString(rs.getString("storage_provider_id")), rs.getString("secret_ref"),
            rs.getString("remote_key"), rs.getString("offline_root"), rs.getString("ready_root"),
            rs.getBoolean("enabled"), rs.getInt("stable_seconds"));
    }

    private Operation mapOperation(ResultSet rs, int row) throws SQLException {
        Timestamp stable = rs.getTimestamp("stable_since");
        long jobId = rs.getLong("remote_job_id");
        Long remoteJobId = rs.wasNull() ? null : jobId;
        return new Operation(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("account_id")),
            rs.getString("idempotency_key"), rs.getString("business_type"), rs.getString("business_id"),
            rs.getString("input_sha256"), rs.getString("work_token"), rs.getString("phase"),
            rs.getString("stable_signature"), stable == null ? null : stable.toInstant(),
            remoteJobId, rs.getString("error_code"), rs.getLong("version"));
    }

    private void appendEvent(UUID operationId, String phase, String errorCode) {
        String payload = errorCode == null ? "{\"phase\":\"" + phase + "\"}"
            : "{\"phase\":\"" + phase + "\",\"errorCode\":\"" + errorCode + "\"}";
        jdbc.update("INSERT INTO pikpak_outbox_event VALUES (?,?,?,?,?,NULL)", UUID.randomUUID().toString(),
            operationId.toString(), "PIKPAK_OPERATION_PHASE_CHANGED", payload, Timestamp.from(Instant.now()));
    }
}
