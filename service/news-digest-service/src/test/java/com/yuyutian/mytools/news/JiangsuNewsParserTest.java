package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.JiangsuNewsParser;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import org.junit.jupiter.api.Test;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class JiangsuNewsParserTest {
    private static final String URL="https://www.js.chinanews.com.cn/";
    @Test void readsGbkListWithExactPublisherTimestampAndIgnoresNavigation() {
        String html="<meta charset='gb2312'><nav><a href='/other'>Other content</a></nav>"+entry("/news/2026/1018/235682.html","2026-10-18 15:19:32");
        var result=JiangsuNewsParser.parse(html.getBytes(Charset.forName("GB18030")),URL);
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().title()).isEqualTo("\u5357\u4eac\u672c\u5730\u65b0\u95fb");
        assertThat(result.articles().getFirst().url()).isEqualTo(URL+"news/2026/1018/235682.html");
        assertThat(result.articles().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-18T07:19:32Z"));
    }
    @Test void rejectsMissingDatesInvalidDatesAndForeignLinksWithoutInventingTimes() {
        String html=entry("https://unrelated.example/news/2026/1018/1.html","2026-10-18 15:19:32")
                +entry("/news/2026/1018/2.html","")+entry("/news/2026/1018/3.html","2026-02-30 12:00:00");
        var result=JiangsuNewsParser.parse(html.getBytes(StandardCharsets.UTF_8),URL);
        assertThat(result.articles()).isEmpty();assertThat(result.skipped()).isEqualTo(3);
        assertThatThrownBy(()->JiangsuNewsParser.parse("<html>Error</html>".getBytes(StandardCharsets.UTF_8),URL)).isInstanceOf(NewsException.class);
    }
    @Test void localAdapterCannotBeRegisteredForOtherSitesOrRegions() {
        TechnicalSourcePolicy.validate(source(URL,"CN-JS"));
        assertThatThrownBy(()->TechnicalSourcePolicy.validate(source("https://example.com/","CN-JS"))).isInstanceOf(NewsException.class);
        assertThatThrownBy(()->TechnicalSourcePolicy.validate(source(URL,"CN-SC"))).isInstanceOf(NewsException.class);
    }
    private SourceSpec source(String url,String region) {
        return new SourceSpec("jiangsu","Jiangsu",url,Category.LOCAL,"chinanews",region,EvidencePolicy.EXCERPT_ALLOWED,URL,0.8,true,SourceKind.CHINANEWS_JIANGSU);
    }
    private String entry(String url,String date) {
        return "<div class='jszx-item'><div class='list-tit'><a href='"+url+"'>\u5357\u4eac\u672c\u5730\u65b0\u95fb</a></div><div class='time'>"+date+"</div></div>";
    }
}
