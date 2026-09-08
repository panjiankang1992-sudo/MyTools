package com.yuyutian.mytools.reader.repository;

import com.yuyutian.mytools.reader.model.AudiobookExportArchive;
import com.yuyutian.mytools.reader.model.AudiobookExportFormat;
import com.yuyutian.mytools.reader.model.AudiobookExportInput;
import com.yuyutian.mytools.reader.model.AudiobookExportRecord;
import com.yuyutian.mytools.reader.model.AudiobookExportResult;
import com.yuyutian.mytools.reader.model.CreateAudiobookExportRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 有声书整书导出任务及其不可变归档的持久化访问层。
 */
@Repository
public class AudiobookExportRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建有声书导出仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public AudiobookExportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按所有者和幂等键查询导出。
     *
     * @param ownerId 所有者标识
     * @param idempotencyKey 调用方幂等键
     * @return 导出记录
     */
    public Optional<AudiobookExportRecord> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return query("WHERE owner_id = ? AND idempotency_key = ?", ownerId, idempotencyKey);
    }

    /**
     * 查询导出记录。
     *
     * @param id 导出标识
     * @return 导出记录
     */
    public Optional<AudiobookExportRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 创建一条指向已完成 generation 的待导出记录。
     *
     * @param id 导出标识
     * @param ownerId 所有者标识
     * @param generationId 已完成 generation 标识
     * @param chapterCount 已冻结章节数
     * @param request 调用方请求
     * @return 新建记录
     */
    public AudiobookExportRecord create(UUID id, long ownerId, UUID generationId, int chapterCount,
                                        CreateAudiobookExportRequest request) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO audiobook_export
                    (id, owner_id, generation_id, idempotency_key, format, status, current_stage,
                     task_instance_id, archive_storage_uri, archive_content_sha256, archive_size_bytes,
                     chapter_count, error_code, created_at, started_at, finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACCEPTED', 'ACCEPTED', NULL, NULL, NULL, NULL, ?, NULL, ?, NULL, NULL, ?)
                """, id.toString(), ownerId, generationId.toString(), request.idempotencyKey(),
                request.format().name(), chapterCount, Timestamp.from(now), Timestamp.from(now));
        return new AudiobookExportRecord(id, ownerId, generationId, request.idempotencyKey(), request.format(),
                "ACCEPTED", "ACCEPTED", null, chapterCount, null, now, null, null, now);
    }

    /**
     * 判断已有导出是否匹配同一版本和格式。
     *
     * @param exportId 导出标识
     * @param generationId generation 标识
     * @param request 调用方请求
     * @return 是否匹配
     */
    public boolean matches(UUID exportId, UUID generationId, CreateAudiobookExportRequest request) {
        return jdbcTemplate.query("""
                SELECT generation_id, format FROM audiobook_export WHERE id = ?
                """, (resultSet, rowNumber) -> new ExportIdentity(
                resultSet.getString("generation_id"), resultSet.getString("format")), exportId.toString())
                .stream().findFirst().map(identity -> generationId.toString().equals(identity.generationId())
                        && request.format().name().equals(identity.format())).orElse(false);
    }

    /**
     * 绑定已受理的归档任务。
     *
     * @param exportId 导出标识
     * @param taskId 调度任务标识
     */
    public void bindTask(UUID exportId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_export
                SET task_instance_id = ?, status = 'QUEUED', current_stage = 'PACKAGING', error_code = NULL,
                    started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(now), Timestamp.from(now), exportId.toString());
    }

    /**
     * 返回执行器创建 ZIP 所需的只读章节定位清单。
     *
     * @param exportId 导出标识
     * @return 执行输入
     */
    public Optional<AudiobookExportInput> findInput(UUID exportId) {
        return jdbcTemplate.query("""
                SELECT export_job.id, export_job.generation_id, generation.generation_version, export_job.format,
                       export_job.chapter_count
                FROM audiobook_export export_job
                JOIN audiobook_generation generation ON generation.id = export_job.generation_id
                WHERE export_job.id = ? AND export_job.status IN ('ACCEPTED', 'QUEUED')
                  AND generation.status = 'COMPLETED'
                """, (resultSet, rowNumber) -> new ExportInputHeader(
                UUID.fromString(resultSet.getString("id")), UUID.fromString(resultSet.getString("generation_id")),
                resultSet.getInt("generation_version"), AudiobookExportFormat.valueOf(resultSet.getString("format")),
                resultSet.getInt("chapter_count")), exportId.toString()).stream().findFirst().map(header -> {
            List<AudiobookExportInput.Chapter> chapters = jdbcTemplate.query("""
                    SELECT chapter_index, chapter_title, audio_storage_uri, audio_content_sha256,
                           audio_size_bytes, audio_format
                    FROM audiobook_generation_chapter
                    WHERE generation_id = ? AND synthesis_status = 'READY' AND audio_storage_uri IS NOT NULL
                      AND audio_content_sha256 IS NOT NULL AND audio_size_bytes IS NOT NULL AND audio_format IS NOT NULL
                    ORDER BY chapter_index
                    """, (resultSet, rowNumber) -> new AudiobookExportInput.Chapter(
                    resultSet.getInt("chapter_index"), resultSet.getString("chapter_title"),
                    resultSet.getString("audio_storage_uri"), resultSet.getString("audio_content_sha256"),
                    resultSet.getLong("audio_size_bytes"), resultSet.getString("audio_format")),
                    header.generationId().toString());
            if (chapters.size() != header.chapterCount() || chapters.isEmpty()) {
                throw new IllegalStateException("audiobook export source chapters are incomplete");
            }
            return new AudiobookExportInput(header.id(), header.generationId(), header.generationVersion(),
                    header.format(), chapters);
        });
    }

    /**
     * 幂等保存执行器发布的完整归档。
     *
     * @param exportId 导出标识
     * @param result 执行器回写结果
     * @return 更新后的导出记录
     */
    public AudiobookExportRecord complete(UUID exportId, AudiobookExportResult result) {
        ExportCompletionState current = jdbcTemplate.query("""
                SELECT owner_id, generation_id, idempotency_key, format, status, current_stage, task_instance_id,
                       chapter_count, error_code, created_at, started_at, finished_at, updated_at,
                       archive_storage_uri, archive_content_sha256, archive_size_bytes
                FROM audiobook_export WHERE id = ?
                """, (resultSet, rowNumber) -> new ExportCompletionState(
                resultSet.getLong("owner_id"), UUID.fromString(resultSet.getString("generation_id")),
                resultSet.getString("idempotency_key"), AudiobookExportFormat.valueOf(resultSet.getString("format")),
                resultSet.getString("status"), resultSet.getString("current_stage"),
                optionalUuid(resultSet.getString("task_instance_id")), resultSet.getInt("chapter_count"),
                resultSet.getString("error_code"), resultSet.getTimestamp("created_at").toInstant(),
                optionalInstant(resultSet.getTimestamp("started_at")), optionalInstant(resultSet.getTimestamp("finished_at")),
                resultSet.getTimestamp("updated_at").toInstant(), resultSet.getString("archive_storage_uri"),
                resultSet.getString("archive_content_sha256"), optionalLong(resultSet.getObject("archive_size_bytes"))),
                exportId.toString()).stream().findFirst().orElseThrow(() ->
                new IllegalArgumentException("audiobook export does not exist"));
        if ("COMPLETED".equals(current.status())) {
            if (!result.storageUri().equals(current.storageUri())
                    || !result.contentSha256().equals(current.contentSha256())
                    || current.sizeBytes() == null || result.sizeBytes() != current.sizeBytes()
                    || result.chapterCount() != current.chapterCount()) {
                throw new IllegalStateException("audiobook export conflicts with frozen result");
            }
            return current.record(exportId);
        }
        if (!"ACCEPTED".equals(current.status()) && !"QUEUED".equals(current.status())) {
            throw new IllegalStateException("audiobook export is not ready to complete");
        }
        if (result.chapterCount() != current.chapterCount()) {
            throw new IllegalStateException("audiobook export chapter count does not match frozen generation");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_export
                SET status = 'COMPLETED', current_stage = 'COMPLETED', archive_storage_uri = ?,
                    archive_content_sha256 = ?, archive_size_bytes = ?, error_code = NULL, finished_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, result.storageUri(), result.contentSha256(), result.sizeBytes(), Timestamp.from(now),
                Timestamp.from(now), exportId.toString());
        return new AudiobookExportRecord(exportId, current.ownerId(), current.generationId(),
                current.idempotencyKey(), current.format(), "COMPLETED", "COMPLETED", current.taskId(),
                current.chapterCount(), null, current.createdAt(), current.startedAt(), now, now);
    }

    /**
     * 返回属于当前所有者、已完成且可流式读取的归档定位信息。
     *
     * @param exportId 导出标识
     * @param ownerId 所有者标识
     * @return 归档定位信息
     */
    public Optional<AudiobookExportArchive> findCompletedArchive(UUID exportId, long ownerId) {
        return jdbcTemplate.query("""
                SELECT generation_id, archive_storage_uri, archive_size_bytes
                FROM audiobook_export
                WHERE id = ? AND owner_id = ? AND status = 'COMPLETED' AND archive_storage_uri IS NOT NULL
                  AND archive_size_bytes IS NOT NULL
                """, (resultSet, rowNumber) -> new AudiobookExportArchive(
                resultSet.getString("archive_storage_uri"), resultSet.getLong("archive_size_bytes"),
                "audiobook-" + resultSet.getString("generation_id") + ".zip"), exportId.toString(), ownerId)
                .stream().findFirst();
    }

    /**
     * 将调度器失败终态写回导出任务。
     *
     * @param exportId 导出标识
     * @param status 调度器终态
     * @param errorCode 稳定错误码
     */
    public void updateTerminalTaskStatus(UUID exportId, String status, String errorCode) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_export
                SET status = ?, current_stage = ?, error_code = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status <> 'COMPLETED'
                """, status, status, errorCode, Timestamp.from(now), Timestamp.from(now), exportId.toString());
    }

    private Optional<AudiobookExportRecord> query(String where, Object... values) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, generation_id, idempotency_key, format, status, current_stage,
                       task_instance_id, chapter_count, error_code, created_at, started_at, finished_at, updated_at
                FROM audiobook_export
                """ + where, (resultSet, rowNumber) -> new AudiobookExportRecord(
                UUID.fromString(resultSet.getString("id")), resultSet.getLong("owner_id"),
                UUID.fromString(resultSet.getString("generation_id")), resultSet.getString("idempotency_key"),
                AudiobookExportFormat.valueOf(resultSet.getString("format")), resultSet.getString("status"),
                resultSet.getString("current_stage"), optionalUuid(resultSet.getString("task_instance_id")),
                resultSet.getInt("chapter_count"), resultSet.getString("error_code"),
                resultSet.getTimestamp("created_at").toInstant(), optionalInstant(resultSet.getTimestamp("started_at")),
                optionalInstant(resultSet.getTimestamp("finished_at")), resultSet.getTimestamp("updated_at").toInstant()),
                values).stream().findFirst();
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant optionalInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long optionalLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record ExportIdentity(String generationId, String format) {
    }

    private record ExportInputHeader(UUID id, UUID generationId, int generationVersion, AudiobookExportFormat format,
                                     int chapterCount) {
    }

    private record ExportCompletionState(long ownerId, UUID generationId, String idempotencyKey,
                                         AudiobookExportFormat format, String status, String currentStage,
                                         UUID taskId, int chapterCount, String errorCode, Instant createdAt,
                                         Instant startedAt, Instant finishedAt, Instant updatedAt, String storageUri,
                                         String contentSha256, Long sizeBytes) {
        private AudiobookExportRecord record(UUID id) {
            return new AudiobookExportRecord(id, ownerId, generationId, idempotencyKey, format, status,
                    currentStage, taskId, chapterCount, errorCode, createdAt, startedAt, finishedAt, updatedAt);
        }
    }
}
