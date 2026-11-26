import org.flywaydb.core.Flyway;
public class RepairPreview {
 /** 修复无部分写入的失败迁移，并通过 Flyway 应用补齐后的必填字段。 */
 public static void main(String[] args) {
  Flyway f=Flyway.configure().dataSource("jdbc:mysql://127.0.0.1:3306/mytools_task",System.getenv("TASK_DB_USERNAME"),System.getenv("TASK_DB_PASSWORD")).locations("filesystem:/tmp/preview-flyway/migrations").load();
  f.repair(); f.migrate();
 }
}
