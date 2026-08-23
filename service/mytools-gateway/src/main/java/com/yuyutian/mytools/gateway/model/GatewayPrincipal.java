package com.yuyutian.mytools.gateway.model;

import java.util.List;

/**
 * Gateway 校验后的统一用户主体。
 */
public record GatewayPrincipal(long userId, String username, List<String> roles) {
}
