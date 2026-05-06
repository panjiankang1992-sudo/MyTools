package com.yuyutian.mytools.wechat.moments.model.dto;

import lombok.Data;

/**
 * 查询朋友圈任务请求
 */
@Data
public class QueryTasksRequest {
    /** 页码（从1开始） */
    private Long page;
    /** 每页记录数 */
    private Long pageSize;
    /** 微信账号ID筛选 */
    private Long accountId;
    /** 任务状态筛选 */
    private Integer status;
    /** 关键词搜索（匹配内容） */
    private String keyword;
}