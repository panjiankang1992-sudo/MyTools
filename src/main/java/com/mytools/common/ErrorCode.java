package com.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码枚举类。
 * 统一管理所有业务错误码，格式为 模块_序号。
 * 每个错误码包含编号、消息和对应的HTTP状态码。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Getter
public enum ErrorCode {
    // 用户错误码 (USER_001 - USER_009)
    USER_001("USER_001", "用户不存在", HttpStatus.NOT_FOUND),
    USER_002("USER_002", "用户名已存在", HttpStatus.CONFLICT),
    USER_003("USER_003", "密码不符合规范", HttpStatus.BAD_REQUEST),
    USER_004("USER_004", "邮箱格式错误", HttpStatus.BAD_REQUEST),
    USER_005("USER_005", "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    USER_006("USER_006", "账户已禁用", HttpStatus.FORBIDDEN),
    USER_007("USER_007", "邮箱已被使用", HttpStatus.CONFLICT),
    USER_008("USER_008", "旧密码错误", HttpStatus.BAD_REQUEST),
    USER_009("USER_009", "无效的用户状态", HttpStatus.BAD_REQUEST),

    // 认证错误码 (AUTH_001 - AUTH_005)
    AUTH_001("AUTH_001", "Token已过期", HttpStatus.UNAUTHORIZED),
    AUTH_002("AUTH_002", "无效Token", HttpStatus.UNAUTHORIZED),
    AUTH_003("AUTH_003", "权限不足", HttpStatus.FORBIDDEN),
    AUTH_004("AUTH_004", "Token格式错误", HttpStatus.UNAUTHORIZED),
    AUTH_005("AUTH_005", "Token刷新失败", HttpStatus.UNAUTHORIZED),

    // 系统错误码 (SYS_001 - SYS_003)
    SYS_001("SYS_001", "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SYS_002("SYS_002", "参数校验失败", HttpStatus.BAD_REQUEST),
    SYS_003("SYS_003", "数据库操作失败", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
