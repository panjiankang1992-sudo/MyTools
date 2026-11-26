package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 条目来源定位，指向内部来源记录中的具体位置。
 *
 * @param sourceId 来源记录标识
 * @param locator 来源内的定位描述
 * @param page 来源页码，可为空
 */
public record EntrySourceRef(
        /** 来源记录标识。 */
        @JsonProperty("sourceId") String sourceId,
        /** 来源内的定位描述。 */
        @JsonProperty("locator") String locator,
        /** 来源页码，可为空。 */
        @JsonProperty("page") Integer page) {
}
