package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 教材版本元数据，字段与实施契约 3.1 节一一对应。
 * 该对象既作为发布清单的持久化模型，也作为 /textbooks 的公开响应模型。
 *
 * @param editionId 书的版本身份
 * @param subject 学科
 * @param grade 年级
 * @param semester 册次
 * @param regionCodes 适用地区代码
 * @param publisher 出版社
 * @param series 教材系列
 * @param editionLabel 版本展示名称
 * @param curriculumYear 课标年份，可为空
 * @param isbn 书号，可为空
 * @param sourceIds 来源记录标识
 * @param schemaVersion 内容包结构版本，固定为 1
 * @param contentVersion 校对后发布版本
 * @param contentSha256 发布正文文件的 SHA-256
 * @param reviewStatus 校对状态
 * @param coverageStatus 覆盖状态
 * @param verifiedAt 核验时间
 * @param publishedAt 发布时间，可为空
 * @param availableEntryCount 可用条目数量
 * @param expectedEntryCount 预期条目数量，可为空
 * @param missingScopes 已声明的缺口范围
 */
public record TextbookEdition(
        @JsonProperty("editionId") String editionId,
        @JsonProperty("subject") Subject subject,
        @JsonProperty("grade") int grade,
        @JsonProperty("semester") Semester semester,
        @JsonProperty("regionCodes") List<String> regionCodes,
        @JsonProperty("publisher") String publisher,
        @JsonProperty("series") String series,
        @JsonProperty("editionLabel") String editionLabel,
        @JsonProperty("curriculumYear") Integer curriculumYear,
        @JsonProperty("isbn") String isbn,
        @JsonProperty("sourceIds") List<String> sourceIds,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("contentVersion") String contentVersion,
        @JsonProperty("contentSha256") String contentSha256,
        @JsonProperty("reviewStatus") ReviewStatus reviewStatus,
        @JsonProperty("coverageStatus") CoverageStatus coverageStatus,
        @JsonProperty("verifiedAt") Instant verifiedAt,
        @JsonProperty("publishedAt") Instant publishedAt,
        @JsonProperty("availableEntryCount") Integer availableEntryCount,
        @JsonProperty("expectedEntryCount") Integer expectedEntryCount,
        @JsonProperty("missingScopes") List<String> missingScopes) {

    /**
     * 返回以实际条目数填充后的副本，用于清单缺省该字段时仍然对外给出确定的数值。
     *
     * @param entryCount 实际可用条目数
     * @return 填充后的教材版本元数据
     */
    public TextbookEdition withAvailableEntryCount(int entryCount) {
        return new TextbookEdition(editionId, subject, grade, semester, regionCodes, publisher, series, editionLabel,
                curriculumYear, isbn, sourceIds, schemaVersion, contentVersion, contentSha256, reviewStatus,
                coverageStatus, verifiedAt, publishedAt, entryCount, expectedEntryCount, missingScopes);
    }

    /**
     * 返回对外输出的条目数，未声明时视为 0。
     *
     * @return 可用条目数
     */
    public int entryCount() {
        return availableEntryCount == null ? 0 : availableEntryCount;
    }
}
