package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 从同一不可变日报生成确定性分片，任何新闻文本均按不可信输入转义。 */
@Component
public class NewsDeliveryRenderer {
    public static final String VERSION = "news-delivery-v3";
    private final ObjectMapper json;
    private final DeliveryProperties properties;
    /** 注入固定阅读地址和 JSON 字节预算计算器。 */
    public NewsDeliveryRenderer(ObjectMapper json, DeliveryProperties properties) { this.json = json; this.properties = properties; }

    /** 完整版保留各类二十标题与前十可用详情，精简版明确标记详情省略。 */
    public List<RenderedPart> render(Edition edition, Channel channel, RenderMode mode) {
        if (channel == Channel.QQ) throw new NewsException(ErrorCode.NEWS_024);
        var zone=java.time.ZoneId.of(edition.zone());
        String subject = "\u6bcf\u65e5\u65b0\u95fb\u901f\u62a5 " + edition.cutoff().atZone(zone).toLocalDate();
        List<String> lines = new ArrayList<>();
        lines.add(subject);
        lines.add("\u66f4\u65b0\u81f3 " + time(edition.cutoff(),zone) + " \u00b7 " + edition.zone());
        lines.add("\u6280\u672f\u770b\u8fd1\u4e03\u65e5\uff0c\u5176\u4ed6\u5206\u7c7b\u770b\u8fd124\u5c0f\u65f6\u3002\u8be6\u60c5\u6765\u81ea\u6765\u6e90\u6458\u8981\uff0c\u4fdd\u7559\u539f\u6587\u8bed\u8a00\u3002");
        if(edition.quality()==Quality.DEGRADED)lines.add("\u90e8\u5206\u5206\u7c7b\u6750\u6599\u4e0d\u8db3\uff0c\u4ee5\u4e0b\u6309\u5b9e\u9645\u53d6\u5f97\u7684\u5185\u5bb9\u5c55\u793a\u3002");
        if (!edition.region().usable())lines.add("\u672c\u5730\u65b0\u95fb\uff1a\u6682\u672a\u53d6\u5f97\u53ef\u7528\u7684\u670d\u52a1\u6240\u5728\u5730\u6216\u5bf9\u5e94\u65b0\u95fb\u6e90\u3002");
        else {
            var details=edition.region().details();
            String label=details==null?edition.region().regionCode():details.cityName()!=null?details.cityName():details.sourcePackLabel();
            lines.add("\u672c\u5730\u8303\u56f4\uff1a"+Objects.toString(label,edition.region().regionCode()));
        }
        if (!properties.readerUrl().isBlank()) {lines.add("\u6253\u5f00\u5b8c\u6574\u65e5\u62a5\uff1a");lines.add(properties.readerUrl() + "?edition=" + edition.id());}
        for (Category category : Category.values()) {
            Section section = edition.sections().get(category);
            if (section == null) { lines.add("\u3010"+categoryName(category)+"\u3011\u6682\u65e0\u5185\u5bb9"); continue; }
            lines.add("\n\u3010"+categoryName(category)+"\u3011" + section.headlineCount() + "\u6761 \u00b7 \u524d10\u6761\u4e2d\u6709" + section.detailCount() + "\u6761\u8be6\u60c5");
            if(category==Category.TECH && "personal-source-mix-v4".equals(edition.rankingVersion()))
                lines.add("\u6280\u672f\u7efc\u5408\u9009\u62a5\uff1a\u4e3a\u5f53\u524d GitHub \u5019\u9009\u4fdd\u7559\u6700\u591a5\u5e2d\uff0c\u5176\u4f59\u6309\u6765\u6e90\u70ed\u5ea6\u6392\u5e8f\u3002\u4e0d\u4ee3\u8868\u5168\u7f51\u70ed\u5ea6\u699c\u3002");
            if(section.items().isEmpty())lines.add("\u672c\u671f\u6682\u65e0\u7b26\u5408\u65f6\u95f4\u8303\u56f4\u7684\u65b0\u95fb\u3002");
            for (RankedItem item : section.items().stream().limit(20).toList()) {
                lines.add(item.rank() + ". " + singleLine(item.title()));
                addUrl(lines, item.url());
                lines.add("\u6765\u6e90\uff1a" + String.join(", ", item.sourceIds()) + " \u00b7 " + time(item.publishedAt(),zone));
                if(item.technical()!=null && item.technical().github()!=null) {
                    GithubTrend trend=item.technical().github();
                    lines.add("GitHub\uff1a\u7d2f\u8ba1 Star " + trend.stars() + " \u00b7 Fork " + trend.forks()
                            + (trend.status()==TrendStatus.READY && trend.netStars()!=null?" \u00b7 \u8fd1\u4e03\u65e5\u51c0\u53d8\u5316 "+trend.netStars():" \u00b7 \u5468\u589e\u91cf\u6837\u672c\u5c1a\u4e0d\u5b8c\u6574"));
                }
                if (item.rank() <= 10) {
                    if (mode == RenderMode.BRIEF && item.rank() > 3) lines.add("\u8be6\u60c5\u89c1\u5b8c\u6574\u65e5\u62a5\u3002");
                    else if (item.detail() == null || item.detailStatus() != DetailStatus.AVAILABLE) lines.add("\u6682\u7f3a\u53ef\u9760\u8be6\u60c5\uff0c\u53ef\u67e5\u770b\u539f\u6587\u3002");
                    else {
                        lines.add("\u6458\u8981\uff1a" + item.detail().text());
                        // 引用仍保留可阅读链接，消息正文不展示内部版本摘要和事件标识。
                        Set<String> links=new LinkedHashSet<>();links.add(item.url());
                        for (Citation citation : item.detail().citations()) if(links.add(citation.url()))addUrl(lines,citation.url());
                    }
                }
                lines.add("");
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            // 单行保持完整，包括 Unicode 代理对和网址，超过预算则明确失败而非截断链接。
            String next = current.isEmpty() ? line : current + "\n" + line;
            if (!fits(subject, next, channel)) {
                if (current.isEmpty() || !fits(subject, line, channel)) throw new NewsException(ErrorCode.NEWS_024);
                chunks.add(current.toString()); current.setLength(0); current.append(line);
            } else { if (!current.isEmpty()) current.append('\n'); current.append(line); }
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        if (chunks.size() > 100) throw new NewsException(ErrorCode.NEWS_024);
        List<RenderedPart> result = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String text = chunks.get(index);
            Content content = new Content(subject + (chunks.size()==1?"":" [" + (index + 1) + "/" + chunks.size() + "]"), text, channel == Channel.EMAIL ? html(text) : null);
            result.add(new RenderedPart(index + 1, chunks.size(), content, hash(content)));
        }
        return List.copyOf(result);
    }
    private boolean fits(String subject, String text, Channel channel) {
        if (channel == Channel.EMAIL) return bytes(text) <= 150000 && bytes(html(text)) <= 300000;
        try {
            return json.writeValueAsBytes(Map.of("msg_type", "text", "content", Map.of("text", (subject + " [100/100]\n\n" + text).replace('<', '\u2039').replace('>', '\u203a')),
                    "timestamp", "9999999999", "sign", "x".repeat(44))).length <= 14000;
        } catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_024); }
    }
    private static String categoryName(Category category) {
        return switch(category){case TECH->"\u6280\u672f";case WORLD->"\u56fd\u9645";case DOMESTIC->"\u56fd\u5185";case LOCAL->"\u672c\u5730";case TRENDING->"\u6d41\u884c";case MILITARY->"\u519b\u4e8b";case FINANCE->"\u8d22\u7ecf";case SOCIETY->"\u793e\u4f1a";case SPORTS->"\u4f53\u80b2";case ENTERTAINMENT->"\u5a31\u4e50";};
    }
    private static String time(java.time.Instant value,java.time.ZoneId zone) {
        return value==null?"\u53d1\u5e03\u65f6\u95f4\u672a\u77e5":java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(value);
    }
    private static String singleLine(String text) { return text.replace('\r',' ').replace('\n',' '); }
    private static void addUrl(List<String> lines, String value) { lines.add(safeUrl(value) ? value : "\u539f\u6587\u94fe\u63a5\u6682\u4e0d\u53ef\u7528"); }
    /** 仅允许没有内嵌凭据的绝对 HTTP(S) 阅读链接。 */
    public static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException | NullPointerException exception) { return false; }
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static String html(String text) {
        StringBuilder result = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body style=\"margin:0;background:#f5f6f8;color:#202534;font-family:Arial,sans-serif\"><main style=\"max-width:720px;margin:24px auto;padding:24px;background:#fff;line-height:1.8\">");
        int index=0;
        for (String line : text.split("\n", -1)) {
            if(line.isBlank())continue;
            String escaped = escape(line);
            String tag=index++==0?"h1":line.startsWith("\u3010")?"h2":line.matches("[0-9]+\\. .*")?"h3":"p";
            // 外部文本仅作为转义后的正文，链接只接受已校验的 HTTP(S) 地址。
            result.append('<').append(tag).append(" style=\"white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0\">");
            if (safeUrl(line)) result.append("<a style=\"color:#275dad\" href=\"").append(escaped).append("\" rel=\"noopener noreferrer\">\u9605\u8bfb\u539f\u6587 \u00b7 ").append(escape(URI.create(line).getHost())).append("</a>");
            else result.append(escaped);
            result.append("</").append(tag).append('>');
        }
        return result.append("</main></body></html>").toString();
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    /** 与 Messaging v2 一致的长度前缀 UTF-8 摘要，避免字段拼接歧义。 */
    public static String hash(Content content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(content.subject(), content.text(), content.html() == null ? "" : content.html())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }
}
