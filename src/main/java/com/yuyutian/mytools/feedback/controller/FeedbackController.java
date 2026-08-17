package com.yuyutian.mytools.feedback.controller;

import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.feedback.model.CreateFeedbackRequest;
import com.yuyutian.mytools.feedback.model.CreateFeedbackResponse;
import com.yuyutian.mytools.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外问题反馈接口。
 */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * 接收外部问题反馈并存储。
     *
     * @param request 问题反馈请求
     * @return 操作结果
     */
    @PostMapping("/api/public/feedback")
    public ResponseEntity<Result<CreateFeedbackResponse>> createFeedback(
            @Valid @RequestBody CreateFeedbackRequest request) {
        CreateFeedbackResponse response = feedbackService.createFeedback(request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.feedback.create"), response));
    }
}
