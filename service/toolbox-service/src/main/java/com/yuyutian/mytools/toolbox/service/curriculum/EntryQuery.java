package com.yuyutian.mytools.toolbox.service.curriculum;

import com.yuyutian.mytools.toolbox.model.ListType;
import java.util.List;

/**
 * 教材条目分页查询条件；游标与版本、过滤条件绑定，跨版本或跨过滤集一律拒绝。
 *
 * @param editionId 教材版本标识
 * @param version   内容版本
 * @param unitIds   单元过滤，空表示不限
 * @param lessonIds 课文过滤，空表示不限
 * @param listTypes 清单类别过滤，空表示不限
 * @param cursor    不透明游标，可为空
 * @param limit     分页条数，已在上层校验
 */
public record EntryQuery(
        String editionId,
        String version,
        List<String> unitIds,
        List<String> lessonIds,
        List<ListType> listTypes,
        String cursor,
        int limit) {
}
