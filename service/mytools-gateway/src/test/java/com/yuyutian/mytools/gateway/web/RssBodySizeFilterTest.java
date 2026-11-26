package com.yuyutian.mytools.gateway.web;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class RssBodySizeFilterTest {
    @Test void rejectsUnknownLengthBodyBeforeMvc() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/app/v1/rss/opml/import") {
            /** 模拟没有长度声明的分块请求。 */
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[2 * 1024 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new RssBodySizeFilter().doFilter(request, response, (input, output) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("RSS_006");
        assertThat(called).isFalse();
    }

    @Test void preservesSmallJsonAndIgnoresOtherUploads() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/v1/rss/entries/1/state");
        request.setContent("{\"read\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        new RssBodySizeFilter().doFilter(request, response, (input, output) ->
                assertThat(input.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray()));
        request.setRequestURI("/api/app/v1/video-generation/uploads/video");
        request.setContent(new byte[2 * 1024 * 1024 + 1]);
        var called = new AtomicBoolean();
        new RssBodySizeFilter().doFilter(request, response, (input, output) -> called.set(true));
        assertThat(called).isTrue();
    }
}
