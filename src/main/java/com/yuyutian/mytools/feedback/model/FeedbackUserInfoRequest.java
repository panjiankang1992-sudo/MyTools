package com.yuyutian.mytools.feedback.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 问题反馈用户基本信息请求。
 */
@Data
public class FeedbackUserInfoRequest {

    @NotBlank(message = "feedback.username.required")
    @Size(max = 50, message = "feedback.username.size")
    private String username;

    @NotBlank(message = "feedback.email.required")
    @Email(message = "feedback.email.invalid")
    @Size(max = 100, message = "feedback.email.size")
    private String email;

    @Size(max = 20, message = "feedback.phone.size")
    private String phone;
}
