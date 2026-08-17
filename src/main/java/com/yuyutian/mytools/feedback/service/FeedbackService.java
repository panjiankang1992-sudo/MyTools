package com.yuyutian.mytools.feedback.service;

import com.yuyutian.mytools.feedback.model.CreateFeedbackRequest;
import com.yuyutian.mytools.feedback.model.CreateFeedbackResponse;

/**
 * 问题反馈服务。
 */
public interface FeedbackService {

    /**
     * 接收并存储问题反馈。
     *
     * @param request 创建问题反馈请求
     * @return 创建结果
     */
    CreateFeedbackResponse createFeedback(CreateFeedbackRequest request);
}
