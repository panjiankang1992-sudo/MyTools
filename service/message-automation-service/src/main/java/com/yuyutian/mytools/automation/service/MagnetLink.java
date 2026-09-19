package com.yuyutian.mytools.automation.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 磁力链接校验与规范化，保留名称和 tracker 参数。 */
final class MagnetLink {
    private static final Pattern HASH = Pattern.compile("(?:[0-9a-fA-F]{40}|[A-Z2-7]{32})",
            Pattern.CASE_INSENSITIVE);

    private MagnetLink() { }

    static boolean isMagnet(String value) {
        return value != null && value.regionMatches(true, 0, "magnet:", 0, 7);
    }

    static String normalize(String value) {
        if (!isMagnet(value) || !value.substring(7).startsWith("?")) {
            throw new IllegalArgumentException("Invalid magnet URI");
        }
        String normalized = "magnet:" + value.substring(7);
        URI.create(normalized);
        int validHashes = 0;
        for (String pair : normalized.substring(8).split("&")) {
            String[] entry = pair.split("=", 2);
            if (entry.length == 2 && "xt".equals(entry[0])) {
                String topic = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                // 仅支持一个明确的 BTIH，避免多个资源身份合并为同一下载动作。
                if (topic.toLowerCase(Locale.ROOT).startsWith("urn:btih:")
                        && HASH.matcher(topic.substring(9)).matches()) {
                    validHashes++;
                } else {
                    throw new IllegalArgumentException("Invalid magnet topic");
                }
            }
        }
        if (validHashes != 1 || normalized.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Invalid magnet topic count");
        }
        return normalized;
    }
}
