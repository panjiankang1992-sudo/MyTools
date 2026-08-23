package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.UploadRecord;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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
     */
    public void ensureRoot(String name, String purpose, String basePath) {
        List<Map<String, Object>> roots = jdbcTemplate.queryForList("SELECT id FROM storage_root WHERE name = ?", name);
        Instant now = Instant.now();
        if (roots.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO storage_root (id, name, purpose, base_path, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, TRUE, ?, ?)
                    """, UUID.randomUUID().toString(), name, purpose, basePath,
                    Timestamp.from(now), Timestamp.from(now));
            return;
        }
        jdbcTemplate.update("""
                UPDATE storage_root SET purpose = ?, base_path = ?, enabled = TRUE, updated_at = ? WHERE name = ?
                """, purpose, basePath, Timestamp.from(now), name);
    }

    /**
     * 查询启用的受管根。
     *
     * @param name 根名称
     * @return 根标识和路径
     */
    public Optional<ManagedRoot> findRoot(String name) {
        return jdbcTemplate.query("SELECT id, name, base_path FROM storage_root WHERE name = ? AND enabled = TRUE",
                (resultSet, rowNumber) -> new ManagedRoot(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("name"), resultSet.getString("base_path")), name)
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
                    (id, name, provider_type, remote_key, secret_ref, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, provider.id().toString(), provider.name(), provider.providerType(), provider.remoteKey(),
                provider.secretRef(), provider.enabled(), Timestamp.from(provider.createdAt()),
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
                        resultSet.getString("remote_key"), resultSet.getString("secret_ref"),
                        resultSet.getBoolean("enabled"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()), argument).stream().findFirst();
    }

    /**
     * 受管根查询结果。
     *
     * @param id 根标识
     * @param name 根名称
     * @param basePath 根路径
     */
    public record ManagedRoot(UUID id, String name, String basePath) {
    }
}
