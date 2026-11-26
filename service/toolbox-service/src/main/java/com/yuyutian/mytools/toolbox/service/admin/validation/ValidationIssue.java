package com.yuyutian.mytools.toolbox.service.admin.validation;

/**
 * 一条可定位的校验问题。
 * 前端据此把用户带到具体行与字段，因此必须带足够定位信息，不能只给一句描述。
 *
 * @param severity  严重程度：BLOCKING 阻止发布，WARNING 仅提示
 * @param code      稳定问题码，供前端映射文案
 * @param fieldPath 字段路径，例如 `entries[3].meaning`
 * @param rowId     条目稳定标识；与条目无关时为 null
 * @param unitId    所属单元；未知时为 null
 * @param lessonId  所属课文；未知时为 null
 * @param detail    安全细节，不含正文以外信息
 */
public record ValidationIssue(
        Severity severity,
        String code,
        String fieldPath,
        String rowId,
        String unitId,
        String lessonId,
        String detail) {

    /** 严重程度。 */
    public enum Severity {
        /** 阻止发布。 */
        BLOCKING,
        /** 仅提示，可发布。 */
        WARNING
    }

    /**
     * 构造阻止发布的问题。
     *
     * @param code      问题码
     * @param fieldPath 字段路径
     * @param rowId     条目标识
     * @param detail    细节
     * @return 问题
     */
    public static ValidationIssue blocking(String code, String fieldPath, String rowId, String detail) {
        return new ValidationIssue(Severity.BLOCKING, code, fieldPath, rowId, null, null, detail);
    }
}
