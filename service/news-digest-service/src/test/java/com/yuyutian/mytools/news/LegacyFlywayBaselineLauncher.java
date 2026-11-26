package com.yuyutian.mytools.news;

/** 仅在独立 JVM 使用旧引擎创建测试历史，凭据仍通过私有配置和实例标记校验。 */
public final class LegacyFlywayBaselineLauncher {
    private LegacyFlywayBaselineLauncher() { }

    /** 为已创建的隔离 schema 建立旧版本迁移历史，不创建或选择业务数据库。 */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("LEGACY_MIGRATION_ARGUMENTS_REQUIRED");
        }
        MySqlCompatibilityTest.createLegacyBaseline(arguments[0], arguments[1], arguments[2]);
    }
}
