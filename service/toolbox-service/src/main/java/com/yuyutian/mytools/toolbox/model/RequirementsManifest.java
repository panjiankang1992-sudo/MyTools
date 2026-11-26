package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 必需槽位清单文件 catalog/requirements.json 的顶层结构。
 *
 * @param requirementsVersion 清单版本
 * @param slots 必需槽位列表
 */
// 内容文件允许携带额外说明字段，接口请求体仍保持严格校验。
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementsManifest(
        /** 清单版本。 */
        @JsonProperty("requirementsVersion") String requirementsVersion,
        /** 必需槽位列表。 */
        @JsonProperty("slots") List<RequiredSlot> slots) {
}
