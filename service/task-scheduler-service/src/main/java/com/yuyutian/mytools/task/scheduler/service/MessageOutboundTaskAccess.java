package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 新版消息投递只允许 Messaging 身份按固定协议创建和读取。 */
public final class MessageOutboundTaskAccess {
    private MessageOutboundTaskAccess() { }
    /** 创建任务只接受不透明投递标识和代次。 */
    public static void create(CreateTaskRequest request) {
        if (!domain(request.taskName(), request.businessType(), request.idempotencyKey())) return;
        requireIdentity();
        try {
            UUID id = UUID.fromString(request.businessId());
            Object raw = request.parameters().get("generation");
            if (!(raw instanceof Number)) reject();
            int generation = ((Number) raw).intValue();
            if (generation < 1 || generation > 100 || ((Number) raw).doubleValue() != generation
                    || !"message_send_channel_message".equals(request.taskName()) || !"MESSAGE_OUTBOUND".equals(request.businessType())
                    || !("message-outbound:" + id + ":" + generation).equals(request.idempotencyKey())
                    || !id.toString().equals(request.parameters().get("deliveryId")) || !id.toString().equals(request.businessId())
                    || !Set.of("deliveryId", "generation").equals(request.parameters().keySet())
                    || request.parentTaskInstanceId() != null || request.priority() != 0
                    || request.requiredNodeLabels() != null && !request.requiredNodeLabels().isEmpty()) reject();
        } catch (IllegalArgumentException | NullPointerException exception) { reject(); }
    }
    /** 独立作用域不能被旧消息令牌或其他业务身份读取。 */
    public static void read(TaskInstanceView task) {
        if (domain(task.taskName(), task.businessType(), task.idempotencyKey())) requireIdentity();
    }
    /** 只信任令牌过滤器写入的服务身份。 */
    public static void requireIdentity() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet) || !"messaging-service".equals(
                servlet.getRequest().getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE))) reject();
    }
    private static boolean domain(String name, String type, String key) {
        return "message_send_channel_message".equalsIgnoreCase(name) || "MESSAGE_OUTBOUND".equalsIgnoreCase(type)
                || key != null && key.toLowerCase(Locale.ROOT).startsWith("message-outbound:");
    }
    private static void reject() { throw new SchedulerException(ErrorCode.TASK_DOMAIN_FORBIDDEN, HttpStatus.FORBIDDEN, "Outbound task domain rejected"); }
}
