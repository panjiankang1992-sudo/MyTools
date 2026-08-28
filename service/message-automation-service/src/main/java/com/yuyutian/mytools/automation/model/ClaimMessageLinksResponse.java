package com.yuyutian.mytools.automation.model;

import java.util.List;

/**
 * 批量链接登记结果，只返回本次获得处理权的规范化链接。
 */
public record ClaimMessageLinksResponse(List<String> claimedUrls) {
}
