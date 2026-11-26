package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.config.InternalTokenFilter;
import com.yuyutian.mytools.news.config.NewsProperties;
import com.yuyutian.mytools.news.config.RegionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.LocalTime;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.*;

class TokenFilterTest {
    @Test
    void regionTokenIsDistinctAndCannotLeakThroughConfigurationDiagnostics() {
        String token = "test-region-token-at-least-thirty-two-characters";
        var region = new RegionProperties("node", token, 50, true);
        assertThat(region.toString()).doesNotContain(token);
        assertThatThrownBy(() -> new InternalTokenFilter(new NewsProperties(token, "admin", false, ZoneId.of("UTC"), LocalTime.NOON), region))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(new NewsProperties("proxy", "admin", false, ZoneId.of("UTC"), LocalTime.NOON),
                new RegionProperties("node", "short", 50, true))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyConfiguredTokenDoesNotAuthorizeAnEmptyBearerHeader() throws Exception {
        var filter = new InternalTokenFilter(new NewsProperties("", "", false, ZoneId.of("Asia/Shanghai"), LocalTime.of(7, 30)));
        var request = new MockHttpServletRequest("GET", "/internal/v1/news/capabilities");
        request.addHeader("Authorization", "Bearer ");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void healthRemainsAvailableButDuplicatePrivilegeTokensPreventStartup() throws Exception {
        var filter = new InternalTokenFilter(new NewsProperties("", "", false, ZoneId.of("Asia/Shanghai"), LocalTime.of(7, 30)));
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThatThrownBy(() -> new NewsProperties("same", "same", false, ZoneId.of("UTC"), LocalTime.NOON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matrixOrEncodedPathsCannotDowngradeAdminAuthentication() throws Exception {
        var filter = new InternalTokenFilter(new NewsProperties("proxy", "admin", false, ZoneId.of("UTC"), LocalTime.NOON));
        for (String path : java.util.List.of("/internal;x/admin/news/sources", "/internal/%61dmin/news/sources",
                "/internal//admin/news/sources", "/internal/v1/../admin/news/sources")) {
            var request = new MockHttpServletRequest("GET", path);
            request.addHeader("Authorization", "Bearer proxy");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }
}
