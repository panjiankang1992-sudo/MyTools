package com.yuyutian.mytools.toolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 公开门面配置：来源、站点标识、教材目录、功能开关与限额。
 * 所有键使用英文，敏感值只从环境变量注入，默认值不包含任何凭据。
 *
 * @param publicOrigin  精确的站点来源，用于同源与 CSRF 校验，禁止使用通配符
 * @param publicOrigins 允许来源清单，逗号分隔；非空时优先于 publicOrigin，禁止使用通配符
 * @param siteId        统计隔离用的站点标识
 * @param catalogPath   只读的已发布教材根目录
 * @param policyVersion 公开策略版本
 * @param pdfEnabled    是否开放公开 PDF 任务（本里程碑固定关闭）
 * @param statsEnabled  是否开放统计读取
 * @param session       会话配置
 * @param events        事件接口配置
 * @param stats         统计聚合配置
 * @param curriculum    教材接口限额
 */
@ConfigurationProperties(prefix = "toolbox")
public record ToolboxProperties(
        @DefaultValue("") String publicOrigin,
        @DefaultValue("") String publicOrigins,
        @DefaultValue("yuyutian-toolbox") String siteId,
        @DefaultValue("./content") String catalogPath,
        @DefaultValue("2026-10-02") String policyVersion,
        @DefaultValue("false") boolean pdfEnabled,
        @DefaultValue("true") boolean statsEnabled,
        Session session,
        Events events,
        Stats stats,
        Curriculum curriculum) {

    /**
     * 匿名会话配置。
     *
     * @param cookieName   会话 cookie 名称，生产默认带 __Host- 前缀
     * @param cookieSecure 是否只在 HTTPS 下发送 cookie
     * @param ttlHours     会话固定有效期，单位小时，创建后不因活动延长
     */
    public record Session(
            @DefaultValue("__Host-toolbox_guest") String cookieName,
            @DefaultValue("true") boolean cookieSecure,
            @DefaultValue("24") long ttlHours) {
    }

    /**
     * 事件接口配置。
     *
     * @param maxBatchSize      单批最多事件数
     * @param maxBodyBytes      请求体最大字节数
     * @param maxPastHours      事件时间允许的最早回溯小时数
     * @param maxFutureMinutes  事件时间允许的最大超前分钟数
     */
    public record Events(
            @DefaultValue("50") int maxBatchSize,
            @DefaultValue("32768") long maxBodyBytes,
            @DefaultValue("24") long maxPastHours,
            @DefaultValue("5") long maxFutureMinutes) {
    }

    /**
     * 统计聚合与读取配置。
     *
     * @param cacheTtlSeconds      统计响应内存缓存秒数，0 表示关闭缓存
     * @param aggregateIntervalMs  聚合任务间隔毫秒数
     * @param aggregateBatchSize   单次聚合消费的 outbox 条数
     * @param aggregationEnabled   是否启用定时聚合
     * @param outboxLeaseSeconds   outbox 租约秒数，用于崩溃后重新接管
     */
    public record Stats(
            @DefaultValue("300") long cacheTtlSeconds,
            @DefaultValue("60000") long aggregateIntervalMs,
            @DefaultValue("200") int aggregateBatchSize,
            @DefaultValue("true") boolean aggregationEnabled,
            @DefaultValue("60") long outboxLeaseSeconds) {
    }

    /**
     * 教材接口限额配置。
     *
     * @param entriesDefaultLimit 条目分页默认条数
     * @param entriesMaxLimit     条目分页最大条数
     */
    public record Curriculum(
            @DefaultValue("100") int entriesDefaultLimit,
            @DefaultValue("500") int entriesMaxLimit) {
    }
}
