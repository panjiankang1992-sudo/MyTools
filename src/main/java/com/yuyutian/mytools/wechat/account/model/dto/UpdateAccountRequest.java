package com.yuyutian.mytools.wechat.account.model.dto;

import lombok.Data;

/**
 * 更新微信账号请求
 */
@Data
public class UpdateAccountRequest {
    /** 账号ID */
    private Long id;
    /** 昵称 */
    private String nickname;
    /** 备注 */
    private String remark;
    /** 刷新频率: 1-每天一次, 2-每天两次 */
    private Integer refreshFrequency;
}
