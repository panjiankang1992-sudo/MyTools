package com.yuyutian.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Error code enum.
 * Unified management of all business error codes, format: 5-digit numeric code.
 * Each error code contains code number, message key, and corresponding HTTP status.
 */
@Getter
public enum ErrorCode {
    // User error codes (10001-10099)
    USER_001("10001", "user.not_found", HttpStatus.NOT_FOUND),
    USER_002("10002", "user.username.exists", HttpStatus.CONFLICT),
    USER_003("10003", "user.password.invalid", HttpStatus.BAD_REQUEST),
    USER_004("10004", "user.email.format.invalid", HttpStatus.BAD_REQUEST),
    USER_005("10005", "user.username.or.password.wrong", HttpStatus.UNAUTHORIZED),
    USER_006("10006", "user.account.disabled", HttpStatus.FORBIDDEN),
    USER_007("10007", "user.email.exists", HttpStatus.CONFLICT),
    USER_008("10008", "user.old.password.wrong", HttpStatus.BAD_REQUEST),
    USER_009("10009", "user.status.invalid", HttpStatus.BAD_REQUEST),

    // Auth error codes (20001-20099)
    AUTH_001("20001", "auth.token.expired", HttpStatus.UNAUTHORIZED),
    AUTH_002("20002", "auth.token.invalid", HttpStatus.UNAUTHORIZED),
    AUTH_003("20003", "auth.permission.denied", HttpStatus.FORBIDDEN),
    AUTH_004("20004", "auth.token.format.error", HttpStatus.UNAUTHORIZED),
    AUTH_005("20005", "auth.account.locked", HttpStatus.FORBIDDEN),

    // File error codes (30001-30099)
    FILE_001("30001", "file.not_found", HttpStatus.NOT_FOUND),
    FILE_002("30002", "file.preview.unsupported", HttpStatus.BAD_REQUEST),
    FILE_003("30003", "file.delete.failed", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_004("30004", "file.filename.exists", HttpStatus.CONFLICT),
    FILE_005("30005", "file.path.invalid", HttpStatus.BAD_REQUEST),
    FILE_006("30006", "file.tag.not_found", HttpStatus.NOT_FOUND),
    FILE_007("30007", "file.tag.name.exists", HttpStatus.CONFLICT),
    FILE_008("30008", "file.tagging.service.unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    FILE_009("30009", "file.type.unsupported", HttpStatus.BAD_REQUEST),
    FILE_010("30010", "file.dir.not_found", HttpStatus.NOT_FOUND),
    FILE_011("30011", "file.scan.in_progress", HttpStatus.CONFLICT),

    // Token error codes (40001-40099)
    TOKEN_001("40001", "token.not_found", HttpStatus.NOT_FOUND),
    TOKEN_002("40002", "token.operation.denied", HttpStatus.FORBIDDEN),
    TOKEN_003("40003", "token.name.invalid", HttpStatus.BAD_REQUEST),
    TOKEN_004("40004", "token.disabled", HttpStatus.BAD_REQUEST),
    TOKEN_005("40005", "token.verify.failed", HttpStatus.BAD_REQUEST),

    // Role error codes (60001-60099)
    ROLE_001("60001", "role.code.exists", HttpStatus.CONFLICT),
    ROLE_002("60002", "role.assigned.to.users", HttpStatus.BAD_REQUEST),
    ROLE_003("60003", "role.not_found", HttpStatus.NOT_FOUND),

    // System error codes (50001-50099)
    SYS_001("50001", "sys.server.error", HttpStatus.INTERNAL_SERVER_ERROR),
    SYS_002("50002", "sys.validation.failed", HttpStatus.BAD_REQUEST),
    SYS_003("50003", "sys.database.error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }
}
