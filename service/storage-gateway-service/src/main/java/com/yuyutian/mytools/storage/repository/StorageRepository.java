package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.UploadRecord;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.AccessTicketRecord;
import com.yuyutian.mytools.storage.model.ReconciliationDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 受管根和上传状态仓储。
 */
@Repository
public class StorageRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建存储仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public StorageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 确保默认本地受管根存在并同步配置路径。
     *
     * @param name 根名称
     * @param purpose 根用途
     * @param basePath 绝对路径
     * @param nodeAffinityLabel 节点亲和标签
     * @param nodeAffinityValue 节点亲和值
     */
    public void ensureRoot(String name, String purpose, String basePath, String nodeAffinityLabel,
                           String nodeAffinityValue) {
        List<Map<String, Object>> roots = jdbcTemplate.queryForList("SELECT id FROM storage_root WHERE name = ?", name);
        Instant now = Instant.now();
        if (roots.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO storage_root (id, name, purpose, base_path, node_affinity_label,
                                              node_affinity_value, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                    """, UUID.randomUUID().toString(), name, purpose, basePath, nodeAffinityLabel, nodeAffinityValue,
                    Timestamp.from(now), Timestamp.from(now));
            return;
        }
        jdbcTemplate.update("""
                UPDATE storage_root SET purpose = ?, base_path = ?, node_affinity_label = ?,
                    node_affinity_value = ?, enabled = TRUE, updated_at = ? WHERE name = ?
                """, purpose, basePath, nodeAffinityLabel, nodeAffinityValue, Timestamp.from(now), name);
    }

    /**
     * 查询启用的受管根。
     *
     * @param name 根名称
     * @return 根标识和路径
     */
    public Optional<ManagedRoot> findRoot(String name) {
        return jdbcTemplate.query("""
                SELECT id, name, base_path, node_affinity_label, node_affinity_value
                FROM storage_root WHERE name = ? AND enabled = TRUE
                """,
                (resultSet, rowNumber) -> new ManagedRoot(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("name"), resultSet.getString("base_path"),
                        resultSet.getString("node_affinity_label"), resultSet.getString("node_affinity_value")), name)
                .stream().findFirst();
    }

    /**
     * 新增远端 Provider。
     *
     * @param provider Provider
     */
    public void insertProvider(StorageProvider provider) {
        jdbcTemplate.update("""
                INSERT INTO storage_provider
                    (id, name, provider_type, remote_key, endpoint_uri, region_name, secret_ref, enabled,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, provider.id().toString(), provider.name(), provider.providerType(), provider.remoteKey(),
                provider.endpointUri(), provider.regionName(), provider.secretRef(), provider.enabled(),
                Timestamp.from(provider.createdAt()),
                Timestamp.from(provider.updatedAt()));
    }

    /**
     * 按名称查询远端 Provider。
     *
     * @param name 名称
     * @return Provider
     */
    public Optional<StorageProvider> findProviderByName(String name) {
        return queryProvider("name = ?", name);
    }

    /**
     * 按标识查询远端 Provider。
     *
     * @param id 标识
     * @return Provider
     */
    public Optional<StorageProvider> findProviderById(UUID id) {
        return queryProvider("id = ?", id.toString());
    }

    /**
     * 新增异步操作。
     *
     * @param operation 操作
     */
    public void insertOperation(StorageOperation operation) {
        jdbcTemplate.update("""
                INSERT INTO storage_operation
                    (id, provider_id, target_provider_id, idempotency_key, operation_type,
                     source_path, target_path, status, task_instance_id, remote_job_id,
                     result_json, error_code, item_count, maximum_objects,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, 0, ?, ?, ?)
                """, operation.id().toString(), operation.providerId().toString(),
                operation.targetProviderId() == null ? null : operation.targetProviderId().toString(),
                operation.idempotencyKey(), operation.operationType(), operation.sourcePath(),
                operation.targetPath(), operation.status(), operation.maximumObjects(),
                Timestamp.from(operation.createdAt()), Timestamp.from(operation.updatedAt()));
    }

    /**
     * 按幂等键查询异步操作。
     *
     * @param key 幂等键
     * @return 操作
     */
    public Optional<StorageOperation> findOperationByKey(String key) {
        return queryOperation("idempotency_key = ?", key);
    }

    /**
     * 按标识查询异步操作。
     *
     * @param id 标识
     * @return 操作
     */
    public Optional<StorageOperation> findOperationById(UUID id) {
        return queryOperation("id = ?", id.toString());
    }

    /**
     * 记录原生复制目标由本操作确认创建。
     *
     * @param operationId 操作标识
     */
    public void markNativeTargetCreated(UUID operationId) {
        int updated = jdbcTemplate.update("""
                UPDATE storage_operation SET native_target_created = TRUE, updated_at = ?
                WHERE id = ? AND operation_type = 'COPY_OBJECT' AND status = 'RUNNING'
                """, Timestamp.from(Instant.now()), operationId.toString());
        if (updated != 1) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
    }

    /**
     * 查询原生复制操作是否确认拥有目标。
     *
     * @param operationId 操作标识
     * @return 是否拥有目标
     */
    public boolean ownsNativeTarget(UUID operationId) {
        Boolean value = jdbcTemplate.queryForObject(
                "SELECT native_target_created FROM storage_operation WHERE id = ?", Boolean.class,
                operationId.toString());
        return Boolean.TRUE.equals(value);
    }

    /**
     * 清除已补偿删除的目标所有权标记。
     *
     * @param operationId 操作标识
     */
    public void clearNativeTargetCreated(UUID operationId) {
        jdbcTemplate.update("UPDATE storage_operation SET native_target_created = FALSE, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), operationId.toString());
    }

    /**
     * 新增只保存摘要的访问票据。
     *
     * @param ticket 票据
     */
    public void insertAccessTicket(AccessTicketRecord ticket) {
        jdbcTemplate.update("""
                INSERT INTO storage_access_ticket
                    (id, token_sha256, root_id, relative_path, permission, expires_at,
                     consumed_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)
                """, ticket.id().toString(), ticket.tokenSha256(), ticket.rootId().toString(),
                ticket.relativePath(), ticket.permission(), Timestamp.from(ticket.expiresAt()),
                Timestamp.from(ticket.createdAt()));
    }

    /**
     * 原子消费一个尚未过期、撤销或使用的票据。
     *
     * @param tokenSha256 Token 摘要
     * @param now 当前时间
     * @return 已消费票据
     */
    @Transactional
    public Optional<AccessTicketRecord> consumeAccessTicket(String tokenSha256, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE storage_access_ticket SET consumed_at = ?
                WHERE token_sha256 = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                """, Timestamp.from(now), tokenSha256, Timestamp.from(now));
        if (updated != 1) {
            return Optional.empty();
        }
        return queryAccessTicket("sat.token_sha256 = ?", tokenSha256);
    }

    /**
     * 撤销一个尚未消费的访问票据。
     *
     * @param id 票据标识
     * @return 是否找到且处于可撤销状态
     */
    public boolean revokeAccessTicket(UUID id) {
        return jdbcTemplate.update("""
                UPDATE storage_access_ticket SET revoked_at = ?
                WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, Timestamp.from(Instant.now()), id.toString()) == 1;
    }

    /**
     * 查询访问票据是否存在。
     *
     * @param id 票据标识
     * @return 票据
     */
    public Optional<AccessTicketRecord> findAccessTicket(UUID id) {
        return queryAccessTicket("sat.id = ?", id.toString());
    }

    /**
     * 按 Token 摘要查询访问票据。
     *
     * @param tokenSha256 Token 摘要
     * @return 票据
     */
    public Optional<AccessTicketRecord> findAccessTicketByHash(String tokenSha256) {
        return queryAccessTicket("sat.token_sha256 = ?", tokenSha256);
    }

    /**
     * 计算已成功扫描快照的确定性摘要。
     *
     * @param operationId 操作标识
     * @return 数量和摘要
     */
    public ReconciliationDigest operationDigest(UUID operationId) {
        StorageOperation operation = findOperationById(operationId)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.OPERATION_NOT_FOUND.code()));
        if (!"SUCCEEDED".equals(operation.status()) || !"SCAN_ROOT".equals(operation.operationType())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        MessageDigest digest = sha256Digest();
        long[] count = {0};
        jdbcTemplate.query("""
                SELECT object_path, object_name, directory, size_bytes, modified_at, content_sha256
                FROM storage_operation_item WHERE operation_id = ? ORDER BY object_path
                """, resultSet -> {
            updateDigest(digest, resultSet.getString("object_path"), resultSet.getString("object_name"),
                    Boolean.toString(resultSet.getBoolean("directory")),
                    Long.toString(resultSet.getLong("size_bytes")),
                    resultSet.getTimestamp("modified_at") == null ? ""
                            : normalizeInstant(resultSet.getTimestamp("modified_at").toInstant()).toString(),
                    java.util.Objects.toString(resultSet.getString("content_sha256"), ""));
            count[0]++;
        }, operationId.toString());
        return new ReconciliationDigest(count[0], java.util.HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 绑定任务实例并进入运行态。
     *
     * @param id 操作标识
     * @param taskId 任务标识
     */
    public void bindOperationTask(UUID id, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE storage_operation SET task_instance_id = COALESCE(task_instance_id, ?),
                    status = 'RUNNING', error_code = NULL, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'WAITING_TARGET', 'RUNNING')
                """, taskId.toString(), Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 将尚未调度的操作标记为等待目标写入栅栏。
     *
     * @param id 操作标识
     */
    public void markWaitingTarget(UUID id) {
        jdbcTemplate.update("""
                UPDATE storage_operation SET status = 'WAITING_TARGET', error_code = ?, updated_at = ?
                WHERE id = ? AND task_instance_id IS NULL AND status IN ('CREATED', 'WAITING_TARGET')
                """, ErrorCode.TARGET_CONFLICT.code(), Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 绑定幂等启动的 rclone 后台任务。
     *
     * @param id 操作标识
     * @param remoteJobId rclone 任务标识
     */
    public void bindRemoteJob(UUID id, long remoteJobId) {
        int updated = jdbcTemplate.update("""
                UPDATE storage_operation SET remote_job_id = ?, updated_at = ?
                WHERE id = ? AND status = 'RUNNING' AND remote_job_id IS NULL
                """, remoteJobId, Timestamp.from(Instant.now()), id.toString());
        if (updated != 1) {
            StorageOperation current = findOperationById(id)
                    .orElseThrow(() -> new IllegalArgumentException(ErrorCode.OPERATION_NOT_FOUND.code()));
            if (!java.util.Objects.equals(current.remoteJobId(), remoteJobId)) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
        }
    }

    /**
     * 幂等合并一个扫描批次并刷新准确计数。
     *
     * @param operationId 操作标识
     * @param items 对象批次
     */
    @Transactional
    public void mergeOperationItems(UUID operationId, List<RemoteObjectView> items) {
        Instant now = Instant.now();
        Map<String, Object> operation = jdbcTemplate.queryForMap(
                "SELECT status, maximum_objects FROM storage_operation WHERE id = ?", operationId.toString());
        if (!"RUNNING".equals(operation.get("status"))) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        for (RemoteObjectView item : items) {
            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                    SELECT object_name, directory, size_bytes, modified_at, content_sha256
                    FROM storage_operation_item WHERE operation_id = ? AND object_path = ?
                    """, operationId.toString(), item.path());
            if (!existing.isEmpty()) {
                if (!sameItem(existing.getFirst(), item)) {
                    throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
                }
                continue;
            }
            jdbcTemplate.update("""
                    INSERT INTO storage_operation_item
                        (operation_id, object_path, object_path_sha256, object_name, directory, size_bytes,
                         modified_at, content_sha256, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, operationId.toString(), item.path(), pathSha256(item.path()), item.name(),
                    item.directory(), item.sizeBytes(),
                    item.modifiedAt() == null ? null : Timestamp.from(normalizeInstant(item.modifiedAt())),
                    item.contentSha256(),
                    Timestamp.from(now), Timestamp.from(now));
        }
        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM storage_operation_item WHERE operation_id = ?", Long.class,
                operationId.toString());
        if (count > ((Number) operation.get("maximum_objects")).longValue()) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        jdbcTemplate.update("""
                UPDATE storage_operation SET item_count = ?, updated_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, count, Timestamp.from(now), operationId.toString());
    }

    /**
     * 判断冻结清单中是否存在一个普通文件。
     *
     * @param operationId 父操作标识
     * @param objectPath 对象路径
     * @return 是否存在
     */
    public boolean containsFrozenFile(UUID operationId, String objectPath) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM storage_operation_item
                WHERE operation_id = ? AND object_path = ? AND directory = FALSE
                """, Integer.class, operationId.toString(), objectPath);
        return count != null && count == 1;
    }

    /**
     * 幂等关联原生树复制父子操作。
     *
     * @param parentId 父操作标识
     * @param childId 子操作标识
     * @param sourcePath 来源对象路径
     * @param targetPath 目标对象路径
     */
    public void linkChildOperation(UUID parentId, UUID childId, String sourcePath, String targetPath) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT child_operation_id, target_object_path FROM storage_operation_child
                WHERE parent_operation_id = ? AND source_object_path = ?
                """, parentId.toString(), sourcePath);
        if (!existing.isEmpty()) {
            if (!childId.toString().equals(existing.getFirst().get("child_operation_id"))
                    || !targetPath.equals(existing.getFirst().get("target_object_path"))) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO storage_operation_child
                    (parent_operation_id, child_operation_id, source_object_path, source_object_path_sha256,
                     target_object_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, parentId.toString(), childId.toString(), sourcePath, pathSha256(sourcePath), targetPath,
                Timestamp.from(Instant.now()));
    }

    /**
     * 查询父操作关联的全部子操作。
     *
     * @param parentId 父操作标识
     * @return 子操作列表
     */
    public List<StorageOperation> findChildOperations(UUID parentId) {
        return jdbcTemplate.query("""
                SELECT operation.* FROM storage_operation operation
                JOIN storage_operation_child child ON child.child_operation_id = operation.id
                WHERE child.parent_operation_id = ? ORDER BY child.source_object_path
                """, (resultSet, rowNumber) -> new StorageOperation(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("provider_id")), resultSet.getString("idempotency_key"),
                resultSet.getString("operation_type"), resultSet.getString("source_path"),
                UUID.fromString(resultSet.getString("target_provider_id")), resultSet.getString("target_path"),
                resultSet.getString("status"), resultSet.getString("task_instance_id") == null ? null
                : UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getObject("remote_job_id", Long.class), resultSet.getLong("item_count"),
                resultSet.getInt("maximum_objects"), resultSet.getString("error_code"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant()),
                parentId.toString());
    }

    /**
     * 将异步操作标记为终态，终态不可被后续回调覆盖。
     *
     * @param id 操作标识
     * @param status 终态
     * @param errorCode 错误码
     */
    public void finishOperation(UUID id, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE storage_operation SET status = ?, error_code = ?, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'WAITING_TARGET', 'RUNNING')
                """, status, errorCode, Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 新增上传会话。
     *
     * @param record 上传记录
     */
    public void insertUpload(UploadRecord record) {
        jdbcTemplate.update("""
                INSERT INTO storage_upload
                    (id, root_id, idempotency_key, relative_path, expected_size, expected_sha256,
                     actual_size, actual_sha256, status, temporary_path, final_path, error_code,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.rootId().toString(), record.idempotencyKey(),
                record.relativePath(), record.expectedSize(), record.expectedSha256(), null, null,
                record.status(), null, null, null, Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按幂等键查询上传。
     *
     * @param idempotencyKey 幂等键
     * @return 上传记录
     */
    public Optional<UploadRecord> findByIdempotencyKey(String idempotencyKey) {
        return query("WHERE su.idempotency_key = ?", idempotencyKey);
    }

    /**
     * 按标识查询上传。
     *
     * @param id 上传标识
     * @return 上传记录
     */
    public Optional<UploadRecord> findById(UUID id) {
        return query("WHERE su.id = ?", id.toString());
    }

    /**
     * 竞争上传执行权。
     *
     * @param id 上传标识
     * @param temporaryPath 临时文件路径
     * @return 是否成功获得执行权
     */
    public boolean claim(UUID id, String temporaryPath) {
        return jdbcTemplate.update("""
                UPDATE storage_upload SET status = 'UPLOADING', temporary_path = ?, updated_at = ?
                WHERE id = ? AND status IN ('CREATED', 'FAILED')
                """, temporaryPath, Timestamp.from(Instant.now()), id.toString()) == 1;
    }

    /**
     * 标记上传成功。
     *
     * @param id 上传标识
     * @param size 实际字节数
     * @param sha256 实际摘要
     * @param finalPath 最终路径
     */
    public void succeed(UUID id, long size, String sha256, String finalPath) {
        jdbcTemplate.update("""
                UPDATE storage_upload SET status = 'SUCCEEDED', actual_size = ?, actual_sha256 = ?,
                    final_path = ?, temporary_path = NULL, error_code = NULL, updated_at = ? WHERE id = ?
                """, size, sha256, finalPath, Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 标记上传失败。
     *
     * @param id 上传标识
     * @param errorCode 稳定错误类别
     */
    public void fail(UUID id, String errorCode) {
        jdbcTemplate.update("""
                UPDATE storage_upload SET status = 'FAILED', temporary_path = NULL,
                    error_code = ?, updated_at = ? WHERE id = ?
                """, errorCode, Timestamp.from(Instant.now()), id.toString());
    }

    private Optional<UploadRecord> query(String condition, Object argument) {
        String sql = """
                SELECT su.*, sr.name AS root_name, sr.base_path
                FROM storage_upload su JOIN storage_root sr ON sr.id = su.root_id
                """ + condition;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new UploadRecord(
                UUID.fromString(resultSet.getString("id")), UUID.fromString(resultSet.getString("root_id")),
                resultSet.getString("root_name"), resultSet.getString("base_path"),
                resultSet.getString("idempotency_key"), resultSet.getString("relative_path"),
                resultSet.getLong("expected_size"), resultSet.getString("expected_sha256"),
                resultSet.getObject("actual_size", Long.class), resultSet.getString("actual_sha256"),
                resultSet.getString("status"), resultSet.getString("temporary_path"),
                resultSet.getString("final_path"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), argument).stream().findFirst();
    }

    private Optional<StorageProvider> queryProvider(String condition, Object argument) {
        return jdbcTemplate.query("SELECT * FROM storage_provider WHERE " + condition,
                (resultSet, rowNumber) -> new StorageProvider(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("name"), resultSet.getString("provider_type"),
                        resultSet.getString("remote_key"), resultSet.getString("endpoint_uri"),
                        resultSet.getString("region_name"), resultSet.getString("secret_ref"),
                        resultSet.getBoolean("enabled"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()), argument).stream().findFirst();
    }

    private Optional<StorageOperation> queryOperation(String condition, Object argument) {
        return jdbcTemplate.query("SELECT * FROM storage_operation WHERE " + condition,
                (resultSet, rowNumber) -> new StorageOperation(UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("provider_id")),
                        resultSet.getString("idempotency_key"), resultSet.getString("operation_type"),
                        resultSet.getString("source_path"),
                        resultSet.getString("target_provider_id") == null ? null
                                : UUID.fromString(resultSet.getString("target_provider_id")),
                        resultSet.getString("target_path"), resultSet.getString("status"),
                        resultSet.getString("task_instance_id") == null ? null
                                : UUID.fromString(resultSet.getString("task_instance_id")),
                        resultSet.getObject("remote_job_id", Long.class),
                        resultSet.getLong("item_count"), resultSet.getInt("maximum_objects"),
                        resultSet.getString("error_code"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()), argument).stream().findFirst();
    }

    private boolean sameItem(Map<String, Object> existing, RemoteObjectView item) {
        Timestamp modified = (Timestamp) existing.get("modified_at");
        return item.name().equals(existing.get("object_name"))
                && item.directory() == (Boolean) existing.get("directory")
                && item.sizeBytes() == ((Number) existing.get("size_bytes")).longValue()
                && java.util.Objects.equals(item.modifiedAt() == null ? null : normalizeInstant(item.modifiedAt()),
                modified == null ? null : normalizeInstant(modified.toInstant()))
                && java.util.Objects.equals(item.contentSha256(), existing.get("content_sha256"));
    }

    private Optional<AccessTicketRecord> queryAccessTicket(String condition, Object argument) {
        return jdbcTemplate.query("""
                SELECT sat.*, sr.name AS root_name
                FROM storage_access_ticket sat JOIN storage_root sr ON sr.id = sat.root_id
                WHERE """ + " " + condition, (resultSet, rowNumber) -> new AccessTicketRecord(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("token_sha256"),
                UUID.fromString(resultSet.getString("root_id")), resultSet.getString("root_name"),
                resultSet.getString("relative_path"), resultSet.getString("permission"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("consumed_at") == null ? null
                        : resultSet.getTimestamp("consumed_at").toInstant(),
                resultSet.getTimestamp("revoked_at") == null ? null
                        : resultSet.getTimestamp("revoked_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()), argument).stream().findFirst();
    }

    private Instant normalizeInstant(Instant value) {
        return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String pathSha256(String path) {
        return java.util.HexFormat.of().formatHex(sha256Digest()
                .digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void updateDigest(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    /**
     * 受管根查询结果。
     *
     * @param id 根标识
     * @param name 根名称
     * @param basePath 根路径
     * @param nodeAffinityLabel 节点亲和标签
     * @param nodeAffinityValue 节点亲和值
     */
    public record ManagedRoot(UUID id, String name, String basePath, String nodeAffinityLabel,
                              String nodeAffinityValue) {
    }
}
