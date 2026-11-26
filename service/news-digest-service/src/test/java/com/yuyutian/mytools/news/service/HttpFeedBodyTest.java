package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.common.NewsException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class HttpFeedBodyTest {
    @Test void rejectedResponseDoesNotDrainInputBeforeCallerCanAbortConnection() {
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[2 * 1024 * 1024 + 16384]) {
            /** 模拟关闭时会继续排空数据的 HTTP 流，调用前必须取消连接。 */
            @Override public void close() { closed.set(true); throw new AssertionError("Do not drain rejected response"); }
        };
        var entity = new BasicHttpEntity(input, -1, ContentType.APPLICATION_OCTET_STREAM);
        assertThatThrownBy(() -> HttpFeedTransport.readBody(entity, Long.MAX_VALUE)).isInstanceOf(NewsException.class);
        assertThat(closed).isFalse();
        assertThat(input.available()).isPositive();
    }
}
