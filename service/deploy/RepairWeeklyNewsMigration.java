import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** 仅在确认唯一失败项是本次新闻迁移时调用 Flyway 官方恢复流程。 */
public final class RepairWeeklyNewsMigration {
    private RepairWeeklyNewsMigration() { }

    /** 使用运维进程注入的既有数据库身份，不把凭据放入命令行或日志。 */
    public static void main(String[] args) {
        Flyway flyway = Flyway.configure().dataSource(System.getenv("TASK_DB_URL"),
                System.getenv("TASK_DB_USERNAME"), System.getenv("TASK_DB_PASSWORD"))
                .locations("classpath:db/migration").load();
        int failed = 0;
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getState().isFailed()) {
                if (!"185".equals(migration.getVersion().toString())
                        || !"V185__seed_manual_weekly_news.sql".equals(migration.getScript())) {
                    throw new IllegalStateException("Unexpected failed migration");
                }
                failed++;
            }
        }
        if (failed != 1) throw new IllegalStateException("Expected one failed migration");
        flyway.repair();
        System.out.println("NEWS_WEEKLY_MIGRATION_REPAIRED");
    }
}
