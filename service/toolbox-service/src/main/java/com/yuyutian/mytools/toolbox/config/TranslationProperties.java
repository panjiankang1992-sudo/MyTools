package com.yuyutian.mytools.toolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 匿名翻译限额，默认留出上游每日免费五千字符的一部分余量。
 *
 * @param globalDailyCharacters 全局每日外部字符数
 * @param guestDailyCharacters 单访客每日外部字符数
 * @param concurrency 最大并行外部请求数
 * @param cacheEntries 成功缓存容量
 * @param cacheTtlSeconds 成功缓存有效秒数
 * @param batchTimeoutMillis 整批最多等待毫秒数
 */
@ConfigurationProperties(prefix = "toolbox.translation")
public record TranslationProperties(
        @DefaultValue("4500") int globalDailyCharacters,
        @DefaultValue("1000") int guestDailyCharacters,
        @DefaultValue("2") int concurrency,
        @DefaultValue("2048") int cacheEntries,
        @DefaultValue("86400") long cacheTtlSeconds,
        @DefaultValue("25000") long batchTimeoutMillis) {

    /** 校验固定匿名免费上游的安全配置范围。 */
    public TranslationProperties {
        if (globalDailyCharacters < 1 || globalDailyCharacters > 5000 || guestDailyCharacters < 1
                || guestDailyCharacters > globalDailyCharacters || concurrency < 1 || concurrency > 4
                || cacheEntries < 1 || cacheEntries > 10000 || cacheTtlSeconds < 1 || cacheTtlSeconds > 86400
                || batchTimeoutMillis < 100 || batchTimeoutMillis > 30000) {
            throw new IllegalArgumentException("invalidTranslationLimits");
        }
    }
}
