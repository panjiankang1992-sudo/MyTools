package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.service.NewsDeliveryRenderer;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NewsDeliveryRendererTest {
    private final ObjectMapper json=new ObjectMapper();
    private final NewsDeliveryRenderer renderer=new NewsDeliveryRenderer(json,new DeliveryProperties(true,"http://127.0.0.1:23250","t".repeat(32),"https://example.test/news"));
    @Test void fullModeKeepsEightyHeadlinesAndFortyOriginalExcerpts() {
        Edition edition=edition("event",Fixtures.EVIDENCE);
        var parts=renderer.render(edition,Channel.EMAIL,RenderMode.FULL);
        String text=String.join("\n",parts.stream().map(p->p.content().text()).toList());
        for(Category c:Category.values())for(int rank=1;rank<=20;rank++)assertThat(text).contains("event-"+c+"-"+rank+"|");
        assertThat(text.split("\u6458\u8981\uff1a",-1)).hasSize(Category.values().length * 10 + 1);
        assertThat(text).contains("https://example.test/news?edition="+edition.id());
        assertThat(text).doesNotContain("Quality:","Citation:","hash-1","Coverage gaps:");
        assertThat(parts.getFirst().content().subject()).startsWith("\u6bcf\u65e5\u65b0\u95fb\u901f\u62a5");
        assertThat(parts).allMatch(p->p.payloadHash().equals(NewsDeliveryRenderer.hash(p.content())));
        assertThat(renderer.render(edition,Channel.EMAIL,RenderMode.FULL)).isEqualTo(parts);
    }
    @Test void briefModeLabelsOmissionsAndNeverInventsDetails() {
        var parts=renderer.render(edition("event",Fixtures.EVIDENCE),Channel.FEISHU,RenderMode.BRIEF);
        String text=String.join("\n",parts.stream().map(p->p.content().text()).toList());
        assertThat(text.split("\u6458\u8981\uff1a",-1)).hasSize(Category.values().length * 3 + 1);
        assertThat(text.split("\u8be6\u60c5\u89c1\u5b8c\u6574\u65e5\u62a5\u3002",-1)).hasSize(Category.values().length * 7 + 1);
        assertThat(parts).allMatch(p->p.content().html()==null);
    }
    @Test void multibyteQuotesAndMentionEscapingFitActualProviderJsonBudget() throws Exception {
        var parts=renderer.render(edition("\u65b0\u95fb\ud83d\ude80\"<".repeat(80),Fixtures.EVIDENCE),Channel.FEISHU,RenderMode.FULL);
        assertThat(parts.size()).isGreaterThan(1);
        for(RenderedPart part:parts) {
            String text=(part.content().subject()+"\n\n"+part.content().text()).replace('<','\u2039').replace('>','\u203a');
            byte[] bytes=json.writeValueAsBytes(Map.of("msg_type","text","content",Map.of("text",text),"timestamp","9999999999","sign","x".repeat(44)));
            assertThat(bytes.length).isLessThanOrEqualTo(14000);
            assertThat(new String(part.content().text().getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8)).isEqualTo(part.content().text());
            assertThat(part.count()).isEqualTo(parts.size());
        }
    }
    @Test void htmlEscapesUntrustedTextAndOnlyRendersSafeLinks() {
        var parts=renderer.render(edition("<img src=x onerror=alert(1)>",Fixtures.EVIDENCE),Channel.EMAIL,RenderMode.FULL);
        String html=parts.getFirst().content().html();
        assertThat(html).doesNotContain("<img", "<script").contains("&lt;img", "href=\"https://example.test/news?");
        assertThat(NewsDeliveryRenderer.safeUrl("javascript:alert(1)")).isFalse();
        assertThat(NewsDeliveryRenderer.safeUrl("https://user:secret@example.test/path")).isFalse();
        assertThat(NewsDeliveryRenderer.safeUrl("//example.test/path")).isFalse();
    }
    @Test void unsupportedQqAndOversizedAtomicLineAreExplicitFailures() {
        assertThatThrownBy(()->renderer.render(edition("event",Fixtures.EVIDENCE),Channel.QQ,RenderMode.FULL)).isInstanceOf(com.yuyutian.mytools.news.common.NewsException.class);
        assertThatThrownBy(()->renderer.render(edition("x".repeat(16000),Fixtures.EVIDENCE),Channel.FEISHU,RenderMode.FULL)).isInstanceOf(com.yuyutian.mytools.news.common.NewsException.class);
    }
    static Edition edition(String title,String detail) {
        Map<Category,Section> sections=new EnumMap<>(Category.class);
        for(Category category:Category.values()) {
            List<RankedItem> items=new ArrayList<>();
            for(int rank=1;rank<=20;rank++) {
                String url="https://example.com/"+category+"/"+rank;
                items.add(new RankedItem(rank,category+"-"+rank,title+"-"+category+"-"+rank+"|",url,Fixtures.CUTOFF.minusSeconds(60),List.of("fixture"),
                        new Score(1,1,1,1,1),rank<=10?DetailStatus.AVAILABLE:DetailStatus.HEADLINE_ONLY,
                        rank<=10?new Detail(detail,"SOURCE_EXCERPT_V1",List.of(new Citation("a-"+rank,url,"fixture","hash-"+rank,Fixtures.CUTOFF))):null,null));
            }
            sections.put(category,new Section(category,Fixtures.CUTOFF.minusSeconds(category==Category.TECH?604800:86400),Fixtures.CUTOFF,20,20,10,Quality.COMPLETE,List.of(),items));
        }
        return new Edition(UUID.randomUUID(),55,Fixtures.CUTOFF,Fixtures.CUTOFF,"Asia/Shanghai","fixture",RegionSnapshot.unresolved(),Quality.COMPLETE,sections);
    }
}
