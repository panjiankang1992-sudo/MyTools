package com.yuyutian.mytools.news.utils;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import org.jsoup.Jsoup;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/** 用规范化文本和网址生成稳定身份，保留有意义的查询参数。 */
public final class ContentIdentity {
    private ContentIdentity() { }

    /** 计算文本的稳定摘要。 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 将来源富文本转换为无可执行标记的纯文本。 */
    public static String plainText(String value) {
        if (value == null) return ""; // 缺失证据保持为空，不以标题填充。
        return Jsoup.parse(value).text().replaceAll("\\s+", " ").strip();
    }

    /** 规范化同稿标题，保留实体数字以避免合并不同事件。 */
    public static String titleKey(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    /** 规范化可展示链接，仅移除明确的跟踪参数和片段。 */
    public static String canonicalUrl(String value) {
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getHost().length() > 253) {
                // 展示链接同样拒绝脚本协议、用户名和非法主机。
                throw new NewsException(ErrorCode.NEWS_008);
            }
            String query = uri.getRawQuery();
            if (query != null) {
                // 不排序未知参数，避免改变签名网址或带顺序的业务参数。
                query = Arrays.stream(query.split("&")).filter(item -> {
                    String name = item.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !name.startsWith("utm_") && !name.equals("fbclid") && !name.equals("gclid");
                }).collect(Collectors.joining("&"));
            }
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            // 使用原始路径和查询避免二次编码百分号。
            return scheme + "://" + host + (port == -1 ? "" : ":" + port) + path
                    + (query == null || query.isEmpty() ? "" : "?" + query);
        } catch (URISyntaxException | NullPointerException exception) {
            throw new NewsException(ErrorCode.NEWS_008);
        }
    }

    /** 按码点截取文本，避免截断代理对。 */
    public static String truncate(String text, int limit) {
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }
}
