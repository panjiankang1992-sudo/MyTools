package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.service.FeedParser;
import com.yuyutian.mytools.news.service.HttpFeedTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅显式启用时探测公开订阅，不写数据库、不打印正文、不发送消息。 */
@EnabledIfSystemProperty(named = "news.liveProbe", matches = "true")
class LiveFeedSmokeTest {
    @Test
    void publicFeedIsRetrievedThroughRestrictedProductionTransport() {
        SourceSpec spec = new SourceSpec("cn-world-probe", "CN world public RSS",
                "https://www.chinanews.com.cn/rss/world.xml", Category.WORLD, "chinanews", null,
                EvidencePolicy.METADATA_ONLY, "https://www.chinanews.com.cn/rss/", 0.8, true);
        var response = new HttpFeedTransport().fetch(new SourceState(spec, null, null, null, null, "NEVER_FETCHED"));
        assertThat(response.status()).isEqualTo(200);
        var parsed = new FeedParser().parse(response.body(), spec.feedUrl());
        assertThat(parsed.articles()).isNotEmpty();
        assertThat(parsed.articles()).allMatch(article -> article.url().startsWith("https://") || article.url().startsWith("http://"));
    }
}
