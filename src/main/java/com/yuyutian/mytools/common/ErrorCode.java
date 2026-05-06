package com.yuyutian.mytools.common;

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
    AUTH_005("AUTH_005", "账户已锁定", HttpStatus.FORBIDDEN),

    // 系统错误码 (SYS_001 - SYS_003)
    SYS_001("SYS_001", "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SYS_002("SYS_002", "参数校验失败", HttpStatus.BAD_REQUEST),
    SYS_003("SYS_003", "数据库操作失败", HttpStatus.INTERNAL_SERVER_ERROR),

    // 微信账号错误码 (ACCOUNT_001 - ACCOUNT_005)
    ACCOUNT_001("ACCOUNT_001", "微信ID已存在", HttpStatus.BAD_REQUEST),
    ACCOUNT_002("ACCOUNT_002", "账号不存在", HttpStatus.NOT_FOUND),
    ACCOUNT_003("ACCOUNT_003", "账号已被任务使用，无法删除", HttpStatus.BAD_REQUEST),
    ACCOUNT_004("ACCOUNT_004", "刷新频率参数无效", HttpStatus.BAD_REQUEST),
    ACCOUNT_005("ACCOUNT_005", "账号已被禁用", HttpStatus.BAD_REQUEST),

    // 朋友圈任务错误码 (MOMENTS_001 - MOMENTS_007)
    MOMENTS_001("MOMENTS_001", "参数错误", HttpStatus.BAD_REQUEST),
    MOMENTS_002("MOMENTS_002", "用户账号不存在", HttpStatus.NOT_FOUND),
    MOMENTS_003("MOMENTS_003", "任务不存在", HttpStatus.NOT_FOUND),
    MOMENTS_004("MOMENTS_004", "文件上传失败", HttpStatus.INTERNAL_SERVER_ERROR),
    MOMENTS_005("MOMENTS_005", "数据库错误", HttpStatus.INTERNAL_SERVER_ERROR),
    MOMENTS_006("MOMENTS_006", "任务已删除，无法刷新", HttpStatus.BAD_REQUEST),
    MOMENTS_007("MOMENTS_007", "刷新操作超时", HttpStatus.GATEWAY_TIMEOUT),

    // 本地文件错误码 (FILE_001 - FILE_005)
    FILE_001("FILE_001", "文件不存在", HttpStatus.NOT_FOUND),
    FILE_002("FILE_002", "文件类型不支持预览", HttpStatus.BAD_REQUEST),
    FILE_003("FILE_003", "文件删除失败", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_004("FILE_004", "文件名已存在", HttpStatus.CONFLICT),
    FILE_005("FILE_005", "无效的文件路径", HttpStatus.BAD_REQUEST),

    // 标签错误码 (TAG_001 - TAG_002)
    TAG_001("TAG_001", "标签不存在", HttpStatus.NOT_FOUND),
    TAG_002("TAG_002", "标签名称已存在", HttpStatus.CONFLICT),

    // 文件打标签错误码 (TAG_003 - TAG_005)
    TAG_003("TAG_003", "文件不存在", HttpStatus.NOT_FOUND),
    TAG_004("TAG_004", "打标签服务不可用", HttpStatus.SERVICE_UNAVAILABLE),
    TAG_005("TAG_005", "文件类型不支持", HttpStatus.BAD_REQUEST),

    // 目录错误码 (DIR_001 - DIR_002)
    DIR_001("DIR_001", "目录不存在或无权限访问", HttpStatus.NOT_FOUND),
    DIR_002("DIR_002", "扫描任务执行中", HttpStatus.CONFLICT),

    // Token错误码 (TOKEN_001 - TOKEN_005)
    TOKEN_001("TOKEN_001", "Token不存在", HttpStatus.NOT_FOUND),
    TOKEN_002("TOKEN_002", "无权限操作此Token", HttpStatus.FORBIDDEN),
    TOKEN_003("TOKEN_003", "Token名称无效", HttpStatus.BAD_REQUEST),
    TOKEN_004("TOKEN_004", "Token已禁用", HttpStatus.BAD_REQUEST),
    TOKEN_005("TOKEN_005", "Token验证失败", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
