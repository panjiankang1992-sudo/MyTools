package com.yuyutian.mytools.toolbox.service.translation;

import com.yuyutian.mytools.toolbox.config.TranslationProperties;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化每日字符预算，全局与访客扣减在同一事务中原子完成。 */
@Component
public class TranslationQuota {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TranslationProperties properties;
    private final Clock clock;

    /**
     * 创建字符预算。
     *
     * @param jdbc 数据库访问
     * @param transactions 事务模板
     * @param properties 额度设置
     * @param clock 日期时钟
     */
    public TranslationQuota(JdbcTemplate jdbc, TransactionTemplate transactions, TranslationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 在发往上游之前占用字符额度；已发请求失败也可能被供应商计费，因此不退还。
     *
     * @param quotaScope **完整额度主体**（例如 `guest:&lt;访客标识&gt;` 或 `admin:&lt;管理员标识&gt;`）。
     *                   **由调用方带上命名空间前缀**——本方法不再自己拼。
     *                   这样管理端与访客端能各自独立计额度（F06）。
     * @param characters 源正文 Unicode 码点数
     * @return 是否成功取得额度
     */
    public boolean tryConsume(String quotaScope, int characters) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Date day = Date.valueOf(today);
        // 此前这里是 `"guest:" + guestId`。若调用方传 `"admin:…"`，落库会变成
        // `guest:admin:…` —— **看起来独立、实际仍与访客共用额度**。
        // 所以前缀改由调用方给出，本方法只当作完整主体使用。
        String guest = quotaScope;
        // 旧日期不再参与预算，定期清理保证访客键不会无限累积。
        jdbc.update("DELETE FROM toolbox_translation_quota WHERE quota_day < ?", Date.valueOf(today.minusDays(2)));
        ensure(day, "global");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // 所有调用都先锁全局行再锁访客行，避免相反锁序导致死锁。
            if (!debit(day, "global", characters, properties.globalDailyCharacters())) {
                return false;
            }
            // 全局预算通过后才创建访客行，预算耗尽后的新访客不能撑大表。
            ensure(day, guest);
            if (!debit(day, guest, characters, properties.guestDailyCharacters())) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private void ensure(Date day, String scope) {
        try {
            jdbc.update("INSERT INTO toolbox_translation_quota (quota_day, quota_scope, used_characters) VALUES (?, ?, 0)", day, scope);
        } catch (DuplicateKeyException ignored) {
            // 并发创建的同日额度行由数据库唯一键合并，不重置已有用量。
        }
    }

    private boolean debit(Date day, String scope, int count, int maximum) {
        return count > 0 && jdbc.update("UPDATE toolbox_translation_quota SET used_characters = used_characters + ? "
                + "WHERE quota_day = ? AND quota_scope = ? AND used_characters <= ?", count, day, scope, maximum - count) == 1;
    }
}
