package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 在分析前补齐公开正文；所有替代表示均校验文章身份，失败保留原材料。 */
public final class ArticleContentResolver {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int TEXT_LIMIT = 4000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOISE = "script,style,nav,header,footer,aside,form,button,pre,"
            + ".comments,#comments,.comment,.related,.recommendations,.sidebar,.advertisement,"
            + ".byline,.author,.editor,.credits,.ad_editor,[class*=copyright],[id*=copyright],[class*=footer],[id*=footer],[class*=pagebottom],"
            + "[role=navigation],[role=dialog],[hidden],[aria-hidden=true],[style*=display:none],[style*=display: none]";
    private final FeedTransport transport;
    private final Set<String> limitedHosts = new HashSet<>();
    private final Map<String, Result> cache = new HashMap<>();

    /** 一轮采集复用缓存与站点限流状态，不保存凭据。 */
    public ArticleContentResolver(FeedTransport transport) { this.transport = transport; }

    /** 正文提取结果保留方法，避免将网页简介声称为完整正文。 */
    public record Extraction(String text, String method) { }

    /** 保存实际证据地址、尝试记录及原图声明，供报告审计和后续下载使用。 */
    public record Result(String text, String evidenceUrl, String method, String status,
                         List<Map<String, String>> attempts, Map<String, String> image, String fetchedAt) {
        /** 导出不含正文和凭据的获取记录。 */
        public Map<String, Object> trace() {
            return Map.of("status", status, "method", method, "evidenceUrl", evidenceUrl,
                    "fetchedAt", fetchedAt, "attempts", attempts);
        }
    }

    /** 每篇最多三次请求、四十五秒，且受分类总预算限制；禁止读取未授权正文。 */
    public Result resolve(SourceSpec source, String url, String title, long deadline) {
        if (source.evidencePolicy() != EvidencePolicy.EXCERPT_ALLOWED) return empty(url, "NOT_ALLOWED", List.of());
        if (source.kind() == SourceKind.HACKER_NEWS && "news.ycombinator.com".equalsIgnoreCase(URI.create(url).getHost())) {
            return empty(url, "DISCUSSION_ONLY", List.of()); // 论坛自链接只使用已采集的原帖材料，不抽取评论作为报道。
        }
        if (cache.containsKey(url)) return cache.get(url);
        long end = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(45).toNanos());
        List<Map<String, String>> attempts = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(url, "PAGE"));
        String github = githubReadmeUrl(url);
        // 官方仓库接口可在网页未服务端渲染 README 时提供相同分支的公开材料。
        if (!github.isEmpty()) targets.add(new Target(github, "GITHUB_README"));
        Extraction best = new Extraction("", "NONE");
        String evidenceUrl = url;
        Map<String, String> image = Map.of();
        String status = "UNAVAILABLE";
        Set<String> visited = new HashSet<>();
        for (int index = 0; index < targets.size() && attempts.size() < 3; index++) {
            if (System.nanoTime() >= end) { status = "BUDGET_EXHAUSTED"; break; }
            Target target = targets.get(index);
            if (!visited.add(target.url())) continue;
            try {
                URI uri = PublicNetworkPolicy.requireHttps(target.url());
                if (limitedHosts.contains(uri.getHost())) {
                    attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "RATE_LIMITED"));
                    continue; // 当前轮次不再反复访问已明确限流的主机。
                }
                SourceSpec request = new SourceSpec(source.id(), source.name(), target.url(), source.category(),
                        source.publisherKey(), source.regionCode(), source.evidencePolicy(), source.rightsReference(),
                        source.authority(), true, target.method().equals("GITHUB_README") ? SourceKind.GITHUB : SourceKind.RSS_ATOM);
                SourceState state = new SourceState(request, null, null, null, null, "REQUEST");
                boolean html = target.method().equals("PAGE") || target.method().equals("AMP");
                if (html && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "UNSUPPORTED_MEDIA"));
                    continue; // PDF 需要独立文档解析器，不作为 HTML 抓取和解析。
                }
                var response = html ? transport.fetchPage(state) : transport.fetch(state);
                if (response.status() != 200 || response.body().length > MAX_BYTES) {
                    attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "REJECTED_RESPONSE"));
                    continue;
                }
                Extraction extracted;
                if (html) {
                    Document page = parse(response.body(), target.url());
                    if (blocked(page)) {
                        attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "BLOCKED"));
                        status = "BLOCKED";
                        break; // 登录、验证页面不尝试替代接口绕过限制。
                    }
                    String canonicalUrl = canonical(page);
                    if ((target.method().equals("AMP") || !canonicalUrl.isBlank()) && !sameArticle(canonicalUrl, url)) {
                        attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "IDENTITY_MISMATCH"));
                        continue;
                    }
                    extracted = extract(page, target.url(), title, end);
                    if (image.isEmpty()) image = IllustratedReportCollector.extractImage(response.body(), target.url());
                    if (target.method().equals("PAGE")) targets.addAll(alternates(page, url));
                } else if (target.method().equals("GITHUB_README")) {
                    extracted = readme(JSON.readTree(response.body()), url);
                } else if (target.method().equals("DECLARED_FEED")) {
                    var matching = new FeedParser().parse(response.body(), target.url()).articles().stream()
                            .filter(item -> sameArticle(item.url(), url)).findFirst();
                    extracted = usable(title, matching.map(ArticleInput::evidenceText).orElse(""), "DECLARED_FEED");
                } else {
                    JsonNode data = JSON.readTree(response.body());
                    extracted = sameArticle(data.path("link").asText(), url)
                            ? usable(title, ContentIdentity.plainText(data.path("content").path("rendered").asText()), "WORDPRESS_API")
                            : new Extraction("", "NONE");
                }
                boolean available = ArticleExcerptReader.sufficient(title, extracted.text());
                attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", available ? "EXTRACTED" : "NO_BODY"));
                if (available && (best.text().isEmpty() || !extracted.method().equals("META_DESCRIPTION"))) {
                    best = extracted; evidenceUrl = target.url(); status = "FETCHED";
                    if (!extracted.method().equals("META_DESCRIPTION")) break;
                }
            } catch (SourceRateLimitException ignored) {
                limitedHosts.add(URI.create(target.url()).getHost());
                attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "RATE_LIMITED"));
            } catch (NewsException exception) {
                attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", exception.code().name()));
            } catch (IOException | IllegalArgumentException ignored) {
                attempts.add(Map.of("url", target.url(), "method", target.method(), "outcome", "INVALID_CONTENT"));
            }
        }
        if (!best.text().isEmpty()) status = "FETCHED";
        Result result = new Result(best.text(), evidenceUrl, best.method(), status, List.copyOf(attempts), image, Instant.now().toString());
        // 预算不足未发起请求的结果不缓存，避免影响下一分类的独立预算。
        if (!attempts.isEmpty()) cache.put(url, result);
        return result;
    }

    /** 解码网页并优先采用公开结构化正文、明确正文容器，再退回页面简介。 */
    public static Extraction extract(byte[] bytes, String url, String title) {
        if (bytes.length > MAX_BYTES) return new Extraction("", "NONE");
        try { return extract(parse(bytes, url), url, title, 0); }
        catch (IOException | IllegalArgumentException ignored) { return new Extraction("", "NONE"); }
    }

    private static Extraction extract(Document page, String url, String title, long deadline) {
        if (blocked(page)) return new Extraction("", "NONE");
        for (Element script : page.select("script[type=application/ld+json]")) {
            try {
                String body = structuredBody(JSON.readTree(script.data()), url, title, 0);
                Extraction structured = usable(title, body, "JSON_LD");
                if (!structured.text().isEmpty()) return structured;
            } catch (IOException ignored) { /* 单个结构化片段损坏不影响普通 HTML 正文。 */ }
        }
        page.select(NOISE).remove();
        // 摄影和编辑署名不能凑成实质正文长度，保留实际新闻段落。
        for (Element paragraph : page.select("p")) {
            String value = paragraph.text().strip();
            if (value.length() < 120 && value.matches("^(?:[\\[\\u3010]?(?:\u7f16\u8f91|\u6444\u5f71|\u8d23\u4efb\u7f16\u8f91)|Photo(?:graphy)?|Editor)\\s*[:\uFF1A].*")) paragraph.remove();
        }
        String host = URI.create(url).getHost();
        String selector = switch (host == null ? "" : host.toLowerCase(Locale.ROOT)) {
            case "github.com" -> ".markdown-body";
            case "www.js.chinanews.com.cn" -> "#cont #txt";
            case "jsnews.jschina.com.cn" -> "#content.articalCont";
            case "www.getlazarus.org" -> "#content";
            default -> "";
        };
        if (!selector.isEmpty()) {
            page.select(".button-row,.vodbox").remove();
            return usable(title, page.select(selector).text(), host.equalsIgnoreCase("github.com") ? "README_HTML" : "ARTICLE_HTML");
        }
        // 通用网页优先交给成熟提取组件，导航型多文章列表不进入正文提取。
        if (page.select("article").size() <= 1) {
            String extracted = OfflineArticleExtractor.extract(page.outerHtml(), url, deadline);
            Extraction external = usable(title, extracted, "TRAFILATURA");
            if (!external.text().isEmpty()) return external;
        }
        // 按明确容器的优先级取一个正文，避免将多篇推荐卡片拼成新闻。
        for (String candidate : List.of("[itemprop=articleBody]", ".article-body", ".article-content", ".entry-content",
                ".post-content", ".left_zw", ".incident-updates-container", "article", "main")) {
            var elements = page.select(candidate);
            if ((candidate.equals("article") || candidate.equals("main")) && elements.size() != 1) continue;
            for (Element element : elements) {
                if (candidate.equals("main") && element.select("article").size() > 1) continue;
                String value = element.text();
                if (ArticleExcerptReader.sufficient(title, value) && linkDensity(element) < 0.55) {
                    Extraction html = usable(title, value, "ARTICLE_HTML");
                    if (!html.text().isEmpty()) return html;
                }
            }
        }
        // 无语义标签的博客必须存在匹配标题及多个长段落，避免把门户导航误认为正文。
        boolean titleMatches = page.select("h1").stream().anyMatch(h -> titleMatches(h.text(), title));
        if (titleMatches) {
            Element best = null;
            int bestLength = 0;
            for (Element paragraph : page.select("p")) {
                Element parent = paragraph.parent();
                if (parent == null || parent.tagName().equals("body") || linkDensity(parent) > 0.3) continue;
                var longParagraphs = parent.select("p").stream().filter(p -> p.text().length() >= 80).toList();
                int length = longParagraphs.stream().mapToInt(p -> p.text().length()).sum();
                if (longParagraphs.size() >= 2 && length > bestLength) { best = parent; bestLength = length; }
            }
            if (best != null) return usable(title, best.select("p").text(), "PARAGRAPH_HTML");
        }
        for (Element meta : page.select("meta[property=og:description],meta[name=description]")) {
            Extraction fallback = usable(title, ContentIdentity.plainText(meta.attr("content")), "META_DESCRIPTION");
            if (!fallback.text().isEmpty()) return fallback;
        }
        return new Extraction("", "NONE");
    }

    private static String structuredBody(JsonNode node, String url, String title, int depth) {
        if (node == null || depth > 12) return "";
        if (node.isArray()) {
            for (JsonNode child : node) {
                String value = structuredBody(child, url, title, depth + 1);
                if (!value.isEmpty()) return value;
            }
        } else if (node.isObject()) {
            String type = node.path("@type").toString();
            if (type.matches(".*\"(?:NewsArticle|Article|BlogPosting|TechArticle|Report)\".*")
                    && !node.path("isAccessibleForFree").asText().equalsIgnoreCase("false")) {
                JsonNode identity = node.has("url") ? node.get("url") : node.path("mainEntityOfPage");
                String identityUrl = identity.isObject() ? identity.path("@id").asText() : identity.asText();
                // 有地址时必须精确对应当前文章，不能仅靠相似标题接纳相关推荐。
                boolean matches = identityUrl.isBlank() ? titleMatches(node.path("headline").asText(), title) : sameArticle(identityUrl, url);
                if (matches && node.path("articleBody").isTextual()) {
                    String text = ContentIdentity.plainText(node.path("articleBody").asText());
                    if (ArticleExcerptReader.sufficient(title, text)) return text;
                }
            }
            return structuredBody(node.path("@graph"), url, title, depth + 1);
        }
        return "";
    }

    private static List<Target> alternates(Document page, String url) {
        List<Target> values = new ArrayList<>();
        URI original = URI.create(url);
        for (Element link : page.select("link[rel=amphtml],link[rel=alternate][type=application/json],link[rel=alternate][type=application/rss+xml],link[rel=alternate][type=application/atom+xml]")) {
            try {
                URI target = PublicNetworkPolicy.requireHttps(original.resolve(link.attr("href")).toString());
                if (!Objects.equals(original.getHost(), target.getHost()) || original.getPort() != target.getPort()) continue;
                if (link.attr("rel").equals("amphtml")) values.add(new Target(target.toString(), "AMP"));
                else if (Set.of("application/rss+xml", "application/atom+xml").contains(link.attr("type"))) values.add(new Target(target.toString(), "DECLARED_FEED"));
                else if (target.getPath().matches(".*/wp-json/wp/v2/(posts|pages)/[0-9]+")) values.add(new Target(target.toString(), "WORDPRESS_API"));
            } catch (NewsException | IllegalArgumentException ignored) { /* 不跟随不安全或跨站的备用正文地址。 */ }
        }
        // 专用文章表示优先于站点订阅，重复声明不消耗额外尝试名额。
        return values.stream().distinct().sorted(Comparator.comparingInt(value -> value.method().equals("DECLARED_FEED") ? 1 : 0))
                .limit(2).toList();
    }

    private static String githubReadmeUrl(String url) {
        try {
            URI uri = PublicNetworkPolicy.requireHttps(url);
            if (!"github.com".equalsIgnoreCase(uri.getHost())) return "";
            String[] path = uri.getPath().replaceAll("/$", "").split("/");
            if (path.length != 3 && !(path.length == 5 && path[3].equals("tree"))) return "";
            if (!path[1].matches("[A-Za-z0-9_.-]+") || !path[2].matches("[A-Za-z0-9_.-]+")) return "";
            return "https://api.github.com/repos/" + path[1] + "/" + path[2] + "/readme"
                    + (path.length == 5 ? "?ref=" + URLEncoder.encode(path[4], StandardCharsets.UTF_8) : "");
        } catch (NewsException | IllegalArgumentException ignored) { return ""; }
    }

    private static Extraction readme(JsonNode node, String url) {
        if (!node.path("encoding").asText().equals("base64") || !node.path("type").asText().equals("file")) return new Extraction("", "NONE");
        URI original = URI.create(url), returned = URI.create(node.path("html_url").asText());
        String[] path = original.getPath().split("/");
        if (!"https".equals(returned.getScheme()) || !"github.com".equals(returned.getHost())
                || !returned.getPath().startsWith("/" + path[1] + "/" + path[2] + "/blob/")) return new Extraction("", "NONE");
        byte[] bytes = Base64.getDecoder().decode(node.path("content").asText().replaceAll("\\s", ""));
        if (bytes.length > MAX_BYTES) return new Extraction("", "NONE");
        if (path.length == 5 && !returned.getPath().startsWith("/" + path[1] + "/" + path[2] + "/blob/" + path[4] + "/")) {
            return new Extraction("", "NONE"); // 显式分支必须匹配，不能悄悄使用默认分支。
        }
        // 移除代码块和图片，仅将 README 的说明及项目列表作为证据。
        String markdown = new String(bytes, StandardCharsets.UTF_8).replaceAll("(?s)```.*?```|~~~.*?~~~", " ")
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ").replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        return usable("", ContentIdentity.plainText(markdown), "GITHUB_README");
    }

    private static Document parse(byte[] bytes, String url) throws IOException {
        return Jsoup.parse(new ByteArrayInputStream(bytes), null, url);
    }
    private static boolean blocked(Document page) {
        return page.title().toLowerCase(Locale.ROOT).matches(".*(just a moment|access denied|verify.{0,20}human|captcha|sign in|log in).*");
    }
    private static Extraction usable(String title, String text, String method) {
        // 通用提取器仍可能只返回页脚声明；去掉独立版权行后再判定证据是否足够。
        text = Arrays.stream(text.split("\\R")).map(String::strip).filter(line -> !line.isEmpty())
                .filter(line -> !line.matches("(?i)^(?:copyright\\s*[\\u00a9(c].*|.{0,100}all rights reserved.*|\u672c\u7f51\u7ad9\u6240\u520a\u8f7d\u4fe1\u606f.*|\u672a\u7ecf\u6388\u6743\u7981\u6b62\u8f6c\u8f7d.*|\u520a\u7528\u672c\u7f51\u7ad9\u7a3f\u4ef6.*)$"))
                .collect(java.util.stream.Collectors.joining("\n"));
        return ArticleExcerptReader.sufficient(title, text) ? new Extraction(ContentIdentity.truncate(text, TEXT_LIMIT), method) : new Extraction("", "NONE");
    }
    private static double linkDensity(Element element) {
        return (double) element.select("a").text().length() / Math.max(1, element.text().length());
    }
    private static boolean titleMatches(String left, String right) {
        String a = ContentIdentity.titleKey(left), b = ContentIdentity.titleKey(right);
        return !a.isBlank() && !b.isBlank() && (a.equals(b) || (Math.min(a.length(), b.length()) >= 20 && (a.contains(b) || b.contains(a))));
    }
    private static String canonical(Document page) { return page.select("link[rel=canonical]").attr("abs:href"); }
    private static boolean sameArticle(String left, String right) {
        try {
            URI a = URI.create(ContentIdentity.canonicalUrl(left)), b = URI.create(ContentIdentity.canonicalUrl(right));
            return a.getHost().replaceFirst("^www\\.", "").equals(b.getHost().replaceFirst("^www\\.", ""))
                    && a.getPort() == b.getPort() && Objects.equals(a.getRawQuery(), b.getRawQuery())
                    && a.getRawPath().replaceAll("/$", "").equals(b.getRawPath().replaceAll("/$", ""));
        } catch (NewsException | IllegalArgumentException ignored) { return false; }
    }
    private static Result empty(String url, String status, List<Map<String, String>> attempts) {
        return new Result("", url, "NONE", status, attempts, Map.of(), Instant.now().toString());
    }
    private record Target(String url, String method) { }
}
