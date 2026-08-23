package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.dsh.model.DshModels;
import com.yuyutian.mytools.dsh.service.DshSessionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DSH图书探测词解析测试。
 */
class BookSourceProbeQueryServiceTest {

    /**
     * 验证标记内的搜索词会去重并限制数量。
     */
    @Test
    void extractsBoundedDistinctProbeTerms() {
        BookSourceProbeQueryService service = new BookSourceProbeQueryService(mock(DshSessionService.class),
                new ObjectMapper());
        DshModels.Message message = new DshModels.Message("1", 1, 1, "assistant",
                "MYTOOLS_PROBE_TERMS_BEGIN\n[\"hero\",\"hero\",\"lost prince\"]\nMYTOOLS_PROBE_TERMS_END",
                "completed");

        assertThat(service.extract(new DshModels.History(List.of(message), List.of(), false, 1)))
                .containsExactly("hero", "lost prince");
    }
}
