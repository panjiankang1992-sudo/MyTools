package com.yuyutian.mytools.news.service.technical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.FeedTransport;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** JSON API 复用受限拨号器，子请求不共享索引响应的条件请求标记。 */
@Component
public class JsonSourceClient {
    private final FeedTransport transport;
    private final ObjectMapper json;

    /** 创建官方 API 客户端。 */
    public JsonSourceClient(FeedTransport transport, ObjectMapper json) { this.transport = transport; this.json = json; }

    /** 获取适配器构造的子资源，拒绝错误响应和过大的 JSON。 */
    public JsonNode get(SourceState source, String url) {
        SourceSpec spec = source.spec();
        SourceSpec request = new SourceSpec(spec.id(), spec.name(), url, spec.category(), spec.publisherKey(), spec.regionCode(),
                spec.evidencePolicy(), spec.rightsReference(), spec.authority(), spec.enabled(), spec.kind());
        FeedTransport.FeedResponse response = transport.fetch(new SourceState(request, null, null, null, null, "REQUEST"));
        if (response.status() != 200) throw new NewsException(ErrorCode.NEWS_006);
        if (response.body().length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        try {
            JsonNode value = json.readTree(response.body());
            if (value == null) throw new NewsException(ErrorCode.NEWS_007);
            return value;
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }
}
