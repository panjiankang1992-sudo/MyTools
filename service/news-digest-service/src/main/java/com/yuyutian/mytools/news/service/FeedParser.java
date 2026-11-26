package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.ArticleInput;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** 有界 RSS/Atom 解析器，禁止外部实体、DTD 和隐式网络访问。 */
@Component
public class FeedParser {
    /** 解析订阅条目，缺失发布日期的条目保留待核验，不能使用抓取时间替代。 */
    public ParsedFeed parse(byte[] bytes, String feedUrl) {
        if (bytes.length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            boolean atom = "feed".equals(root.getLocalName());
            if (!atom && !"rss".equals(root.getLocalName()) && !"RDF".equals(root.getLocalName())) {
                // HTTP 成功但返回 HTML 错误页不算有效订阅。
                throw new NewsException(ErrorCode.NEWS_007);
            }
            var nodes = root.getElementsByTagNameNS("*", atom ? "entry" : "item");
            if (nodes.getLength() > 500) throw new NewsException(ErrorCode.NEWS_007);
            List<ArticleInput> articles = new ArrayList<>();
            int skipped = 0;
            for (int index = 0; index < nodes.getLength(); index++) {
                Element item = (Element) nodes.item(index);
                String title = ContentIdentity.plainText(child(item, "title"));
                String link = atom ? atomLink(item) : child(item, "link");
                if (title.isBlank() || link.isBlank()) { skipped++; continue; }
                try {
                    String url = ContentIdentity.canonicalUrl(URI.create(feedUrl).resolve(link).toString());
                    if (url.length() > 2048) { skipped++; continue; }
                    String evidence = atom ? first(child(item, "content"), child(item, "summary"))
                            : first(child(item, "encoded"), child(item, "description"));
                    // Atom 的 updated 不能证明首次发布时间，因此不作为 published 的替代值。
                    String date = atom ? child(item, "published") : first(child(item, "pubDate"), child(item, "date"));
                    articles.add(new ArticleInput(url, ContentIdentity.truncate(title, 1000), parseDate(date),
                            ContentIdentity.truncate(ContentIdentity.plainText(evidence), 20000), 0));
                } catch (IllegalArgumentException | NewsException exception) {
                    skipped++; // 单条非法链接不使其余有效条目丢失。
                }
            }
            return new ParsedFeed(List.copyOf(articles), skipped);
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new NewsException(ErrorCode.NEWS_007);
        }
    }

    private String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element.getTextContent().strip();
        }
        return "";
    }

    private String atomLink(Element parent) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "link".equals(element.getLocalName())
                    && (element.getAttribute("rel").isEmpty() || element.getAttribute("rel").equals("alternate"))) {
                return element.getAttribute("href");
            }
        }
        return "";
    }

    private Instant parseDate(String value) {
        if (value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) {
            try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (DateTimeParseException invalid) { return null; }
        }
    }

    private String first(String preferred, String fallback) { return preferred.isBlank() ? fallback : preferred; }

    /** 解析计数用于观察坏条目，而非静默忽略来源质量问题。 */
    public record ParsedFeed(List<ArticleInput> articles, int skipped) { }
}
