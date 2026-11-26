import org.flywaydb.core.Flyway;
import java.sql.DriverManager;

/** 仅在下载迁移尚未写入业务配置时，使用官方 Flyway API 修复失败版本元数据。 */
public final class RepairFailedDownloadMigration {
    /** 使用进程环境中的最小权限调度账号执行有界修复，不输出凭据。 */
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mytools_task?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
        String user = System.getenv("TASK_DB_USERNAME");
        String password = System.getenv("TASK_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=FALSE")) {
                if (!rows.next() || !"180".equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("unexpected_failed_migration_scope");
                }
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM execution_cluster WHERE id='a1800000-0000-4000-8000-000000000001'")) {
                rows.next();
                if (rows.getInt(1) != 0) {
                    throw new IllegalStateException("partial_migration_requires_separate_review");
                }
            }
        }
        Flyway.configure().dataSource(url, user, password)
                .locations("filesystem:" + args[0]).load().repair();
    }
}
