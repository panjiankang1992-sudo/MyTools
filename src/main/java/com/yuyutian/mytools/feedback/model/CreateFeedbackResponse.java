package com.yuyutian.mytools.feedback.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 创建问题反馈响应。
 */
@Data
@AllArgsConstructor
public class CreateFeedbackResponse {

    private String feedbackId;

    private String status;
}
