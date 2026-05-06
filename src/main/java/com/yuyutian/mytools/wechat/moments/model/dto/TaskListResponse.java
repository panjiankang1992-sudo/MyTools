package com.yuyutian.mytools.wechat.moments.model.dto;

import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import lombok.Data;
import java.util.List;

/**
 * 朋友圈任务列表响应
 */
@Data
public class TaskListResponse {
    /** 任务列表 */
    private List<MomentsTask> list;
    /** 总数 */
    private Long total;
}