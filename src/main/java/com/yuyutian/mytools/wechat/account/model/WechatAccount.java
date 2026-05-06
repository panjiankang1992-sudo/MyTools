package com.yuyutian.mytools.wechat.account.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 微信账号实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WechatAccount {
    /** 账号ID */
    private Long id;
    /** 微信ID/微信号 */
    private String wechatId;
    /** 昵称 */
    private String nickname;
    /** 备注 */
    private String remark;
    /** 状态: 1-正常, 2-禁用 */
    private Integer status;
    /** 刷新频率: 1-每天一次, 2-每天两次, 默认2 */
    private Integer refreshFrequency;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
