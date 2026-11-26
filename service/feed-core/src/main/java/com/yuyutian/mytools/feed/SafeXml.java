package com.yuyutian.mytools.feed;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.helpers.DefaultHandler;

/** 为 Feed 与 OPML 统一提供有界、禁止隐式联网的 XML 解析。 */
public final class SafeXml {
    /** 原始 XML 与解压内容共同采用的字节上限。 */
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private SafeXml() { }
    /** 解析受限 XML，任何 DTD、实体或过深结构均拒绝。 */
    public static Document parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new FeedException(FeedException.Kind.LIMIT);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "100");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new FeedException(FeedException.Kind.FORMAT);
        }
    }
}
