package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 仅在创建时返回一次原始 Token 的访问票据。
 *
 * @param id 票据标识
 * @param accessUrl 单用途访问路径
 * @param expiresAt 到期时间
 */
public record AccessTicketView(UUID id, String accessUrl, Instant expiresAt) {
}
