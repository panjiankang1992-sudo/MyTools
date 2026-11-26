package com.yuyutian.mytools.toolbox.service.events;

import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.mapper.stats.EventMapper;
import com.yuyutian.mytools.toolbox.model.EventBatchRequest;
import com.yuyutian.mytools.toolbox.model.EventBatchResult;
import com.yuyutian.mytools.toolbox.model.EventItemResult;
import com.yuyutian.mytools.toolbox.model.EventRow;
import com.yuyutian.mytools.toolbox.model.GuestSession;
import com.yuyutian.mytools.toolbox.model.PublicUsageEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事件写入服务：逐条校验、逐条事务落库，一条失败不影响同批其他事件。
 * 三层去重分别是事件唯一键、成功语义唯一键与按事件标识区分真实重复打印。
 */
@Service
public class EventIngestService {

    /** 公开客户端事件来源标记。 */
    public static final String SOURCE_CLIENT = "client";

    /** 接受状态码。 */
    private static final String STATUS_ACCEPTED = "accepted";

    /** 重复状态码。 */
    private static final String STATUS_DUPLICATE = "duplicate";

    private final EventValidator validator;
    private final EventMapper eventMapper;
    private final ToolboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 构造服务。
     *
     * @param validator           单条事件校验器
     * @param eventMapper         事件数据访问层
     * @param properties          门面配置
     * @param transactionTemplate 编程式事务模板
     * @param clock               统一时钟
     */
    public EventIngestService(EventValidator validator, EventMapper eventMapper, ToolboxProperties properties,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.validator = validator;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 处理一批事件，返回逐条结果与计数汇总。
     *
     * @param session 已通过会话与 CSRF 校验的访客会话
     * @param request 批量请求
     * @return 批量处理结果
     */
    public EventBatchResult ingest(GuestSession session, EventBatchRequest request) {
        List<PublicUsageEvent> events = request.events() == null ? List.of() : request.events();
        int maxBatch = properties.events().maxBatchSize();
        if (events.size() > maxBatch) {
            // 批量超过硬限制时整批拒绝，避免无界写入。
            throw new BusinessException(ErrorCode.TOOLBOX_005,
                    Map.of("limitName", "maxEventsPerBatch", "limit", (long) maxBatch, "actual", (long) events.size()));
        }
        Instant now = clock.instant();
        List<EventItemResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        for (int index = 0; index < events.size(); index++) {
            PublicUsageEvent raw = events.get(index);
            String rawEventId = raw == null ? null : raw.eventId();
            EventValidationResult validation = validator.validate(raw, now);
            if (validation instanceof EventValidationResult.Rejected rejection) {
                results.add(new EventItemResult(index, rawEventId, "rejected", rejection.reason().code()));
                rejected++;
                continue;
            }
            ValidatedEvent event = ((EventValidationResult.Valid) validation).event();
            Booking booking = persist(session, event, now);
            results.add(new EventItemResult(index, event.eventId(), booking.status(), booking.reason()));
            if (STATUS_ACCEPTED.equals(booking.status())) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new EventBatchResult(List.copyOf(results), accepted, duplicate, rejected);
    }

    /**
     * 在单个事务内写入事件明细、可选的成功去重记录与统计 Outbox；重复键整体回滚并按 duplicate 返回。
     *
     * @param session 会话
     * @param event   已校验事件
     * @param now     服务端接收时间
     * @return 单条处理结果
     */
    private Booking persist(GuestSession session, ValidatedEvent event, Instant now) {
        Booking booking = transactionTemplate.execute(status -> {
            EventRow row = new EventRow(
                    0L,
                    properties.siteId(),
                    session.guestId(),
                    event.eventId(),
                    event.toolId(),
                    event.category(),
                    event.eventType().value(),
                    SOURCE_CLIENT,
                    event.operationId(),
                    event.occurredAt(),
                    now);
            long eventPk;
            try {
                eventPk = eventMapper.insertEvent(row);
            } catch (DuplicateKeyException exception) {
                // 第一层去重：同站点同访客的 eventId 重复，属于网络重投。
                status.setRollbackOnly();
                return new Booking(STATUS_DUPLICATE, EventRejectReason.DUPLICATE_EVENT.code());
            }
            if (event.eventType() == EventType.WORKSHEET_SUCCESS) {
                try {
                    eventMapper.insertSuccessDedupe(properties.siteId(), SOURCE_CLIENT, session.guestId(),
                            event.toolId(), event.operationId(), now);
                } catch (DuplicateKeyException exception) {
                    // 第二层去重：同一次操作的成功只计一次，整条事务回滚避免留下明细。
                    status.setRollbackOnly();
                    return new Booking(STATUS_DUPLICATE, EventRejectReason.DUPLICATE_SUCCESS.code());
                }
            }
            eventMapper.insertOutbox(eventPk);
            return new Booking(STATUS_ACCEPTED, null);
        });
        return booking == null ? new Booking(STATUS_DUPLICATE, EventRejectReason.DUPLICATE_EVENT.code()) : booking;
    }

    /**
     * 单条事件的处理结论。
     *
     * @param status accepted 或 duplicate
     * @param reason 稳定原因码，接受时为空
     */
    private record Booking(String status, String reason) {
    }
}
