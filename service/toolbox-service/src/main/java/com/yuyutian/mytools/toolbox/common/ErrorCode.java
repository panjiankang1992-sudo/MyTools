package com.yuyutian.mytools.toolbox.common;

import java.util.Map;

/**
 * 公开工具箱门面的稳定错误码。
 * HTTP 状态、错误码与契约附录 A 第 1 节表格一一对应；提示文案由前端按 messageKey 解析。
 */
public enum ErrorCode {

    /** 字段、范围、类型无效。 */
    TOOLBOX_001(422, "request.invalid"),
    /** 匿名 cookie 缺失或已过期。 */
    TOOLBOX_002(401, "session.required"),
    /** 资源不存在或不属于当前会话。 */
    TOOLBOX_003(404, "resource.notFound"),
    /** 同幂等键不同请求、状态或 offset 冲突。 */
    TOOLBOX_004(409, "request.conflict"),
    /** 输入字节、文件、页、像素等硬限制。 */
    TOOLBOX_005(413, "task.limitExceeded"),
    /** 频率、并发、队列或额度限制。 */
    TOOLBOX_006(429, "request.rateLimited"),
    /** 本会话资源已到期。 */
    TOOLBOX_007(410, "resource.expired"),
    /** 依赖未启用或不可用。 */
    TOOLBOX_008(503, "service.unavailable"),
    /** 加密、表单等不支持输入。 */
    TOOLBOX_009(422, "input.unsupported"),
    /** 来源或 CSRF 校验不通过。 */
    TOOLBOX_010(403, "request.forbidden"),
    /** 缺字、题目超大、内容待校对。 */
    TOOLBOX_011(422, "content.unusable"),
    /** 上游结果校验或输出失败。 */
    TOOLBOX_012(503, "upstream.invalidResult"),

    /** 管理会话缺失或已过期，需重新登录；暂停写入但不清空编辑缓冲。 */
    TOOLBOX_ADMIN_001(401, "admin.session.required"),
    /** 当前账号没有管理员权限，应销毁管理会话。 */
    TOOLBOX_ADMIN_002(403, "admin.forbidden"),
    /** 来源或 CSRF 校验不通过，保留输入且不执行变更。 */
    TOOLBOX_ADMIN_003(403, "admin.csrf.invalid"),
    /** revision、baseContentVersion 或同幂等键不同内容冲突。 */
    TOOLBOX_ADMIN_004(409, "admin.conflict"),
    /** 发布校验失败，返回可定位的问题清单。 */
    TOOLBOX_ADMIN_005(422, "admin.publish.invalid"),
    /** 教材、草稿、版本或会话不存在。 */
    TOOLBOX_ADMIN_006(404, "admin.notFound"),
    /** 请求体或条目数量超出管理接口上限。 */
    TOOLBOX_ADMIN_007(413, "admin.limitExceeded"),
    /** 登录、保存或翻译触发频率限制，需返回 Retry-After。 */
    TOOLBOX_ADMIN_008(429, "admin.rateLimited"),
    /** 身份依赖或管理存储不可用，禁止继续变更。 */
    TOOLBOX_ADMIN_009(503, "admin.unavailable"),
    /** 缺少 If-Match 等并发前提条件。 */
    TOOLBOX_ADMIN_010(428, "admin.precondition.required"),
    /** 目录来源为只读文件（`files`）时禁止发布：发布只写数据库，公开目录不会随之改变。 */
    TOOLBOX_ADMIN_011(409, "admin.publish.disabled");

    private final int httpStatus;
    private final String messageKey;

    ErrorCode(int httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }

    /**
     * 返回该错误码对应的 HTTP 状态。
     *
     * @return HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 返回默认的 messageKey，前端据此解析本地化提示。
     *
     * @return 稳定的 messageKey
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * 构造只包含字段路径、限额与索引的安全 details。
     *
     * @param field  出错字段路径，可为空
     * @param reason 安全的机器可读原因，可为空
     * @param extras 附加安全键值，可为空
     * @return 不可变 details 映射
     */
    public Map<String, Object> details(String field, String reason, Map<String, Object> extras) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (field != null) {
            details.put("field", field);
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        if (extras != null) {
            details.putAll(extras);
        }
        return java.util.Collections.unmodifiableMap(details);
    }
}
