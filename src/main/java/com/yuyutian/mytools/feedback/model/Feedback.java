package com.yuyutian.mytools.feedback.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题反馈实体。
 */
@Data
public class Feedback {

    private String id;

    private String username;

    private String email;

    private String phone;

    private String category;

    private String title;

    private String content;

    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
