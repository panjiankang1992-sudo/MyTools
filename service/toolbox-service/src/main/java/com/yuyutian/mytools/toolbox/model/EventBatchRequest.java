package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 事件批量请求体。
 *
 * @param events 事件列表
 */
public record EventBatchRequest(
        /** 事件列表。 */
        @JsonProperty("events") List<PublicUsageEvent> events) {
}
