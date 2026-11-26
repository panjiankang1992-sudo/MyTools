package com.yuyutian.mytools.feed;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 保留稳定条目身份与受限图文的 RSS 2.0/Atom 1.0 解析器。 */
public final class FeedParser {
    // 四分类每天二十篇的一周窗口最多五百六十篇，保留有界扩展余量。
    private static final int MAX_ENTRIES = 2000;
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final Safelist HTML = Safelist.relaxed()
            .removeProtocols("a", "href", "ftp", "mailto")
            .removeProtocols("img", "src", "http")
            .removeAttributes("img", "height", "width", "align")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("img", "referrerpolicy", "no-referrer")
            .addEnforcedAttribute("img", "loading", "lazy");

    /** 解析条目，日期缺失不伪造，内容修订不改变原生身份。 */
    public FeedModels.Feed parse(byte[] bytes, String feedUrl) {
        Element root = SafeXml.parse(bytes).getDocumentElement();
        boolean atom = "feed".equals(root.getLocalName()) && ATOM.equals(root.getNamespaceURI());
        if (!atom && !("rss".equals(root.getLocalName()) && "2.0".equals(root.getAttribute("version")))) throw new FeedException(FeedException.Kind.FORMAT);
        Element container = atom ? root : child(root, "channel", null);
        if (container == null) throw new FeedException(FeedException.Kind.FORMAT);
        String ns = atom ? ATOM : null;
        var items = children(container, atom ? "entry" : "item", ns);
        if (items.size() > MAX_ENTRIES) throw new FeedException(FeedException.Kind.LIMIT);
        var entries = new LinkedHashMap<String, FeedModels.Entry>();
        for (Element item : items) {
            String base = base(item, feedUrl);
            String nativeId = text(item, atom ? "id" : "guid", ns);
            String url = atom ? atomLink(item, feedUrl) : safeLink(text(item, "link", null), base);
            Element content = atom ? child(item, "content", ATOM) : child(item, "encoded", CONTENT);
            if (content == null || (atom && !content.getAttribute("src").isBlank())) content = child(item, atom ? "summary" : "description", ns);
            String html = content == null ? "" : cleanHtml(contentValue(content, atom), base(content, feedUrl));
            String summary = truncate(Jsoup.parseBodyFragment(html).text(), 600);
            String title = truncate(plain(text(item, "title", ns)), 1000);
            if (title.isBlank()) title = truncate(summary, 100);
            if (title.isBlank()) continue; // 无可读内容的空条目不占据用户列表。
            String date = atom ? text(item, "published", ATOM) : first(text(item, "pubDate", null), text(item, "date", DC));
            String type = !nativeId.isBlank() ? "NATIVE" : !url.isBlank() ? "URL" : "FINGERPRINT";
            String identity = switch (type) {
                case "NATIVE" -> nativeId;
                case "URL" -> canonical(url);
                default -> title + "\n" + date + "\n" + summary;
            };
            String author = atom ? text(child(item, "author", ATOM), "name", ATOM)
                    : first(text(item, "creator", DC), text(item, "author", null));
            var image = Jsoup.parseBodyFragment(html).selectFirst("img[src]");
            var entry = new FeedModels.Entry(type, hash(identity), truncate(nativeId, 2048), url, title,
                    truncate(plain(author), 256), summary, html, date(date), date(text(item, "updated", ATOM)),
                    hash(title + "\n" + html), image == null ? "" : image.attr("src"));
            entries.putIfAbsent(type + entry.identityHash(), entry);
        }
        return new FeedModels.Feed(truncate(plain(text(container, "title", ns)), 256),
                atom ? atomLink(container, feedUrl) : safeLink(text(container, "link", null), base(container, feedUrl)),
                truncate(plain(text(container, atom ? "subtitle" : "description", ns)), 1000), List.copyOf(entries.values()));
    }

    /** 只读取网页头部声明的订阅候选，不自动扫描站点或抓取候选。 */
    public List<FeedModels.Candidate> discover(byte[] bytes, String pageUrl) {
        if (bytes.length > SafeXml.MAX_BYTES) throw new FeedException(FeedException.Kind.LIMIT);
        try {
            var page = Jsoup.parse(new ByteArrayInputStream(bytes), null, pageUrl);
            var candidates = new LinkedHashMap<String, FeedModels.Candidate>();
            for (var link : page.head().select("link[rel=alternate][href]")) {
                String type = link.attr("type").toLowerCase(Locale.ROOT);
                if (!type.equals("application/rss+xml") && !type.equals("application/atom+xml")) continue;
                try {
                    String url = canonical(PublicNetworkPolicy.requireHttps(link.absUrl("href")).toString());
                    candidates.putIfAbsent(url, new FeedModels.Candidate(url, truncate(link.attr("title"), 256)));
                } catch (FeedException ignored) { /* 非法候选不影响同页的其他订阅声明。 */ }
                if (candidates.size() == 10) break;
            }
            return List.copyOf(candidates.values());
        } catch (java.io.IOException exception) { throw new FeedException(FeedException.Kind.FORMAT); }
    }

    /** 清洗 HTML 并将相对资源地址转为绝对地址。 */
    public static String cleanHtml(String input, String baseUrl) {
        if (input.length() > 200_000) throw new FeedException(FeedException.Kind.LIMIT);
        String cleaned = Jsoup.clean(input, baseUrl, HTML, new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        var document = Jsoup.parseBodyFragment(cleaned, baseUrl);
        for (var element : document.select("a[href],img[src],blockquote[cite],q[cite]")) {
            String attribute = element.tagName().equals("img") ? "src" : element.tagName().equals("a") ? "href" : "cite";
            String link = safeLink(element.attr(attribute), baseUrl);
            if (link.isBlank()) element.removeAttr(attribute);
            else element.attr(attribute, link);
        }
        document.outputSettings().prettyPrint(false);
        return document.body().html();
    }

    /** 规范化身份 URL，保留路径大小写和查询参数的原始顺序及转义。 */
    public static String canonical(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String authority = host + (port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80) ? "" : ":" + port);
        String path = uri.getRawPath();
        return scheme + "://" + authority + (path == null || path.isEmpty() ? "/" : path)
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    /** 返回固定长度内容摘要，用于唯一身份和正文版本。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    /** 按 Unicode 码点截断，防止破坏补充字符。 */
    public static String truncate(String value, int max) {
        return value.codePointCount(0, value.length()) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static String contentValue(Element content, boolean atom) {
        if (!atom || content.getAttribute("type").equals("html")) return content.getTextContent();
        if (!content.getAttribute("type").equals("xhtml")) return "<p>" + Entities.escape(content.getTextContent()) + "</p>";
        try {
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            for (Node node = content.getFirstChild(); node != null; node = node.getNextSibling()) transformer.transform(new DOMSource(node), new StreamResult(output));
            var html = Jsoup.parseBodyFragment(output.toString());
            for (var element : html.getAllElements()) {
                String name = element.tagName();
                if (name.contains(":")) element.tagName(name.substring(name.indexOf(':') + 1));
            }
            return html.body().html();
        } catch (Exception exception) { throw new FeedException(FeedException.Kind.FORMAT); }
    }

    private static String atomLink(Element item, String baseUrl) {
        for (Element link : children(item, "link", ATOM)) {
            if (link.getAttribute("rel").isEmpty() || link.getAttribute("rel").equals("alternate")) return safeLink(link.getAttribute("href"), base(link, baseUrl));
        }
        return "";
    }

    private static String safeLink(String input, String baseUrl) {
        if (input.isBlank()) return "";
        try {
            URI uri = URI.create(baseUrl).resolve(input.strip());
            if (uri.toString().length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            return uri.toString();
        } catch (IllegalArgumentException exception) { return ""; }
    }

    private static String base(Element element, String fallback) {
        var ancestors = new ArrayList<Element>();
        for (Node node = element; node instanceof Element current; node = node.getParentNode()) ancestors.addFirst(current);
        URI value = URI.create(fallback);
        for (Element ancestor : ancestors) {
            String declared = ancestor.getAttributeNS(XMLConstants.XML_NS_URI, "base");
            if (!declared.isBlank()) {
                try { value = value.resolve(declared); } catch (IllegalArgumentException ignored) { /* 忽略无效基址。 */ }
            }
        }
        return value.toString();
    }

    private static Element child(Element parent, String name, String namespace) {
        var values = children(parent, name, namespace);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static List<Element> children(Element parent, String name, String namespace) {
        var result = new ArrayList<Element>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())
                    && java.util.Objects.equals(namespace, element.getNamespaceURI())) result.add(element);
        }
        return result;
    }

    private static String text(Element parent, String name, String namespace) {
        Element value = child(parent, name, namespace);
        return value == null ? "" : value.getTextContent().strip();
    }

    private static String plain(String value) { return Jsoup.parseBodyFragment(value).text(); }
    private static String first(String a, String b) { return a.isBlank() ? b : a; }
    private static Instant date(String value) {
        if (value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) {
            try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (DateTimeParseException invalid) { return null; }
        }
    }
}
