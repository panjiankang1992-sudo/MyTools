package com.yuyutian.mytools.news.utils;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 将上游限流提示转换为有界冷却，避免自动重试形成请求风暴。 */
public final class RateLimitPolicy {
    private RateLimitPolicy() { }

    /** 同时尊重 Retry-After 和 GitHub 重置时刻，并至少冷却一分钟。 */
    public static Instant retryAt(Instant now, String retryAfter, String resetEpoch) {
        Instant result = now.plusSeconds(300);
        if (retryAfter != null) {
            try { result = now.plusSeconds(Long.parseLong(retryAfter)); }
            catch (NumberFormatException | java.time.DateTimeException exception) {
                try { result = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                catch (DateTimeParseException ignored) { result = now.plusSeconds(300); }
            } catch (ArithmeticException ignored) { result = now.plusSeconds(86400); }
        }
        if (resetEpoch != null) {
            try {
                Instant reset = Instant.ofEpochSecond(Long.parseLong(resetEpoch));
                if (reset.isAfter(result)) result = reset;
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                // 无效头部不影响已经计算的保守冷却时刻。
            }
        }
        if (result.isBefore(now.plusSeconds(60))) result = now.plusSeconds(60);
        if (result.isAfter(now.plusSeconds(86400))) result = now.plusSeconds(86400);
        return result;
    }
}
