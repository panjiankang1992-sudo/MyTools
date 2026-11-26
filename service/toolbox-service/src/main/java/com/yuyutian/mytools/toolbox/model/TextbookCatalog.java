package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 教材目录接口响应，只包含可公开元数据。
 *
 * @param catalogVersion 目录版本
 * @param editions 已发布教材版本
 * @param coverage 20 个必需槽位覆盖情况
 */
public record TextbookCatalog(
        /** 目录版本。 */
        @JsonProperty("catalogVersion") String catalogVersion,
        /** 已发布教材版本。 */
        @JsonProperty("editions") List<TextbookEdition> editions,
        /** 20 个必需槽位覆盖情况。 */
        @JsonProperty("coverage") List<CoverageSlot> coverage) {
}
