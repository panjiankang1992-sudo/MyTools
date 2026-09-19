package com.yuyutian.mytools.reader.model;

/**
 * 阅读服务统一错误码。
 */
public enum ErrorCode {
    SEARCH_NOT_FOUND("READER_001", "Search request was not found"),
    DISCOVERY_NOT_FOUND("READER_002", "Source discovery request was not found"),
    INTERNAL_UNAUTHORIZED("READER_003", "Internal service token is invalid"),
    HEALTH_CHECK_NOT_FOUND("READER_004", "Source health check was not found"),
    HEALTH_SOURCE_LIMIT("READER_005", "Enabled source count exceeds health check limit"),
    EBOOK_IMPORT_NOT_FOUND("READER_006", "Ebook import request was not found"),
    EBOOK_SOURCE_NOT_FOUND("READER_007", "Enabled book source was not found"),
    EBOOK_CATALOG_NOT_READY("READER_008", "Ebook import catalog is not ready"),
    EBOOK_CATALOG_INVALID("READER_009", "Ebook catalog batch is invalid"),
    CHAPTER_PREFETCH_NOT_FOUND("READER_010", "Chapter prefetch request was not found"),
    CHAPTER_CACHE_NOT_FOUND("READER_011", "Chapter cache entry was not found"),
    CHAPTER_CACHE_INVALID("READER_012", "Chapter cache batch is invalid"),
    CACHE_MAINTENANCE_NOT_FOUND("READER_013", "Chapter cache maintenance was not found"),
    CACHE_MAINTENANCE_CONFLICT("READER_014", "Chapter cache maintenance conflicts with existing state"),
    LIBRARY_REBUILD_NOT_FOUND("READER_015", "Library rebuild was not found"),
    LIBRARY_REBUILD_CONFLICT("READER_016", "Library rebuild conflicts with existing state"),
    READER_STATE_NOT_FOUND("READER_017", "Reader state was not found"),
    READER_STATE_CONFLICT("READER_018", "Reader state version conflicts with existing state"),
    RUNTIME_UNAVAILABLE("READER_019", "Reader runtime is unavailable"),
    AUDIOBOOK_GENERATION_NOT_FOUND("READER_020", "Audiobook generation was not found"),
    AUDIOBOOK_ASSET_NOT_FOUND("READER_021", "Audiobook source asset was not found"),
    AUDIOBOOK_TASK_FAILED("READER_022", "Audiobook generation task did not complete"),
    AUDIOBOOK_AUDIO_UNAVAILABLE("READER_023", "Audiobook chapter audio is unavailable"),
    AUDIOBOOK_EXPORT_NOT_FOUND("READER_024", "Audiobook export was not found"),
    AUDIOBOOK_EXPORT_TASK_FAILED("READER_025", "Audiobook export task did not complete"),
    AUDIOBOOK_EXPORT_UNAVAILABLE("READER_026", "Audiobook export archive is unavailable"),
    AUDIOBOOK_CHARACTER_LIMIT_EXCEEDED("READER_027", "Audiobook text exceeds the configured character limit"),
    AUDIOBOOK_DAILY_CHARACTER_QUOTA_EXCEEDED("READER_028", "Audiobook daily character quota is exceeded"),
    AUDIOBOOK_GENERATION_UNAVAILABLE("READER_029", "Audiobook generation is not available for this user"),
    ADAPTATION_NOT_FOUND("READER_030", "Chapter adaptation was not found"),
    CHAPTER_ADAPTATION_INELIGIBLE("READER_031", "Chapter is not eligible for adaptation"),
    ADAPTATION_INTENT_INVALID("READER_032", "Adaptation intent is invalid"),
    ADAPTATION_INTENT_CONFLICT("READER_033", "Adaptation intent conflicts with story constraints"),
    CHAPTER_CATALOG_STALE("READER_034", "Chapter catalog has changed"),
    CHAPTER_SOURCE_CHANGED("READER_035", "Chapter source has changed"),
    ADAPTATION_IDEMPOTENCY_CONFLICT("READER_036", "Adaptation request key conflicts with previous input"),
    ADAPTATION_ALREADY_ACTIVE("READER_037", "Chapter already has an active adaptation"),
    ADAPTATION_PARENT_INVALID("READER_038", "Adaptation parent is invalid"),
    ADAPTATION_PROVIDER_UNAVAILABLE("READER_039", "Adaptation provider is unavailable"),
    ADAPTATION_PROVIDER_UNAUTHORIZED("READER_040", "Adaptation provider authentication failed"),
    ADAPTATION_PROVIDER_PROTOCOL_INVALID("READER_041", "Adaptation provider response is invalid"),
    ADAPTATION_CONTENT_TOO_LARGE("READER_042", "Chapter exceeds adaptation capacity"),
    ADAPTATION_CONSTRAINT_VALIDATION_FAILED("READER_043", "Adaptation did not satisfy story constraints"),
    ADAPTATION_EXECUTION_FENCED("READER_044", "Adaptation execution is no longer current"),
    ADAPTATION_CAPACITY_EXCEEDED("READER_045", "Adaptation capacity is exceeded"),
    ADAPTATION_UNAVAILABLE("READER_046", "Adaptation is unavailable"),
    ADAPTATION_ATTEMPT_NOT_FOUND("READER_047", "Adaptation attempt was not found"),
    ADAPTATION_DISPATCH_FAILED("READER_048", "Adaptation could not be dispatched"),
    ADAPTATION_CONTEXT_INCOMPLETE("READER_049", "Adaptation context is incomplete"),
    ADAPTATION_TASK_BIND_PENDING("READER_050", "Adaptation task binding is pending"),
    ADAPTATION_CATALOG_TOO_LARGE("READER_051", "Chapter catalog exceeds adaptation capacity"),
    ADAPTATION_PROVIDER_CONSENT_REQUIRED("READER_052", "Provider processing consent is required"),
    ADAPTATION_CONTENT_REJECTED("READER_053", "Adaptation content was rejected"),
    ADAPTATION_PROVIDER_OUTCOME_UNKNOWN("READER_054", "Adaptation provider outcome is unknown"),
    ADAPTATION_PROVIDER_REQUEST_REJECTED("READER_055", "Adaptation provider rejected the request"),
    ADAPTATION_HISTORY_DELETED("READER_056", "Adaptation history was deleted"),
    ADAPTATION_DELETION_IN_PROGRESS("READER_057", "Adaptation history deletion is in progress"),
    ADAPTATION_DEADLINE_EXCEEDED("READER_058", "Adaptation deadline was exceeded"),
    ADAPTATION_AUTHORIZATION_UNAVAILABLE("READER_059", "Adaptation execution authorization is unavailable"),
    ADAPTATION_REQUEST_INVALID("READER_060", "Adaptation request does not match the supported schema"),
    ADAPTATION_PERSISTENCE_UNAVAILABLE("READER_061", "Adaptation persistence is unavailable"),
    ADAPTATION_CONSENT_REVISION_CONFLICT("READER_062", "Adaptation consent state has changed"),
    ADAPTATION_TEMPLATE_NOT_FOUND("READER_063", "Adaptation style template version was not found"),
    ADAPTATION_TEMPLATE_VERSION_CONFLICT("READER_064", "Adaptation style template version has changed");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回默认英文错误说明。
     *
     * @return 错误说明
     */
    public String message() {
        return message;
    }
}
