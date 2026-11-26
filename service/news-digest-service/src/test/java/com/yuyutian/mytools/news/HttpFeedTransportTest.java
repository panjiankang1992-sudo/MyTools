package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.HttpFeedTransport;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.DeflaterOutputStream;
import static org.assertj.core.api.Assertions.*;

class HttpFeedTransportTest {
    @Test void gzipAndDeflateAreDecodedBeforeHtmlExtraction() throws Exception {
        byte[] body = ("<article>" + Fixtures.EVIDENCE + "</article>").getBytes(StandardCharsets.UTF_8);
        for (String encoding : new String[]{"gzip", "deflate"}) {
            assertThat(HttpFeedTransport.decodeContent(compress(body, encoding), encoding, Long.MAX_VALUE)).isEqualTo(body);
        }
        assertThat(HttpFeedTransport.decodeContent(body, null, Long.MAX_VALUE)).isSameAs(body);
    }
    @Test void decompressionRejectsExpansionTruncationAndExpiredDeadline() throws Exception {
        byte[] large = compress(new byte[2 * 1024 * 1024 + 1], "gzip");
        assertThatThrownBy(() -> HttpFeedTransport.decodeContent(large, "gzip", Long.MAX_VALUE)).isInstanceOf(NewsException.class);
        byte[] valid = compress("original content".getBytes(StandardCharsets.UTF_8), "gzip");
        assertThatThrownBy(() -> HttpFeedTransport.decodeContent(valid, "gzip", 0)).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> HttpFeedTransport.decodeContent(java.util.Arrays.copyOf(valid, valid.length - 5), "gzip", Long.MAX_VALUE)).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> HttpFeedTransport.decodeContent(valid, "br", Long.MAX_VALUE)).isInstanceOf(NewsException.class);
    }
    private byte[] compress(byte[] bytes, String encoding) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var compressor = encoding.equals("gzip") ? new GZIPOutputStream(output) : new DeflaterOutputStream(output)) { compressor.write(bytes); }
        return output.toByteArray();
    }
}
