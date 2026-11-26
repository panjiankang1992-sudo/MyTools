package com.yuyutian.mytools.toolbox.service.curriculum;

import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;

/**
 * 教材目录查询条件：不同维度取交集，同一维度多值取并集。
 *
 * @param subject  学科，可为空表示不限
 * @param grade    年级，可为空表示不限
 * @param semester 册次，可为空表示不限
 * @param region   地区代码，可为空表示不限
 */
public record CatalogQuery(Subject subject, Integer grade, Semester semester, String region) {
}
