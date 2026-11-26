package com.yuyutian.mytools.toolbox.service.admin.curriculum;

/**
 * 教材列表查询条件（设计文档 §3.3）。
 * 所有条件均为可选；未提供的条件不参与过滤。
 *
 * @param keyword  关键字，匹配教材名称、出版社、系列与版本
 * @param subject  学科：`chinese` / `english`
 * @param grade    年级
 * @param semester 册次：`first` / `second`
 * @param status   状态：`draft`（仅草稿）／`published`（已发布）
 * @param page     页码，从 1 开始
 * @param pageSize 每页数量，上限 100
 */
public record AdminTextbookQuery(
        String keyword,
        String subject,
        Integer grade,
        String semester,
        String status,
        int page,
        int pageSize) {

    /** 每页最大数量，与设计文档 §6 的 pageSize 上限一致。 */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 归一化分页参数，避免越界与超大页。
     *
     * @return 归一化后的查询条件
     */
    public AdminTextbookQuery normalized() {
        return new AdminTextbookQuery(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                subject == null || subject.isBlank() ? null : subject.trim(),
                grade,
                semester == null || semester.isBlank() ? null : semester.trim(),
                status == null || status.isBlank() ? null : status.trim(),
                Math.max(1, page),
                Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize)));
    }

    /**
     * 返回偏移量。
     *
     * @return SQL 偏移量
     */
    public int offset() {
        return (normalized().page() - 1) * normalized().pageSize();
    }
}
