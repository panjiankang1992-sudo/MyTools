package com.yuyutian.mytools.wechat.account.model.dto;

import lombok.Data;

/**
 * 创建微信账号请求
 */
@Data
public class CreateAccountRequest {
    /** 微信ID/微信号 */
    private String wechatId;
    /** 昵称 */
    private String nickname;
    /** 备注 */
    private String remark;
}