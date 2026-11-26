package com.yuyutian.mytools.toolbox.service.admin.identity;

import java.util.List;

/**
 * 上游校验返回的账号权威状态。
 * 该状态来自上游数据库的当前值，而不是浏览器提供的任何字段，也不是 JWT 内的历史角色。
 *
 * @param active   账号当前是否有效；false 表示已禁用、已撤销或已过期
 * @param userId   上游用户标识；对前端输出时一律按字符串处理，避免超出 JS 安全整数范围
 * @param username 显示用账号名，可为空
 * @param roles    数据库中的当前角色清单，可能为空
 */
public record AdminIdentity(boolean active, String userId, String username, List<String> roles) {

    /** 管理角色的唯一判定值。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 判断该身份当前是否具备管理员权限。
     * 只有账号有效且角色清单包含 ADMIN 才成立；不按用户名字面量判断。
     *
     * @return 具备管理员权限时为 true
     */
    public boolean isAdmin() {
        return active && roles != null && roles.contains(ROLE_ADMIN);
    }
}
