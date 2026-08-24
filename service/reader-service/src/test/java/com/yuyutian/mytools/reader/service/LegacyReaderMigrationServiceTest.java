package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.LegacyReaderMigrationBatch;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reader_legacy_migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class LegacyReaderMigrationServiceTest {

    @Autowired
    private LegacyReaderMigrationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldDryRunThenIdempotentlyMigrateUserReaderState() {
        long updatedAt = 1_800_000_000_000L;
        var shelf = new LegacyReaderMigrationItem("SHELF", 71L, "sync-a", "book-a",
                Map.of("name", "Book", "deleted", false, "revision", 3), false, 3, updatedAt);
        var progress = new LegacyReaderMigrationItem("PROGRESS", 71L, "book-a", "book-a",
                Map.of("chapterTitle", "Chapter 2", "locator", 18, "percentage", 42,
                        "deleted", false, "revision", 4), false, 4, updatedAt);
        var marker = new LegacyReaderMigrationItem("MARKER", 71L, "marker-a", "book-a",
                Map.of("kind", "BOOKMARK", "chapterTitle", "Chapter 2", "locator", 20,
                        "note", "note", "createdAt", updatedAt - 1000, "deleted", false, "revision", 2),
                false, 2, updatedAt);
        var batch = new LegacyReaderMigrationBatch("reader-migration-v1", false,
                List.of(shelf, progress, marker));

        var dryRun = service.migrate(new LegacyReaderMigrationBatch("reader-migration-v1", true, batch.items()));
        assertThat(dryRun.accepted()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shelf_book WHERE owner_id=71",
                Integer.class)).isZero();

        var migrated = service.migrate(batch);
        var replay = service.migrate(batch);

        assertThat(migrated.accepted()).isEqualTo(3);
        assertThat(migrated.rejected()).isZero();
        assertThat(replay.skipped()).isEqualTo(3);
        assertThat(replay.digestSha256()).isEqualTo(migrated.digestSha256());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shelf_book WHERE owner_id=71",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT metadata_json FROM shelf_book WHERE owner_id=71",
                String.class))
                .contains("legacyBookId", "book-a");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reading_progress p JOIN shelf_book s "
                + "ON s.id=p.shelf_book_id WHERE s.owner_id=71", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader_marker m JOIN shelf_book s "
                + "ON s.id=m.shelf_book_id WHERE s.owner_id=71", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_reader_migration_item WHERE owner_id=71",
                Integer.class)).isEqualTo(3);
        var evidence = service.evidence("reader-migration-v1");
        assertThat(evidence.itemCount()).isEqualTo(3);
        assertThat(evidence.digestSha256()).hasSize(64);
    }

    @Test
    void shouldPreserveOrphanProgressWithDeterministicPlaceholderShelf() {
        long updatedAt = 1_800_000_000_000L;
        var progress = new LegacyReaderMigrationItem("PROGRESS", 72L, "missing", "missing",
                Map.of("deleted", false), false, 1, updatedAt);

        var result = service.migrate(new LegacyReaderMigrationBatch("reader-orphan-v1", false,
                List.of(progress)));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reading_progress p JOIN shelf_book s "
                + "ON s.id=p.shelf_book_id WHERE s.owner_id=72", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shelf_book WHERE owner_id=72",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT metadata_json FROM shelf_book WHERE owner_id=72",
                String.class))
                .contains("legacyPlaceholder", "missing");

        var replay = service.migrate(new LegacyReaderMigrationBatch("reader-orphan-v1", false,
                List.of(progress)));
        assertThat(replay.skipped()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shelf_book WHERE owner_id=72",
                Integer.class)).isOne();
    }
}
