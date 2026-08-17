package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DshEventProjectorTest {

    @Test
    void shouldExposeVisibleMessagesWithoutSystemPromptOrReasoning() throws Exception {
        var value = new ObjectMapper().readTree("""
                {"events":[
                  {"event":{"type":"user/message","seq":1,"time":10,"data":{"id":"system",\
                    "source":{"kind":"plugin"},"content":[{"type":"text","text":"secret system"}]}}},
                  {"event":{"type":"user/message","seq":2,"time":20,"data":{"id":"user-1",\
                    "source":{"kind":"user"},"content":[{"type":"text","text":"hello"}]}}},
                  {"event":{"type":"assistant/message","seq":3,"time":30,"data":{"message":{"id":"a-1",\
                    "content":[{"type":"reasoning","text":"hidden"},{"type":"text","text":"answer"}]}}}},
                  {"event":{"type":"turn/end","seq":4,"time":40,"data":{}}}
                ],"hasMore":false}
                """);

        var history = new DshEventProjector().history(value);

        assertThat(history.messages()).extracting(message -> message.text()).containsExactly("hello", "answer");
        assertThat(history.steps()).extracting(step -> step.type()).containsExactly("turn/end");
        assertThat(history.lastSeq()).isEqualTo(4L);
    }
}
