package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import static org.assertj.core.api.Assertions.*;

class PublicNetworkPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.0.0.1", "100.64.0.1", "169.254.169.254", "192.168.1.1",
            "172.16.0.1", "198.18.0.1", "192.0.2.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "::1", "fc00::1",
            "fe80::1", "2001:db8::1", "2002:7f00:1::1", "::ffff:127.0.0.1", "192.0.0.1", "192.0.0.170", "192.0.2.255", "192.88.99.1"})
    void rejectsNonPublicAddresses(String literal) throws UnknownHostException {
        assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(literal))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "192.0.78.24", "192.0.78.25"})
    void acceptsPublicAddressFamilies(String literal) throws UnknownHostException {
        assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/rss", "https://user:secret@example.com/rss", "https://localhost/rss",
            "https://example.com:8443/rss", "file:///etc/passwd", "https://example.com/rss#fragment", "https://127.0.0.1/rss"})
    void rejectsUnsafeSourceUrls(String url) {
        assertThatThrownBy(() -> PublicNetworkPolicy.requireHttps(url)).isInstanceOf(NewsException.class);
    }

    @Test
    void preservesEncodedPathsAndMeaningfulQueryOrder() {
        assertThat(ContentIdentity.canonicalUrl("https://EXAMPLE.com:443/a%2Fb?id=2&id=1&utm_medium=email#section"))
                .isEqualTo("https://example.com/a%2Fb?id=2&id=1");
        assertThat(ContentIdentity.truncate("A\uD83D\uDE00B", 2)).isEqualTo("A\uD83D\uDE00");
    }
}
