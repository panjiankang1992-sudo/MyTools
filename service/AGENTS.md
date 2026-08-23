# Service Workspace Instructions

- 该目录中的新服务不得隐式加入 MyTools 根 Maven 构建。
- Java 服务使用 Java 21、Spring Boot 和 Maven；Python 服务使用 Python 3.12。
- Java 代码注释使用中文，代码标识符和配置键使用英文。
- 所有 public Java 方法必须有中文 Javadoc。
- 任务步骤只能引用已发布、不可变版本的脚本包。
- 脚本不得直接修改 Scheduler 数据库。
- 直接 DML 必须使用参数化 SQL、最小权限账号、幂等键及任务审计字段。
- 新旧实现迁移期间必须支持旁路、双跑、对账和快速回退。
- 禁止在仓库中保存数据库密码、Bot Token、邮件授权码或生产 Secret。
