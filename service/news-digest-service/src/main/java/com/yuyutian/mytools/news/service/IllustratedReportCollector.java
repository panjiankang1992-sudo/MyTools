package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.jsoup.Jsoup;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 为个人图文报告收集有来源的正文与原图，供无头分析读取，不发送邮件。 */
public final class IllustratedReportCollector {
    private final FeedTransport transport;

    /** 使用现有公网 HTTPS、大小和超时限制。 */
    public IllustratedReportCollector(FeedTransport transport) { this.transport = transport; }

    /** 保留候选顺序，为所有候选补读正文与配图，供模型合并后重新排序。 */
    public Map<String, Object> collect(Edition edition, List<Article> articles, Path output) throws IOException {
        Path images = output.resolve("images");
        Files.createDirectory(images);
        Map<String, Article> versions = new HashMap<>();
        articles.forEach(article -> versions.put(article.id(), article));
        List<Map<String, Object>> sections = new ArrayList<>();
        var resolver = new ArticleContentResolver(transport);
        for (Category category : Category.values()) {
            Section section = edition.sections().get(category);
            List<Map<String, Object>> items = new ArrayList<>();
            List<Pending> pending = new ArrayList<>();
            for (RankedItem item : section.items()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", item.rank()); entry.put("eventId", item.eventId());
                entry.put("title", item.title()); entry.put("url", item.url());
                entry.put("publishedAt", Objects.toString(item.publishedAt(), ""));
                entry.put("sources", item.sourceIds()); entry.put("score", item.score());
                if (item.technical() != null) entry.put("technical", item.technical());
                Article article = item.detail() == null ? null : item.detail().citations().stream()
                        .map(citation -> versions.get(citation.articleId())).filter(Objects::nonNull).findFirst().orElse(null);
                if (article == null) article = articles.stream().filter(value -> value.url().equals(item.url()))
                        .max(Comparator.comparing(Article::fetchedAt)).orElse(null);
                // 过期或只允许元数据的来源既不导出正文，也不发起补读请求。
                boolean readable = article != null && article.source().evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED
                        && article.evidenceExpiredAt() == null;
                String text = readable ? Objects.toString(article.evidenceText(), "") : "";
                entry.put("evidence", ContentIdentity.truncate(text, 4000));
                entry.put("evidenceUrl", article == null ? item.url() : DigestEngine.evidenceUrl(article));
                entry.put("detailAvailable", ArticleExcerptReader.sufficient(item.title(), text));
                entry.put("imageStatus", "UNAVAILABLE");
                entry.put("contentAcquisition", Map.of("status", readable ? "PENDING" : "NOT_ALLOWED", "attempts", List.of()));
                items.add(entry);
                if (readable) pending.add(new Pending(item, article, entry));
            }
            // 缺正文的条目先使用独立预算，图片下载不能挤占正文获取时间。
            pending.sort(Comparator.comparing(value -> (Boolean) value.entry().get("detailAvailable")));
            long categoryDeadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            long bodyDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            Map<Integer, Map<String, String>> pictures = new HashMap<>();
            Map<String, Map<String, String>> sourceImages = Map.of();
            if ((category == Category.TRENDING || category == Category.MILITARY) && !pending.isEmpty()) {
                // 复用平台声明的热榜配图，禁止从搜索页采集无关图片。
                var source = pending.getFirst().article().source();
                if (source.kind() == SourceKind.BAIDU_HOT || source.kind() == SourceKind.CCTV_MILITARY) {
                    try {
                        byte[] body = fetch(source, source.feedUrl(), true);
                        sourceImages = source.kind() == SourceKind.BAIDU_HOT ? BaiduHotParser.images(body) : CctvMilitaryParser.images(body);
                    }
                    catch (NewsException | IllegalArgumentException ignored) { /* 热榜图片不可用时保留摘要。 */ }
                }
            }
            int before = (int) items.stream().filter(row -> Boolean.TRUE.equals(row.get("detailAvailable"))).count();
            for (Pending value : pending) {
                if (value.article().source().kind() == SourceKind.BAIDU_HOT) {
                    value.entry().put("contentAcquisition", Map.of("status", "PLATFORM_SUMMARY", "attempts", List.of()));
                    value.entry().put("observedAt", value.article().fetchedAt().toString());
                    if (sourceImages.containsKey(value.item().url())) pictures.put(value.item().rank(), sourceImages.get(value.item().url()));
                    continue;
                }
                var result = resolver.resolve(value.article().source(), value.item().url(), value.item().title(), bodyDeadline);
                var entry = value.entry();
                String oldText = (String) entry.get("evidence");
                boolean used = result.text().length() > oldText.length();
                if (used) {
                    entry.put("evidence", result.text()); entry.put("evidenceUrl", result.evidenceUrl());
                    entry.put("detailAvailable", ArticleExcerptReader.sufficient(value.item().title(), result.text()));
                }
                Map<String, Object> trace = new LinkedHashMap<>(result.trace());
                trace.put("usedForSummary", used);
                trace.put("selectedEvidenceUrl", entry.get("evidenceUrl"));
                entry.put("contentAcquisition", trace);
                // 列表声明的原图优先于页面模板的通用频道封面。
                if (sourceImages.containsKey(value.item().url())) pictures.put(value.item().rank(), sourceImages.get(value.item().url()));
                else if (!result.image().isEmpty()) pictures.put(value.item().rank(), result.image());
            }
            long imageDeadline = Math.min(categoryDeadline, System.nanoTime() + Duration.ofSeconds(30).toNanos());
            long imageBytes = 0;
            pending.sort(Comparator.comparingInt(value -> value.item().rank()));
            for (Pending value : pending) {
                Map<String, String> image = pictures.get(value.item().rank());
                if (image == null || System.nanoTime() >= imageDeadline || imageBytes >= 8 * 1024 * 1024) continue;
                try {
                    byte[] bytes = fetch(value.article().source(), image.get("url"), false);
                    String format = imageFormat(bytes);
                    if (format == null || imageBytes + bytes.length > 8 * 1024 * 1024) continue;
                    String filename = category.name().toLowerCase(Locale.ROOT) + "-" + value.item().rank() + "." + format;
                    Files.write(images.resolve(filename), bytes, StandardOpenOption.CREATE_NEW);
                    value.entry().put("image", Map.of("url", image.get("url"), "caption", image.get("caption"),
                            "sourceUrl", value.item().url(), "path", "images/" + filename,
                            "mime", "image/" + (format.equals("jpg") ? "jpeg" : format), "bytes", bytes.length));
                    value.entry().put("imageStatus", "EMBEDDED"); imageBytes += bytes.length;
                } catch (NewsException | IllegalArgumentException | IOException ignored) {
                    // 原图失败不丢弃已取得的正文；后续模板可以使用有事实依据的摘要卡。
                }
            }
            int after = (int) items.stream().filter(row -> Boolean.TRUE.equals(row.get("detailAvailable"))).count();
            sections.add(Map.of("category", category.name(), "items", items, "imageBytes", imageBytes,
                    "contentAcquisition", Map.of("before", before, "after", after, "recovered", after - before,
                            "unavailable", items.size() - after, "completedAt", Instant.now().toString())));
        }
        return Map.of("editionId", edition.id().toString(), "cutoff", edition.cutoff().toString(),
                "zone", edition.zone(), "region", edition.region(), "sections", sections);
    }

    private record Pending(RankedItem item, Article article, Map<String, Object> entry) { }

    private byte[] fetch(SourceSpec source, String url, boolean page) {
        SourceSpec request = new SourceSpec(source.id(), source.name(), url, source.category(), source.publisherKey(),
                source.regionCode(), source.evidencePolicy(), source.rightsReference(), source.authority(), true);
        var state = new SourceState(request, null, null, null, null, "REQUEST");
        var response = page ? transport.fetchPage(state) : transport.fetch(state);
        return response.status() == 200 ? response.body() : new byte[0];
    }

    /** 只选文章自身声明的配图，过滤标志、头像、追踪像素和不安全地址。 */
    public static Map<String, String> extractImage(byte[] body, String url) {
        try {
            var document = Jsoup.parse(new ByteArrayInputStream(body), null, url);
            if (document.title().toLowerCase(Locale.ROOT).matches(".*(just a moment|access denied|captcha|sign in|log in).*")) return Map.of();
            var candidates = new ArrayList<>(document.select("article img, [itemprop=articleBody] img, .article-body img, .article-content img, .left_zw img, #cont #txt img"));
            if ("jsnews.jschina.com.cn".equalsIgnoreCase(URI.create(url).getHost())) {
                // 只取该站正文内原图，不能把站标或侧栏推广图当作文章配图。
                candidates.addAll(document.select("#content.articalCont img"));
            }
            candidates.addAll(document.select("meta[property=og:image], meta[name=twitter:image]"));
            for (var element : candidates) {
                String raw = element.tagName().equals("meta") ? element.attr("content")
                        : !element.attr("data-src").isBlank() ? element.attr("data-src") : element.attr("src");
                if (raw.isBlank() || raw.toLowerCase(Locale.ROOT).matches(".*(?:logo|avatar|sprite|badge|tracking|favicon).*")) continue;
                if (Set.of("0", "1").contains(element.attr("width")) || Set.of("0", "1").contains(element.attr("height"))) continue;
                try {
                    URI image = URI.create(url).resolve(raw);
                    // 新闻站旧模板有 HTTP 原图地址，仅升级为 HTTPS；实际获取失败时不回退明文。
                    if ("http".equalsIgnoreCase(image.getScheme())) image = URI.create("https:" + image.toString().substring(5));
                    PublicNetworkPolicy.requireHttps(image.toString());
                    if (image.toString().length() > 2048 || image.getPath().toLowerCase(Locale.ROOT).endsWith(".svg")) continue;
                    return Map.of("url", image.toString(), "caption", ContentIdentity.truncate(ContentIdentity.plainText(element.attr("alt")), 160));
                } catch (NewsException | IllegalArgumentException ignored) { /* 跳过单个非法配图，不影响后续候选。 */ }
            }
        } catch (IOException ignored) { /* 页面解码失败时返回明确缺图。 */ }
        return Map.of();
    }

    /** 依据真实文件头识别邮件可内嵌的图片，并限制解码尺寸。 */
    public static String imageFormat(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) return null;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "jpg", "png", "gif").contains(format)) return null;
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 120 || height < 70 || width > 8192 || height > 8192 || (long) width * height > 20_000_000) return null;
                return format.equals("jpeg") ? "jpg" : format;
            } finally { reader.dispose(); }
        }
    }
}
