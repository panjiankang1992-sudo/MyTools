package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 发布清单文件 catalog/published.json 的顶层结构。
 *
 * @param catalogVersion 目录版本
 * @param editions 已发布教材版本
 */
// 内容文件允许携带额外说明字段，接口请求体仍保持严格校验。
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogManifest(
        /** 目录版本。 */
        @JsonProperty("catalogVersion") String catalogVersion,
        /** 已发布教材版本。 */
        @JsonProperty("editions") List<TextbookEdition> editions) {
}
