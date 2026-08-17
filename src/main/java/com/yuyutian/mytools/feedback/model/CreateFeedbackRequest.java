package com.yuyutian.mytools.feedback.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建问题反馈请求。
 */
@Data
public class CreateFeedbackRequest {

    @Valid
    @NotNull(message = "feedback.user_info.required")
    private FeedbackUserInfoRequest userInfo;

    @NotBlank(message = "feedback.category.required")
    @Size(max = 50, message = "feedback.category.size")
    private String category;

    @NotBlank(message = "feedback.title.required")
    @Size(max = 200, message = "feedback.title.size")
    private String title;

    @NotBlank(message = "feedback.content.required")
    @Size(max = 5000, message = "feedback.content.size")
    private String content;
}
