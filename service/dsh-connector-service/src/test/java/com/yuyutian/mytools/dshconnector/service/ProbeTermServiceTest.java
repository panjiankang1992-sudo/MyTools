package com.yuyutian.mytools.dshconnector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.dshconnector.config.DshConnectorProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 探测词结果解析测试。
 */
class ProbeTermServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProbeTermService service = new ProbeTermService(
            mock(DshRpcClient.class), new DshConnectorProperties(), objectMapper);

    /**
     * 验证只提取最后一个完整标记中的有界去重词集。
     *
     * @throws Exception JSON 构造失败
     */
    @Test
    void extractsLastBoundedDistinctTermSet() {
        JsonNode history = history(
                "MYTOOLS_PROBE_TERMS_BEGIN\n[\"old term\"]\nMYTOOLS_PROBE_TERMS_END",
                "MYTOOLS_PROBE_TERMS_BEGIN\n[\"hero\",\"hero\",\"lost prince\",\"xy\","
                        + "\"third term\",\"fourth term\",\"ignored term\"]\nMYTOOLS_PROBE_TERMS_END");

        assertThat(service.extract(history))
                .containsExactly("hero", "lost prince", "xy", "third term", "fourth term");
    }

    /**
     * 验证不完整或非法响应不会泄漏为搜索词。
     *
     */
    @Test
    void rejectsIncompleteOrInvalidTermSet() {
        JsonNode incomplete = history("MYTOOLS_PROBE_TERMS_BEGIN [\"hero\"]");
        JsonNode invalid = history("MYTOOLS_PROBE_TERMS_BEGIN [1,true,null] MYTOOLS_PROBE_TERMS_END");

        assertThat(service.extract(incomplete)).isEmpty();
        assertThat(service.extract(invalid)).isEqualTo(List.of());
    }

    private JsonNode history(String... messages) {
        ObjectNode history = objectMapper.createObjectNode();
        ArrayNode events = history.putArray("events");
        for (String message : messages) {
            ObjectNode event = events.addObject().putObject("event");
            event.put("type", "assistant/message");
            event.putObject("data").putObject("message").putArray("content")
                    .addObject().put("type", "text").put("text", message);
        }
        return history;
    }
}
