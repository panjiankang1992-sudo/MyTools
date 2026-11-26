package com.yuyutian.mytools.news.service.technical;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** 解析公共 API 时不把缺失计数和错误日期默认为有效事实。 */
final class TechnicalParsing {
    private TechnicalParsing() { }

    static long nonNegative(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < 0) throw new NewsException(ErrorCode.NEWS_007);
        return number.longValue();
    }

    static long positive(JsonNode value, String field) {
        long result = nonNegative(value, field);
        if (result == 0) throw new NewsException(ErrorCode.NEWS_007);
        return result;
    }

    static String text(JsonNode value, String field, int limit) {
        return ContentIdentity.truncate(ContentIdentity.plainText(value.path(field).asText("")), limit);
    }

    static Instant date(JsonNode value, String field) {
        try { return Instant.parse(value.path(field).asText("")); }
        catch (DateTimeParseException exception) { return null; }
    }

    static boolean inWeek(Instant published, Instant now) {
        return published != null && !published.isBefore(now.minus(Duration.ofDays(7))) && !published.isAfter(now);
    }

    static long deadline() { return System.nanoTime() + Duration.ofSeconds(75).toNanos(); }
    static boolean expired(long deadline) { return System.nanoTime() >= deadline; }
}
