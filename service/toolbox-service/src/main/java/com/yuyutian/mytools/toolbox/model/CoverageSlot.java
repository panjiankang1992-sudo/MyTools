package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 20 个必需槽位之一的覆盖情况。
 *
 * @param subject 学科
 * @param grade 年级
 * @param semester 册次
 * @param coverageStatus 覆盖状态
 * @param editionIds 该槽位已发布的教材版本标识
 */
public record CoverageSlot(
        /** 学科。 */
        @JsonProperty("subject") Subject subject,
        /** 年级。 */
        @JsonProperty("grade") int grade,
        /** 册次。 */
        @JsonProperty("semester") Semester semester,
        /** 覆盖状态。 */
        @JsonProperty("coverageStatus") CoverageStatus coverageStatus,
        /** 该槽位已发布的教材版本标识。 */
        @JsonProperty("editionIds") List<String> editionIds) {
}
