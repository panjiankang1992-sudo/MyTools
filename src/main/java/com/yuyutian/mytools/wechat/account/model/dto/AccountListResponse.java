package com.yuyutian.mytools.wechat.account.model.dto;

import com.yuyutian.mytools.wechat.account.model.WechatAccount;
import lombok.Data;
import java.util.List;

/**
 * 微信账号列表响应
 */
@Data
public class AccountListResponse {
    /** 账号列表 */
    private List<WechatAccount> list;
    /** 总数 */
    private Long total;
}