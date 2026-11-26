package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F07：**启用 CAS 时，发布必须发现"当前版本已被别的实例改过"**。
 *
 * 服务的按册锁**只在本进程内**有效。多实例下：
 *
 * ```
 * 实例 A 读当前 = v1 → 实例 B 读当前 = v1 → A 写 v2 → B 写 v3（覆盖 A）
 * ```
 *
 * **结果**：A 的发布"成功了"，但公开目录指向 B 的版本——**A 的发布被静默抹掉**。
 *
 * 本类直接用数据层写入**故意非法**的版本行（元数据 `{}`），因此使用独立内存库：
 * 这类行留在共享库里会让数据库来源的上下文在启动重建快照时**按设计拒绝启动**。
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishCurrentVersionCasTest {

    /**
     * 独立内存库：本类写入的版本行是"故意非法"的数据层夹具。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:publish-cas;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AdminTextbookRepository repository;

    private static byte[] body(String marker) {
        return ("{\"edition\":{\"editionId\":\"cas-book\"},\"marker\":\"" + marker + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    @DisplayName("启用 CAS 时期望值过期必须失败；期望值正确必须成功")
    void casDetectsConcurrentPublish() {
        repository.createTextbook("cas-book", "{\"subject\":\"chinese\"}");

        byte[] first = body("first");
        // 首次发布：当前为 null（尚未发布过），启用 CAS 并期望 null。
        repository.publishVersion("cas-book", "1.0.0", first, sha(first), "{}", "tester", null, true);

        byte[] second = body("second");
        assertThatThrownBy(() -> repository.publishVersion("cas-book", "1.0.1", second, sha(second),
                "{}", "tester", "someone-elses-version", true))
                .as("当前版本已被改动时必须失败；否则先发布的那个实例会被静默覆盖")
                .isInstanceOf(IllegalStateException.class);

        // 门槛不能变成"永远发不出去"：拿**正确**的期望值应当成功。
        // 上面那次失败没有改动当前版本，所以此刻它仍是 1.0.0 对应的标识。
        assertThat(repository.listAllVersions("cas-book")).hasSize(1);
        byte[] third = body("third");
        assertThatCode(() -> repository.publishVersion("cas-book", "1.0.2", third, sha(third),
                "{}", "tester", null, false))
                .as("未启用 CAS 时不受影响（既有调用方的语义）")
                .doesNotThrowAnyException();
    }
}
