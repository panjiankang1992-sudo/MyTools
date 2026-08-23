package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 黄金屋中文站点的声明式书源适配器。
 */
@Component
public class HjwzwBookSourceSiteAdapter implements BookSourceSiteAdapter {

    /**
     * 判断目标地址是否属于黄金屋中文站点。
     *
     * @param target 目标地址
     * @return 是否支持
     */
    @Override
    public boolean supports(URI target) {
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase();
        return "hjwzw.com".equals(host) || host.endsWith(".hjwzw.com");
    }

    /**
     * 生成黄金屋中文的固定声明式规则。
     *
     * @param target 目标地址
     * @param objectMapper JSON转换器
     * @return 书源JSON
     */
    @Override
    public String createSnapshot(URI target, ObjectMapper objectMapper) {
        String origin = target.getScheme() + "://" + target.getAuthority();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("bookSourceUrl", origin);
        root.put("bookSourceName", "\u9ec3\u91d1\u5c4b\u4e2d\u6587");
        root.put("bookSourceGroup", "\u514d\u8cbb\u5c0f\u8aaa");
        root.put("bookSourceType", 0);
        root.put("enabled", true);
        root.put("enabledExplore", true);
        root.put("searchUrl", origin + "/List/{{key}}");
        root.put("exploreUrl", origin + "/index.html");
        root.put("header", "");
        ObjectNode search = root.putObject("ruleSearch");
        search.put("bookList", "a[href^='/Book/']");
        search.put("name", "text");
        search.put("bookUrl", "href");
        root.set("ruleExplore", search.deepCopy());
        ObjectNode info = root.putObject("ruleBookInfo");
        info.put("name", "meta[property='og:title']@content");
        info.put("author", "meta[property='og:novel:author']@content");
        info.put("kind", "meta[property='og:novel:category']@content");
        info.put("intro", "meta[property='og:description']@content");
        info.put("coverUrl", "meta[property='og:image']@content");
        info.put("tocUrl", "a[href*='Chapter']@href");
        ObjectNode toc = root.putObject("ruleToc");
        toc.put("chapterList", "#tbchapterlist a");
        toc.put("chapterName", "text");
        toc.put("chapterUrl", "href");
        ObjectNode content = root.putObject("ruleContent");
        content.put("content", "div[style*='text-indent: 2em']@html");
        content.put("nextContentUrl", "");
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.READER_007);
        }
    }
}
