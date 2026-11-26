package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 必需槽位定义，来自 catalog/requirements.json。
 *
 * @param subject 学科
 * @param grade 年级
 * @param semester 册次
 */
// 内容文件允许携带额外说明字段，接口请求体仍保持严格校验。
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequiredSlot(
        /** 学科。 */
        @JsonProperty("subject") Subject subject,
        /** 年级。 */
        @JsonProperty("grade") int grade,
        /** 册次。 */
        @JsonProperty("semester") Semester semester) {
}
