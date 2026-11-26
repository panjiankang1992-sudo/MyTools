package com.yuyutian.mytools.toolbox.service.events;

import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.model.PublicUsageEvent;
import com.yuyutian.mytools.toolbox.model.ToolCategory;
import com.yuyutian.mytools.toolbox.model.ToolDefinition;
import com.yuyutian.mytools.toolbox.service.tools.ToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 单条事件校验器：按工具注册表、事件类型组合与时间窗口逐条判断，绝不因一条失败影响整批。
 */
@Component
public class EventValidator {

    /** 事件标识与操作标识的最大长度，防止超长键破坏索引。 */
    private static final int MAX_ID_LENGTH = 64;

    private final ToolRegistry toolRegistry;
    private final ToolboxProperties properties;

    /**
     * 构造校验器。
     *
     * @param toolRegistry 工具注册表
     * @param properties   门面配置
     */
    public EventValidator(ToolRegistry toolRegistry, ToolboxProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 校验单条事件。
     *
     * @param raw 原始事件
     * @param now 服务端当前时间
     * @return 校验结果
     */
    public EventValidationResult validate(PublicUsageEvent raw, Instant now) {
        if (raw == null) {
            return new EventValidationResult.Rejected(EventRejectReason.MISSING_EVENT_ID);
        }
        String eventId = trimToNull(raw.eventId());
        if (eventId == null) {
            return new EventValidationResult.Rejected(EventRejectReason.MISSING_EVENT_ID);
        }
        if (eventId.length() > MAX_ID_LENGTH) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_EVENT_ID);
        }
        if (raw.schemaVersion() == null || raw.schemaVersion() != 1) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_SCHEMA_VERSION);
        }
        Optional<EventType> eventType = EventType.parse(trimToNull(raw.eventType()));
        if (eventType.isEmpty()) {
            // pdf_success 不在公开枚举内，会与任意未知取值一样被拒绝。
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_EVENT_TYPE);
        }
        Optional<ToolCategory> category = ToolCategory.parse(trimToNull(raw.category()));
        if (category.isEmpty()) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_CATEGORY);
        }
        String toolId = trimToNull(raw.toolId());
        String operationId = trimToNull(raw.operationId());
        if (operationId != null && operationId.length() > MAX_ID_LENGTH) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_OPERATION_ID);
        }
        if (toolId != null) {
            Optional<ToolDefinition> definition = toolRegistry.find(toolId);
            if (definition.isEmpty()) {
                return new EventValidationResult.Rejected(EventRejectReason.UNKNOWN_TOOL);
            }
            if (!definition.get().enabled()) {
                return new EventValidationResult.Rejected(EventRejectReason.TOOL_DISABLED);
            }
            if (definition.get().category() != category.get()) {
                return new EventValidationResult.Rejected(EventRejectReason.TOOL_CATEGORY_MISMATCH);
            }
        }
        EventType type = eventType.get();
        if (type != EventType.ENTRY_CLICK && toolId == null) {
            return new EventValidationResult.Rejected(EventRejectReason.MISSING_TOOL_ID);
        }
        if (type == EventType.WORKSHEET_SUCCESS && !toolRegistry.isWorksheetTool(toolId)) {
            // worksheet_success 只接受四个 worksheet.* 工具，借此阻断用其他工具伪造成功。
            return new EventValidationResult.Rejected(EventRejectReason.WORKSHEET_TOOL_REQUIRED);
        }
        if (type != EventType.ENTRY_CLICK && operationId == null) {
            return new EventValidationResult.Rejected(EventRejectReason.MISSING_OPERATION_ID);
        }
        if (type == EventType.ENTRY_CLICK && operationId != null && operationId.length() > MAX_ID_LENGTH) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_OPERATION_ID);
        }
        String occurredAt = trimToNull(raw.occurredAt());
        if (occurredAt == null) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_OCCURRED_AT);
        }
        Instant occurredInstant;
        try {
            occurredInstant = Instant.parse(occurredAt);
        } catch (DateTimeParseException exception) {
            return new EventValidationResult.Rejected(EventRejectReason.INVALID_OCCURRED_AT);
        }
        Instant earliest = now.minus(Duration.ofHours(properties.events().maxPastHours()));
        Instant latest = now.plus(Duration.ofMinutes(properties.events().maxFutureMinutes()));
        if (occurredInstant.isBefore(earliest) || occurredInstant.isAfter(latest)) {
            return new EventValidationResult.Rejected(EventRejectReason.OCCURRED_AT_OUT_OF_RANGE);
        }
        return new EventValidationResult.Valid(
                new ValidatedEvent(eventId, type, category.get(), toolId, operationId, occurredInstant));
    }

    /**
     * 去除首尾空白，空白串按缺失处理。
     *
     * @param value 原始取值
     * @return 规范化取值或 null
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
