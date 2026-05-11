package com.yuyutian.mytools.apilog.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * API日志实体类。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("ApiLog")
public class ApiLog {

    /** 日志ID */
    private Long id;

    /** 用户ID（未登录为NULL） */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 模块名称 */
    private String module;

    /** 请求路径 */
    private String apiPath;

    /** HTTP方法 */
    private String method;

    /** 请求方法名 */
    private String requestMethod;

    /** 是否成功：true-成功，false-失败 */
    private Boolean success;

    /** 错误信息 */
    private String errorMessage;

    /** 请求耗时（毫秒） */
    private Long durationMs;

    /** IP地址 */
    private String ipAddress;

    /** 请求时间 */
    private LocalDateTime requestTime;

    /** 响应时间 */
    private LocalDateTime responseTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
