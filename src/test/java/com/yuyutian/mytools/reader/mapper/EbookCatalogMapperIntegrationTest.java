package com.yuyutian.mytools.reader.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 电子书目录动态SQL真实执行测试。
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:h2:mem:ebook_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
class EbookCatalogMapperIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EbookCatalogMapper mapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ebook_metadata");
        jdbcTemplate.execute("DROP TABLE IF EXISTS local_file");
        jdbcTemplate.execute("CREATE TABLE local_file (id BIGINT PRIMARY KEY, filename VARCHAR(255), "
                + "file_path VARCHAR(1024), file_size BIGINT, extension VARCHAR(50), file_hash VARCHAR(64), "
                + "deleted INT, adult_status INT, adult_content INT, adult_confidence DOUBLE, "
                + "update_time TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ebook_metadata (local_file_id BIGINT PRIMARY KEY, file_hash VARCHAR(64), "
                + "metadata_version INT, status VARCHAR(20), retry_after TIMESTAMP, cover_path VARCHAR(1024))");
        jdbcTemplate.update("INSERT INTO local_file (id, filename, file_path, file_size, extension, file_hash, "
                + "deleted, adult_status, adult_content, update_time) VALUES (1, 'book.txt', "
                + "'/opt/extend/resource/ebook/book.txt', 10, 'txt', 'new-hash', 0, 0, 0, CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO ebook_metadata (local_file_id, file_hash, metadata_version, status) "
                + "VALUES (1, 'old-hash', 1, 'READY')");
    }

    /**
     * 验证版本或哈希变化条件不会把XML实体文本发送给数据库。
     */
    @Test
    void shouldExecuteCandidateVersionAndHashPredicate() {
        assertEquals(1, mapper.selectIndexCandidates("/opt/extend/resource/ebook", 2, 10).size());
        assertEquals(1, mapper.countIndexCandidates("/opt/extend/resource/ebook", 2));
    }
}
