package com.yuyutian.mytools.feed;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 仅转换订阅清单，不执行 OPML 中的 URL、脚本或外部包含。 */
public final class OpmlCodec {
    /** 读取最多二百个候选，嵌套分组转为可预览的路径。 */
    public List<FeedModels.Outline> parse(byte[] bytes) {
        Element root = SafeXml.parse(bytes).getDocumentElement();
        if (!root.getTagName().equals("opml")) throw new FeedException(FeedException.Kind.FORMAT);
        var output = new ArrayList<FeedModels.Outline>();
        collect(root, "", output);
        return List.copyOf(output);
    }

    private void collect(Element parent, String group, List<FeedModels.Outline> output) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) continue;
            String next = group;
            if (element.getTagName().equals("outline")) {
                String title = element.hasAttribute("title") ? element.getAttribute("title") : element.getAttribute("text");
                if (element.hasAttribute("xmlUrl")) {
                    output.add(new FeedModels.Outline(element.getAttribute("xmlUrl"), FeedParser.truncate(title, 256), group));
                    if (output.size() > 200) throw new FeedException(FeedException.Kind.LIMIT);
                } else if (!title.isBlank()) next = FeedParser.truncate(group.isBlank() ? title : group + " / " + title, 80);
            }
            collect(element, next, output);
        }
    }

    /** 使用 XML 写入器转义标题与 URL，导出当前用户的有效订阅。 */
    public String write(List<FeedModels.Outline> outlines) {
        try {
            var groups = new LinkedHashMap<String, List<FeedModels.Outline>>();
            for (var outline : outlines) groups.computeIfAbsent(outline.group(), ignored -> new ArrayList<>()).add(outline);
            var output = new StringWriter();
            var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("opml"); writer.writeAttribute("version", "2.0");
            writer.writeStartElement("head"); writer.writeStartElement("title"); writer.writeCharacters("MyTools RSS"); writer.writeEndElement(); writer.writeEndElement();
            writer.writeStartElement("body");
            for (var group : groups.entrySet()) {
                boolean nested = group.getKey() != null && !group.getKey().isBlank();
                if (nested) { writer.writeStartElement("outline"); writer.writeAttribute("text", group.getKey()); }
                for (var outline : group.getValue()) {
                    writer.writeEmptyElement("outline"); writer.writeAttribute("type", "rss");
                    writer.writeAttribute("text", outline.title()); writer.writeAttribute("title", outline.title()); writer.writeAttribute("xmlUrl", outline.url());
                }
                if (nested) writer.writeEndElement();
            }
            writer.writeEndElement(); writer.writeEndElement(); writer.writeEndDocument(); writer.close();
            return output.toString();
        } catch (javax.xml.stream.XMLStreamException exception) { throw new FeedException(FeedException.Kind.FORMAT); }
    }
}
