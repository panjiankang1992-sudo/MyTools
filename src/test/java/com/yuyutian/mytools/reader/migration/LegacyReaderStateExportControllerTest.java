package com.yuyutian.mytools.reader.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyReaderStateExportControllerTest {

    private LegacyReaderStateExportController controller;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy_reader_export;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE t_shelf_book (
                    user_id BIGINT NOT NULL, sync_key VARCHAR(80) NOT NULL, book_id VARCHAR(1000) NOT NULL,
                    name VARCHAR(300) NOT NULL, author VARCHAR(200) NOT NULL, origin VARCHAR(20) NOT NULL,
                    format VARCHAR(20) NOT NULL, resource_uri VARCHAR(4096) NOT NULL,
                    source_id VARCHAR(4096) NOT NULL, remote_cover_url VARCHAR(4096) NOT NULL,
                    client_updated_at BIGINT NOT NULL, server_updated_at BIGINT NOT NULL,
                    deleted BOOLEAN NOT NULL, revision BIGINT NOT NULL, PRIMARY KEY (user_id, sync_key))
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_reading_progress (
                    user_id BIGINT NOT NULL, book_id VARCHAR(1000) NOT NULL, chapter_title VARCHAR(500) NOT NULL,
                    locator BIGINT NOT NULL, percentage INT NOT NULL, client_updated_at BIGINT NOT NULL,
                    server_updated_at BIGINT NOT NULL, deleted BOOLEAN NOT NULL, revision BIGINT NOT NULL,
                    PRIMARY KEY (user_id, book_id))
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_reader_marker (
                    user_id BIGINT NOT NULL, marker_id VARCHAR(1000) NOT NULL, kind VARCHAR(20) NOT NULL,
                    book_id VARCHAR(1000) NOT NULL, chapter_title VARCHAR(500) NOT NULL, locator BIGINT NOT NULL,
                    note VARCHAR(2000) NOT NULL, created_at BIGINT NOT NULL, client_updated_at BIGINT NOT NULL,
                    server_updated_at BIGINT NOT NULL, deleted BOOLEAN NOT NULL, revision BIGINT NOT NULL,
                    PRIMARY KEY (user_id, marker_id))
                """);
        controller = new LegacyReaderStateExportController(jdbcTemplate, "reader-token");
    }

    @Test
    void shouldExportShelfBeforeDependentStateUsingStableCursor() {
        jdbcTemplate.update("""
                INSERT INTO t_shelf_book
                    (user_id, sync_key, book_id, name, author, origin, format, resource_uri, source_id,
                     remote_cover_url, client_updated_at, server_updated_at, deleted, revision)
                VALUES (7, 'a', 'book-a', 'A', 'Author', 'LOCAL', 'EPUB', 'storage://a', 'source-a',
                        '', 100, 200, FALSE, 3),
                       (7, 'b', 'book-b', 'B', 'Author', 'LOCAL', 'EPUB', 'storage://b', 'source-b',
                        '', 101, 201, TRUE, 4)
                """);
        jdbcTemplate.update("""
                INSERT INTO t_reading_progress
                    (user_id, book_id, chapter_title, locator, percentage, client_updated_at,
                     server_updated_at, deleted, revision)
                VALUES (7, 'book-a', 'Chapter', 10, 20, 100, 200, FALSE, 2)
                """);

        var first = controller.export("Bearer reader-token", "shelf", 0, "", 1);
        var second = controller.export("Bearer reader-token", "SHELF", first.nextAfterOwnerId(),
                first.nextAfterKey(), 1);
        var progress = controller.export("Bearer reader-token", "PROGRESS", 0, "", 100);

        assertThat(first.complete()).isFalse();
        assertThat(first.items()).extracting("legacyKey").containsExactly("a");
        assertThat(second.items()).extracting("legacyKey").containsExactly("b");
        assertThat(second.items().getFirst().payload()).containsEntry("deleted", true);
        assertThat(progress.items().getFirst().bookId()).isEqualTo("book-a");
        assertThat(progress.items().getFirst().payload()).containsEntry("percentage", 20);
    }

    @Test
    void shouldRejectMissingMigrationToken() {
        assertThatThrownBy(() -> controller.export(null, "SHELF", 0, "", 100))
                .isInstanceOf(SecurityException.class);
    }
}
