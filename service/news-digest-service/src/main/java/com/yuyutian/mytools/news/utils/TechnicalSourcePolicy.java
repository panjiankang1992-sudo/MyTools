package com.yuyutian.mytools.news.utils;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import java.time.Duration;

/** 专用 API 只能登记固定官方入口，子请求路径由适配器构造。 */
public final class TechnicalSourcePolicy {
    private TechnicalSourcePolicy() { }

    /** 验证技术来源协议和入口，避免把带凭据的适配器指向任意域名。 */
    public static void validate(SourceSpec source) {
        PublicNetworkPolicy.requireHttps(source.feedUrl());
        if (source.kind() == SourceKind.RSS_ATOM) return;
        if (source.kind() == SourceKind.BAIDU_HOT || source.kind() == SourceKind.CCTV_MILITARY) {
            // 网页协议绑定已核对入口，不能被用作任意页面解析器。
            boolean hot = source.kind() == SourceKind.BAIDU_HOT;
            if (source.category() != (hot ? Category.TRENDING : Category.MILITARY)
                    || !(hot ? "https://top.baidu.com/board?tab=realtime" : "https://military.cctv.com/data/index.json").equals(source.feedUrl()))
                throw new NewsException(ErrorCode.NEWS_002);
            return;
        }
        if (source.kind() == SourceKind.CHINANEWS_JIANGSU) {
            // 本地网页适配器只解析已核对的江苏频道，不允许任意 HTML 入口套用时间规则。
            if (source.category() != Category.LOCAL || !"CN-JS".equals(source.regionCode())
                    || !"https://www.js.chinanews.com.cn/".equals(source.feedUrl())) throw new NewsException(ErrorCode.NEWS_002);
            return;
        }
        if (source.kind() == SourceKind.JSCHINA_JIANGSU) {
            // 江苏要闻的模板与时间格式经过单独核对，限制为同一省级入口。
            if (source.category() != Category.LOCAL || !"CN-JS".equals(source.regionCode())
                    || !"https://jsnews.jschina.com.cn/jsyw/".equals(source.feedUrl())) throw new NewsException(ErrorCode.NEWS_002);
            return;
        }
        String expected = switch (source.kind()) {
            case HACKER_NEWS -> "https://hacker-news.firebaseio.com/v0/topstories.json";
            case DEV -> "https://dev.to/api/articles";
            case GITHUB -> "https://api.github.com/search/repositories";
            default -> throw new NewsException(ErrorCode.NEWS_002);
        };
        if (source.category() != Category.TECH || !expected.equals(source.feedUrl())) throw new NewsException(ErrorCode.NEWS_002);
    }

    /** 使用保守的最小采集间隔，持续调度后仍由数据库执行冷却门禁。 */
    public static Duration interval(SourceKind kind) {
        return switch (kind) {
            case GITHUB -> Duration.ofHours(6);
            case DEV -> Duration.ofHours(1);
            case HACKER_NEWS -> Duration.ofMinutes(15);
            case RSS_ATOM -> Duration.ZERO;
            case CHINANEWS_JIANGSU, JSCHINA_JIANGSU, BAIDU_HOT, CCTV_MILITARY -> Duration.ofMinutes(30);
        };
    }
}
