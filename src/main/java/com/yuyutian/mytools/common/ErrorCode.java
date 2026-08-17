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
    USER_010("10010", "user.verification.code.invalid", HttpStatus.BAD_REQUEST),
    USER_011("10011", "user.verification.code.expired", HttpStatus.BAD_REQUEST),
    USER_012("10012", "user.verification.code.too_frequent", HttpStatus.TOO_MANY_REQUESTS),

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

    // App Market error codes (70001-70099)
    APP_001("70001", "app.market.app_not_found", HttpStatus.NOT_FOUND),
    APP_002("70002", "app.market.permission.denied", HttpStatus.FORBIDDEN),
    APP_003("70003", "app.market.file.too_large", HttpStatus.BAD_REQUEST),
    APP_004("70004", "app.market.file.type.unsupported", HttpStatus.BAD_REQUEST),
    APP_005("70005", "app.market.version.conflict", HttpStatus.CONFLICT),
    APP_006("70006", "app.market.name.duplicate", HttpStatus.CONFLICT),
    APP_007("70007", "app.market.file.not_found", HttpStatus.NOT_FOUND),
    APP_008("70008", "app.market.version.not_found", HttpStatus.NOT_FOUND),

    // Role error codes (60001-60099)
    ROLE_001("60001", "role.code.exists", HttpStatus.CONFLICT),
    ROLE_002("60002", "role.assigned.to.users", HttpStatus.BAD_REQUEST),
    ROLE_003("60003", "role.not_found", HttpStatus.NOT_FOUND),

    // Feedback error codes (80001-80099)
    FEEDBACK_001("80001", "feedback.create.failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // 媒体错误码（90001-90099）
    MEDIA_001("90001", "media.stream.source.unsupported", HttpStatus.BAD_REQUEST),
    MEDIA_002("90002", "media.stream.open.failed", HttpStatus.BAD_GATEWAY),
    MEDIA_003("90003", "media.stream.range.invalid", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE),
    MEDIA_004("90004", "media.stream.ticket.invalid", HttpStatus.UNAUTHORIZED),
    MEDIA_005("90005", "media.thumbnail.invalid", HttpStatus.UNPROCESSABLE_ENTITY),
    MEDIA_006("90006", "media.catalog.invalid", HttpStatus.NOT_FOUND),

    // Copilot错误码（91001-91099）
    COPILOT_001("91001", "copilot.gateway.disabled", HttpStatus.SERVICE_UNAVAILABLE),
    COPILOT_002("91002", "copilot.request.invalid", HttpStatus.BAD_REQUEST),
    COPILOT_003("91003", "copilot.provider.unavailable", HttpStatus.BAD_GATEWAY),
    COPILOT_004("91004", "copilot.provider.response.invalid", HttpStatus.BAD_GATEWAY),

    // DSH错误码（95001-95099）
    DSH_001("95001", "dsh.gateway.disabled", HttpStatus.SERVICE_UNAVAILABLE),
    DSH_002("95002", "dsh.request.invalid", HttpStatus.BAD_REQUEST),
    DSH_003("95003", "dsh.service.unavailable", HttpStatus.BAD_GATEWAY),
    DSH_004("95004", "dsh.response.invalid", HttpStatus.BAD_GATEWAY),
    DSH_005("95005", "dsh.session.not.found", HttpStatus.NOT_FOUND),
    DSH_006("95006", "dsh.session.forbidden", HttpStatus.FORBIDDEN),
    DSH_007("95007", "dsh.interaction.expired", HttpStatus.CONFLICT),

    // 阅读错误码（92001-92099）
    READER_001("92001", "reader.marker.quota.exceeded", HttpStatus.CONFLICT),
    READER_002("92002", "reader.shelf.invalid", HttpStatus.BAD_REQUEST),
    READER_003("92003", "reader.shelf.quota.exceeded", HttpStatus.CONFLICT),
    READER_004("92004", "reader.source.invalid", HttpStatus.BAD_REQUEST),
    READER_005("92005", "reader.source.quota.exceeded", HttpStatus.CONFLICT),

    // 局域网连接错误码（93001-93099）
    CONNECTIVITY_001("93001", "connectivity.probe.invalid", HttpStatus.UNAUTHORIZED),
    CONNECTIVITY_002("93002", "connectivity.probe.expired", HttpStatus.UNAUTHORIZED),

    // 网盘错误码（94001-94099）
    DRIVE_001("94001", "drive.not.found", HttpStatus.NOT_FOUND),
    DRIVE_002("94002", "drive.item.not.found", HttpStatus.NOT_FOUND),
    DRIVE_003("94003", "drive.gateway.unavailable", HttpStatus.BAD_GATEWAY),
    DRIVE_004("94004", "drive.request.invalid", HttpStatus.BAD_REQUEST),
    DRIVE_005("94005", "drive.ticket.invalid", HttpStatus.UNAUTHORIZED),

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
