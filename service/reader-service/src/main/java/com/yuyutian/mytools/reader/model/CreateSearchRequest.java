package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 创建多书源搜索请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param keyword 搜索关键字
 * @param mode 查询模式
 * @param page 页码
 * @param searchTerms 已完成分析的探测词
 * @param sources 书源不可变快照
 */
public record CreateSearchRequest(
        @NotNull Long ownerId,
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Size(max = 200) String keyword,
        @NotNull SearchMode mode,
        @Min(1) @Max(1000) int page,
        @Size(max = 10) List<@NotBlank @Size(max = 100) String> searchTerms,
        @NotEmpty @Size(max = 500) List<@Valid SourceSnapshot> sources
) {
    /** 返回不可变探测词集合。 */
    @Override public List<String> searchTerms() {
        return searchTerms == null ? List.of() : List.copyOf(searchTerms);
    }
    /**
     * 书源执行快照。
     *
     * @param id 原系统或新系统书源标识
     * @param name 书源名称
     * @param url 书源地址
     * @param revision 快照版本
     * @param snapshot Runtime 接受的完整快照
     */
    public record SourceSnapshot(
            @NotBlank @Size(max = 255) String id,
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 2000) String url,
            @NotNull Integer revision,
            @NotNull Map<String, Object> snapshot
    ) {
    }
}
