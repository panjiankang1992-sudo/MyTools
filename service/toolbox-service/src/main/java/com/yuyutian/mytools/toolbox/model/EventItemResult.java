package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单条事件的处理结果，reason 为稳定的机器可读原因。
 *
 * @param index 批内下标
 * @param eventId 事件标识，可为空
 * @param status accepted、duplicate 或 rejected
 * @param reason 拒绝或重复原因，可为空
 */
public record EventItemResult(
        /** 批内下标。 */
        @JsonProperty("index") int index,
        /** 事件标识，可为空。 */
        @JsonProperty("eventId") String eventId,
        /** accepted、duplicate 或 rejected。 */
        @JsonProperty("status") String status,
        /** 拒绝或重复原因，可为空。 */
        @JsonProperty("reason") String reason) {
}
