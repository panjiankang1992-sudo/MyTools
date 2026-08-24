package com.yuyutian.mytools.reader.migration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 旧书架、阅读进度和书签的只读迁移接口。
 */
@RestController
@RequestMapping("/internal/v1/migration/reader-state")
public class LegacyReaderStateExportController {

    private final JdbcTemplate jdbcTemplate;
    private final String token;

    /**
     * 创建旧 Reader 用户状态导出接口。
     */
    public LegacyReaderStateExportController(JdbcTemplate jdbcTemplate,
                                             @Value("${migration.reader-state.internal-token:}") String token) {
        this.jdbcTemplate = jdbcTemplate;
        this.token = token;
    }

    /**
     * 使用复合游标分页导出一种用户 Reader 状态。
     */
    @GetMapping
    public ExportPage export(@RequestHeader(name = "Authorization", required = false) String authorization,
                             @RequestParam String type,
                             @RequestParam(defaultValue = "0") long afterOwnerId,
                             @RequestParam(defaultValue = "") String afterKey,
                             @RequestParam(required = false) Long snapshotOwnerId,
                             @RequestParam(required = false) String snapshotKey,
                             @RequestParam(defaultValue = "100") int limit) {
        authorize(authorization);
        if (afterOwnerId < 0 || afterKey.length() > 1000 || limit < 1 || limit > 500
                || (snapshotOwnerId == null) != (snapshotKey == null)) {
            throw new IllegalArgumentException("Reader migration page is invalid");
        }
        String normalized = type.toUpperCase(Locale.ROOT);
        SnapshotHighWater highWater = snapshotOwnerId == null
                ? highWater(normalized) : new SnapshotHighWater(snapshotOwnerId, snapshotKey);
        if (highWater.ownerId() < 0 || highWater.key().length() > 1000
                || compare(afterOwnerId, afterKey, highWater.ownerId(), highWater.key()) > 0) {
            throw new IllegalArgumentException("Reader migration snapshot is invalid");
        }
        List<ExportItem> items = switch (normalized) {
            case "SHELF" -> shelves(afterOwnerId, afterKey, highWater, limit);
            case "PROGRESS" -> progress(afterOwnerId, afterKey, highWater, limit);
            case "MARKER" -> markers(afterOwnerId, afterKey, highWater, limit);
            default -> throw new IllegalArgumentException("Reader migration type is invalid");
        };
        ExportItem last = items.isEmpty() ? null : items.getLast();
        return new ExportPage(items, last == null ? afterOwnerId : last.ownerId(),
                last == null ? afterKey : last.legacyKey(), items.size() < limit
                || compare(last.ownerId(), last.legacyKey(), highWater.ownerId(), highWater.key()) >= 0,
                highWater.ownerId(), highWater.key());
    }

    private List<ExportItem> shelves(long ownerId, String key, SnapshotHighWater highWater, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM t_shelf_book
                WHERE (user_id > ? OR (user_id = ? AND sync_key > ?))
                  AND (user_id < ? OR (user_id = ? AND sync_key <= ?))
                ORDER BY user_id, sync_key LIMIT ?
                """, (resultSet, rowNumber) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", resultSet.getString("name"));
            payload.put("author", resultSet.getString("author"));
            payload.put("origin", resultSet.getString("origin"));
            payload.put("format", resultSet.getString("format"));
            payload.put("resourceUri", resultSet.getString("resource_uri"));
            payload.put("sourceId", resultSet.getString("source_id"));
            payload.put("remoteCoverUrl", resultSet.getString("remote_cover_url"));
            payload.put("clientUpdatedAt", resultSet.getLong("client_updated_at"));
            payload.put("deleted", resultSet.getBoolean("deleted"));
            payload.put("revision", resultSet.getLong("revision"));
            return item("SHELF", resultSet.getLong("user_id"), resultSet.getString("sync_key"),
                    resultSet.getString("book_id"), payload, resultSet.getBoolean("deleted"),
                    resultSet.getLong("revision"), resultSet.getLong("server_updated_at"));
        }, ownerId, ownerId, key, highWater.ownerId(), highWater.ownerId(), highWater.key(), limit);
    }

    private List<ExportItem> progress(long ownerId, String key, SnapshotHighWater highWater, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM t_reading_progress
                WHERE (user_id > ? OR (user_id = ? AND book_id > ?))
                  AND (user_id < ? OR (user_id = ? AND book_id <= ?))
                ORDER BY user_id, book_id LIMIT ?
                """, (resultSet, rowNumber) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chapterTitle", resultSet.getString("chapter_title"));
            payload.put("locator", resultSet.getLong("locator"));
            payload.put("percentage", resultSet.getInt("percentage"));
            payload.put("clientUpdatedAt", resultSet.getLong("client_updated_at"));
            payload.put("deleted", resultSet.getBoolean("deleted"));
            payload.put("revision", resultSet.getLong("revision"));
            String bookId = resultSet.getString("book_id");
            return item("PROGRESS", resultSet.getLong("user_id"), bookId, bookId, payload,
                    resultSet.getBoolean("deleted"), resultSet.getLong("revision"),
                    resultSet.getLong("server_updated_at"));
        }, ownerId, ownerId, key, highWater.ownerId(), highWater.ownerId(), highWater.key(), limit);
    }

    private List<ExportItem> markers(long ownerId, String key, SnapshotHighWater highWater, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM t_reader_marker
                WHERE (user_id > ? OR (user_id = ? AND marker_id > ?))
                  AND (user_id < ? OR (user_id = ? AND marker_id <= ?))
                ORDER BY user_id, marker_id LIMIT ?
                """, (resultSet, rowNumber) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", resultSet.getString("kind"));
            payload.put("chapterTitle", resultSet.getString("chapter_title"));
            payload.put("locator", resultSet.getLong("locator"));
            payload.put("note", resultSet.getString("note"));
            payload.put("createdAt", resultSet.getLong("created_at"));
            payload.put("clientUpdatedAt", resultSet.getLong("client_updated_at"));
            payload.put("deleted", resultSet.getBoolean("deleted"));
            payload.put("revision", resultSet.getLong("revision"));
            return item("MARKER", resultSet.getLong("user_id"), resultSet.getString("marker_id"),
                    resultSet.getString("book_id"), payload, resultSet.getBoolean("deleted"),
                    resultSet.getLong("revision"), resultSet.getLong("server_updated_at"));
        }, ownerId, ownerId, key, highWater.ownerId(), highWater.ownerId(), highWater.key(), limit);
    }

    private SnapshotHighWater highWater(String type) {
        String sql = switch (type) {
            case "SHELF" -> "SELECT user_id, sync_key FROM t_shelf_book ORDER BY user_id DESC, sync_key DESC LIMIT 1";
            case "PROGRESS" -> "SELECT user_id, book_id FROM t_reading_progress ORDER BY user_id DESC, book_id DESC LIMIT 1";
            case "MARKER" -> "SELECT user_id, marker_id FROM t_reader_marker ORDER BY user_id DESC, marker_id DESC LIMIT 1";
            default -> throw new IllegalArgumentException("Reader migration type is invalid");
        };
        List<SnapshotHighWater> values = jdbcTemplate.query(sql, (resultSet, rowNumber) ->
                new SnapshotHighWater(resultSet.getLong(1), resultSet.getString(2)));
        return values.isEmpty() ? new SnapshotHighWater(0, "") : values.getFirst();
    }

    private int compare(long leftOwner, String leftKey, long rightOwner, String rightKey) {
        int owner = Long.compare(leftOwner, rightOwner);
        return owner == 0 ? leftKey.compareTo(rightKey) : owner;
    }

    private ExportItem item(String type, long ownerId, String key, String bookId, Map<String, Object> payload,
                            boolean deleted, long revision, long updatedAt) {
        return new ExportItem(type, ownerId, key, bookId, payload, deleted, revision, updatedAt);
    }

    private void authorize(String authorization) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (token.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("Reader migration authorization failed");
        }
    }

    /**
     * 旧 Reader 用户状态迁移条目。
     */
    public record ExportItem(String entityType, long ownerId, String legacyKey, String bookId,
                             Map<String, Object> payload, boolean deleted, long revision, long serverUpdatedAt) {
    }

    /**
     * 旧 Reader 用户状态迁移页面。
     */
    public record ExportPage(List<ExportItem> items, long nextAfterOwnerId, String nextAfterKey,
                             boolean complete, long snapshotOwnerId, String snapshotKey) {
    }

    private record SnapshotHighWater(long ownerId, String key) {
    }
}
