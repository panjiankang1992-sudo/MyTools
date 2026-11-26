package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.service.IllustratedReportCollector;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;

class IllustratedReportCollectorTest {
    @Test void missingBodyPrecedesImagesAndEvidenceReachesAnalysisInput(@org.junit.jupiter.api.io.TempDir java.nio.file.Path output) throws Exception {
        var source = Fixtures.source("news", com.yuyutian.mytools.news.Model.NewsModels.Category.TECH);
        var articles = java.util.List.of(Fixtures.article("known", source, Fixtures.CUTOFF.minusSeconds(20), Fixtures.EVIDENCE),
                Fixtures.article("missing", source, Fixtures.CUTOFF.minusSeconds(40), ""));
        var engine = new com.yuyutian.mytools.news.service.DigestEngine();
        var edition = engine.build(java.util.UUID.randomUUID(), 1, Fixtures.CUTOFF, Fixtures.CUTOFF, java.time.ZoneId.of("UTC"),
                com.yuyutian.mytools.news.Model.NewsModels.RegionSnapshot.unresolved(), articles);
        java.util.List<String> requests = new java.util.ArrayList<>();
        var collector = new IllustratedReportCollector(state -> {
            String url = state.spec().feedUrl(); requests.add(url);
            String html = url.endsWith(".jpg") ? "bad image" : "<article>" + Fixtures.EVIDENCE + "<img src='/photo.jpg'></article>";
            return new com.yuyutian.mytools.news.service.FeedTransport.FeedResponse(200, html.getBytes(StandardCharsets.UTF_8), null, null);
        });
        var report = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(collector.collect(engine.buildCandidates(edition, articles), articles, output));
        var tech = report.path("sections").get(0);
        assertThat(requests.getFirst()).endsWith("/missing");
        assertThat(requests.subList(0, 2)).noneMatch(url -> url.endsWith(".jpg"));
        assertThat(tech.path("contentAcquisition").path("recovered").asInt()).isEqualTo(1);
        assertThat(tech.path("contentAcquisition").path("unavailable").asInt()).isZero();
        for (var item : tech.path("items")) {
            assertThat(item.path("detailAvailable").asBoolean()).isTrue();
            assertThat(item.path("evidence").asText()).contains("verified source paragraph");
            assertThat(item.path("contentAcquisition").path("attempts")).hasSize(1);
        }
    }

    @Test void originalImageUsesArticleContextAndRejectsInjectedSchemesAndTrackingPixels() {
        String page = "<article><img src='data:image/png;base64,abc'><img src='https://127.0.0.1/a.png'>"
                + "<img width='1' src='https://example.com/pixel.png'><img src='/photo.jpg' alt='Actual &amp; caption'></article>";
        var image = IllustratedReportCollector.extractImage(page.getBytes(StandardCharsets.UTF_8), "https://example.com/story");
        assertThat(image).containsEntry("url", "https://example.com/photo.jpg").containsEntry("caption", "Actual & caption");
        assertThat(IllustratedReportCollector.extractImage("<meta property='og:image' content='https://example.com/logo.png'>".getBytes(StandardCharsets.UTF_8), "https://example.com/story")).isEmpty();
    }

    @Test void legacyImageLinksUpgradeToHttpsAndChallengePagesDoNotProvidePhotos() {
        String image = "<meta property='og:image' content='http://images.example.com/story.jpg'>";
        assertThat(IllustratedReportCollector.extractImage(image.getBytes(StandardCharsets.UTF_8), "https://example.com/story"))
                .containsEntry("url", "https://images.example.com/story.jpg");
        assertThat(IllustratedReportCollector.extractImage(("<title>Access denied</title>" + image).getBytes(StandardCharsets.UTF_8), "https://example.com/story")).isEmpty();
    }

    @Test void jschinaImageComesFromBodyInsteadOfSidebarPromotion() {
        String html = "<div class='content_right'><img src='/promotion.jpg'></div>"
                + "<div id='content' class='articalCont'><figure><img src='https://jcdn.xhby.net/photo.jpg'></figure></div>";
        assertThat(IllustratedReportCollector.extractImage(html.getBytes(StandardCharsets.UTF_8),
                "https://jsnews.jschina.com.cn/jsyw/202610/t20261019_s123.shtml"))
                .containsEntry("url", "https://jcdn.xhby.net/photo.jpg");
        assertThat(IllustratedReportCollector.extractImage(html.getBytes(StandardCharsets.UTF_8), "https://example.com/story")).isEmpty();
    }

    @Test void mimeDetectionRejectsHtmlAndTinyImagesButAcceptsRealNewsPhotos() throws Exception {
        assertThat(IllustratedReportCollector.imageFormat("<html>Not a photo</html>".getBytes(StandardCharsets.UTF_8))).isNull();
        for (int size : new int[]{1, 240}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", output);
            assertThat(IllustratedReportCollector.imageFormat(output.toByteArray())).isEqualTo(size == 1 ? null : "png");
        }
    }
}
