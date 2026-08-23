package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.MarkerStateRequest;
import com.yuyutian.mytools.reader.model.MarkerStateView;
import com.yuyutian.mytools.reader.model.ProgressStateRequest;
import com.yuyutian.mytools.reader.model.ProgressStateView;
import com.yuyutian.mytools.reader.model.ShelfStateRequest;
import com.yuyutian.mytools.reader.model.ShelfStateView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 书架、阅读进度和阅读标记的同步数据业务服务。
 */
@Service
public class ReaderStateService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Reader 同步状态服务。
     */
    public ReaderStateService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询一个用户的书架及可选墓碑。
     */
    public List<ShelfStateView> shelves(long ownerId, boolean includeDeleted) {
        return jdbcTemplate.query("""
                SELECT * FROM shelf_book WHERE owner_id = ? AND (? = TRUE OR deleted = FALSE)
                ORDER BY updated_at, id LIMIT 5000
                """, (resultSet, rowNumber) -> new ShelfStateView(UUID.fromString(resultSet.getString("id")),
                resultSet.getLong("owner_id"), resultSet.getString("book_key"),
                json(resultSet.getString("metadata_json")), resultSet.getBoolean("deleted"),
                resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), ownerId, includeDeleted);
    }

    /**
     * 创建或按版本更新书架状态。
     */
    @Transactional
    public ShelfStateView saveShelf(ShelfStateRequest request) {
        Instant now = Instant.now();
        if (request.expectedVersion() == null) {
            UUID id = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                        INSERT INTO shelf_book
                            (id, owner_id, book_key, metadata_json, deleted, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                        """, id.toString(), request.ownerId(), request.bookKey(), json(request.metadata()),
                        request.deleted(), Timestamp.from(now), Timestamp.from(now));
            } catch (DuplicateKeyException exception) {
                throw new ReaderStateConflictException();
            }
            return shelf(request.ownerId(), request.bookKey());
        }
        int changed = jdbcTemplate.update("""
                UPDATE shelf_book SET metadata_json = ?, deleted = ?, version = version + 1, updated_at = ?
                WHERE owner_id = ? AND book_key = ? AND version = ?
                """, json(request.metadata()), request.deleted(), Timestamp.from(now), request.ownerId(),
                request.bookKey(), request.expectedVersion());
        if (changed != 1) {
            requireShelf(request.ownerId(), request.bookKey());
            throw new ReaderStateConflictException();
        }
        return shelf(request.ownerId(), request.bookKey());
    }

    /**
     * 查询一个用户的阅读进度及可选墓碑。
     */
    public List<ProgressStateView> progress(long ownerId, boolean includeDeleted) {
        return jdbcTemplate.query("""
                SELECT rp.*, sb.owner_id, sb.book_key FROM reading_progress rp JOIN shelf_book sb
                  ON sb.id = rp.shelf_book_id
                WHERE sb.owner_id = ? AND (? = TRUE OR rp.deleted = FALSE)
                ORDER BY rp.updated_at, rp.shelf_book_id LIMIT 5000
                """, (resultSet, rowNumber) -> new ProgressStateView(
                UUID.fromString(resultSet.getString("shelf_book_id")), resultSet.getLong("owner_id"),
                resultSet.getString("book_key"), resultSet.getInt("chapter_index"),
                resultSet.getString("chapter_url"), json(resultSet.getString("position_json")),
                resultSet.getBoolean("deleted"), resultSet.getLong("version"),
                resultSet.getTimestamp("updated_at").toInstant()), ownerId, includeDeleted);
    }

    /**
     * 创建或按版本更新阅读进度。
     */
    @Transactional
    public ProgressStateView saveProgress(ProgressStateRequest request) {
        UUID shelfId = requireShelf(request.ownerId(), request.bookKey());
        Instant now = Instant.now();
        if (request.expectedVersion() == null) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO reading_progress
                            (shelf_book_id, chapter_index, chapter_url, position_json, deleted, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?)
                        """, shelfId.toString(), request.chapterIndex(), request.chapterUrl(),
                        json(request.position()), request.deleted(), Timestamp.from(now));
            } catch (DuplicateKeyException exception) {
                throw new ReaderStateConflictException();
            }
        } else {
            int changed = jdbcTemplate.update("""
                    UPDATE reading_progress SET chapter_index = ?, chapter_url = ?, position_json = ?,
                        deleted = ?, version = version + 1, updated_at = ?
                    WHERE shelf_book_id = ? AND version = ?
                    """, request.chapterIndex(), request.chapterUrl(), json(request.position()), request.deleted(),
                    Timestamp.from(now), shelfId.toString(), request.expectedVersion());
            if (changed != 1) {
                requireProgress(shelfId);
                throw new ReaderStateConflictException();
            }
        }
        return progress(shelfId);
    }

    /**
     * 查询一个用户的阅读标记及可选墓碑。
     */
    public List<MarkerStateView> markers(long ownerId, boolean includeDeleted) {
        return jdbcTemplate.query("""
                SELECT rm.*, sb.owner_id, sb.book_key FROM reader_marker rm JOIN shelf_book sb
                  ON sb.id = rm.shelf_book_id
                WHERE sb.owner_id = ? AND (? = TRUE OR rm.deleted = FALSE)
                ORDER BY rm.updated_at, rm.id LIMIT 10000
                """, (resultSet, rowNumber) -> marker(resultSet), ownerId, includeDeleted);
    }

    /**
     * 创建或按版本更新阅读标记。
     */
    @Transactional
    public MarkerStateView saveMarker(MarkerStateRequest request) {
        UUID shelfId = requireShelf(request.ownerId(), request.bookKey());
        Instant now = Instant.now();
        if (request.expectedVersion() == null) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO reader_marker
                            (id, shelf_book_id, marker_type, chapter_index, position_json, note_text,
                             deleted, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                        """, request.markerId().toString(), shelfId.toString(), request.markerType(),
                        request.chapterIndex(), json(request.position()), request.note(), request.deleted(),
                        Timestamp.from(now), Timestamp.from(now));
            } catch (DuplicateKeyException exception) {
                throw new ReaderStateConflictException();
            }
        } else {
            int changed = jdbcTemplate.update("""
                    UPDATE reader_marker SET marker_type = ?, chapter_index = ?, position_json = ?,
                        note_text = ?, deleted = ?, version = version + 1, updated_at = ?
                    WHERE id = ? AND shelf_book_id = ? AND version = ?
                    """, request.markerType(), request.chapterIndex(), json(request.position()), request.note(),
                    request.deleted(), Timestamp.from(now), request.markerId().toString(), shelfId.toString(),
                    request.expectedVersion());
            if (changed != 1) {
                requireMarker(request.markerId(), shelfId);
                throw new ReaderStateConflictException();
            }
        }
        return marker(request.markerId(), shelfId);
    }

    private ShelfStateView shelf(long ownerId, String bookKey) {
        return jdbcTemplate.query("""
                SELECT * FROM shelf_book WHERE owner_id = ? AND book_key = ?
                """, (resultSet, rowNumber) -> new ShelfStateView(UUID.fromString(resultSet.getString("id")),
                resultSet.getLong("owner_id"), resultSet.getString("book_key"),
                json(resultSet.getString("metadata_json")), resultSet.getBoolean("deleted"),
                resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), ownerId, bookKey).stream().findFirst()
                .orElseThrow(ReaderStateNotFoundException::new);
    }

    private UUID requireShelf(long ownerId, String bookKey) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM shelf_book WHERE owner_id = ? AND book_key = ?", String.class, ownerId, bookKey);
        if (ids.size() != 1) {
            throw new ReaderStateNotFoundException();
        }
        return UUID.fromString(ids.getFirst());
    }

    private ProgressStateView progress(UUID shelfId) {
        return jdbcTemplate.query("""
                SELECT rp.*, sb.owner_id, sb.book_key FROM reading_progress rp JOIN shelf_book sb
                  ON sb.id = rp.shelf_book_id WHERE rp.shelf_book_id = ?
                """, (resultSet, rowNumber) -> new ProgressStateView(shelfId, resultSet.getLong("owner_id"),
                resultSet.getString("book_key"), resultSet.getInt("chapter_index"),
                resultSet.getString("chapter_url"), json(resultSet.getString("position_json")),
                resultSet.getBoolean("deleted"), resultSet.getLong("version"),
                resultSet.getTimestamp("updated_at").toInstant()), shelfId.toString()).stream().findFirst()
                .orElseThrow(ReaderStateNotFoundException::new);
    }

    private void requireProgress(UUID shelfId) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reading_progress WHERE shelf_book_id = ?",
                Integer.class, shelfId.toString()) == 0) {
            throw new ReaderStateNotFoundException();
        }
    }

    private MarkerStateView marker(UUID markerId, UUID shelfId) {
        return jdbcTemplate.query("""
                SELECT rm.*, sb.owner_id, sb.book_key FROM reader_marker rm JOIN shelf_book sb
                  ON sb.id = rm.shelf_book_id WHERE rm.id = ? AND rm.shelf_book_id = ?
                """, (resultSet, rowNumber) -> marker(resultSet), markerId.toString(), shelfId.toString()).stream()
                .findFirst().orElseThrow(ReaderStateNotFoundException::new);
    }

    private MarkerStateView marker(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new MarkerStateView(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("shelf_book_id")), resultSet.getLong("owner_id"),
                resultSet.getString("book_key"), resultSet.getString("marker_type"),
                resultSet.getInt("chapter_index"), json(resultSet.getString("position_json")),
                resultSet.getString("note_text"), resultSet.getBoolean("deleted"), resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }

    private void requireMarker(UUID markerId, UUID shelfId) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader_marker WHERE id = ? AND shelf_book_id = ?",
                Integer.class, markerId.toString(), shelfId.toString()) == 0) {
            throw new ReaderStateNotFoundException();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Reader state JSON is invalid", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            while (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored Reader state JSON is invalid", exception);
        }
    }
}
