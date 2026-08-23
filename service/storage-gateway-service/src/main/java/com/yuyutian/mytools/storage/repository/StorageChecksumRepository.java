package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.model.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地对象校验和操作仓储。
 */
@Repository
public class StorageChecksumRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建校验和仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public StorageChecksumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增校验和操作。
     *
     * @param operation 操作
     */
    public void insert(ChecksumOperation operation) {
        jdbcTemplate.update("""
                INSERT INTO storage_checksum_operation
                    (id, root_id, idempotency_key, relative_path, status, task_instance_id,
                     size_bytes, content_sha256, error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?)
                """, operation.id().toString(), operation.rootId().toString(), operation.idempotencyKey(),
                operation.relativePath(), operation.status(), Timestamp.from(operation.createdAt()),
                Timestamp.from(operation.updatedAt()));
    }

    /**
     * 按幂等键查询。
     *
     * @param idempotencyKey 幂等键
     * @return 可选操作
     */
    public Optional<ChecksumOperation> findByKey(String idempotencyKey) {
        return query("sco.idempotency_key = ?", idempotencyKey);
    }

    /**
     * 按标识查询。
     *
     * @param id 操作标识
     * @return 可选操作
     */
    public Optional<ChecksumOperation> findById(UUID id) {
        return query("sco.id = ?", id.toString());
    }

    /**
     * 幂等绑定任务实例。
     *
     * @param id 操作标识
     * @param taskId 任务实例标识
     */
    public void bindTask(UUID id, UUID taskId) {
        int updated = jdbcTemplate.update("""
                UPDATE storage_checksum_operation SET task_instance_id = ?, status = 'RUNNING', updated_at = ?
                WHERE id = ? AND task_instance_id IS NULL AND status = 'CREATED'
                """, taskId.toString(), Timestamp.from(Instant.now()), id.toString());
        if (updated != 1) {
            ChecksumOperation current = findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(ErrorCode.CHECKSUM_NOT_FOUND.code()));
            if (!taskId.equals(current.taskInstanceId())) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
        }
    }

    /**
     * 设置不可变终态。
     *
     * @param id 操作标识
     * @param status 终态
     * @param sizeBytes 文件大小
     * @param sha256 内容摘要
     * @param errorCode 错误码
     */
    public void finish(UUID id, String status, Long sizeBytes, String sha256, String errorCode) {
        jdbcTemplate.update("""
                UPDATE storage_checksum_operation
                SET status = ?, size_bytes = ?, content_sha256 = ?, error_code = ?, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'RUNNING')
                """, status, sizeBytes, sha256, errorCode, Timestamp.from(Instant.now()), id.toString());
    }

    private Optional<ChecksumOperation> query(String predicate, Object argument) {
        return jdbcTemplate.query("""
                SELECT sco.*, sr.name AS root_name
                FROM storage_checksum_operation sco JOIN storage_root sr ON sr.id = sco.root_id
                WHERE """ + predicate, (resultSet, rowNumber) -> new ChecksumOperation(
                UUID.fromString(resultSet.getString("id")), UUID.fromString(resultSet.getString("root_id")),
                resultSet.getString("root_name"), resultSet.getString("idempotency_key"),
                resultSet.getString("relative_path"), resultSet.getString("status"),
                resultSet.getString("task_instance_id") == null ? null
                        : UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getObject("size_bytes", Long.class), resultSet.getString("content_sha256"),
                resultSet.getString("error_code"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), argument).stream().findFirst();
    }
}
