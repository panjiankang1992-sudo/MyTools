package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F07：**发布必须确认教材行真的被更新了**。
 *
 * 审查原话："mapper CAS 结果被忽略"。
 *
 * `publishVersion` 里这一句的返回值此前没有被检查：
 *
 * ```java
 * jdbc.update("UPDATE toolbox_textbook SET current_version_id = ? WHERE edition_id = ?", ...);
 * ```
 *
 * 如果 `toolbox_textbook` 里**没有这一册的行**，更新影响 0 行，
 * **而发布照样"成功"返回**：调用方以为发布完成，公开目录却什么都没变。
 * 界面会显示"已发布"，用户刷新却看不到内容——**一个不会报错的失败**。
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishRequiresTextbookRowTest {

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("教材行不存在时发布必须失败，而不是静默成功")
    void publishFailsWhenTextbookRowIsMissing() {
        // 正常建一册，然后把**教材行**删掉——模拟"版本能写，但目录挂不上去"的中间状态。
        AdminTextbookRepository.DraftRef draft =
                repository.createTextbook("orphan-publish-book", "{\"subject\":\"chinese\"}");
        jdbc.update("DELETE FROM toolbox_textbook WHERE edition_id = ?", "orphan-publish-book");
        // 草稿仍在（版本正文还能写），但目录行没了。
        assertThat(repository.findDraft(draft.draftId())).isPresent();

        byte[] body = "{\"edition\":{\"editionId\":\"orphan-publish-book\"},\"units\":[],\"entries\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);

        assertThatThrownBy(() -> repository.publishVersion("orphan-publish-book", "1.0.0", body, sha,
                "{\"subject\":\"chinese\"}", "tester"))
                .as("教材行不存在时不能假装发布成功；否则界面显示已发布、公开目录却什么都没变")
                .isInstanceOf(RuntimeException.class);
    }

    private static String sha256(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
