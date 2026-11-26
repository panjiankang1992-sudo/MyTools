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
import java.util.Map;
import java.util.UUID;

/** 新闻任务使用独立业务身份，双向限制创建、读取、取消和结果查询。 */
public final class NewsTaskAccess {
    private NewsTaskAccess() { }

    /** 固定新闻业务契约，禁止传入任意脚本、所有者或网络目标。 */
    public static void create(CreateTaskRequest request) {
        boolean domain = domain(request.taskName(), request.idempotencyKey(), request.businessType());
        if (!domain && !newsIdentity()) return;
        requireIdentity();
        try {
            UUID id = UUID.fromString(request.businessId());
            boolean region = "news_resolve_region".equals(request.taskName());
            boolean weekly = "news_generate_weekly".equals(request.taskName());
            String type = region ? "NEWS_REGION" : weekly ? "NEWS_WEEKLY" : "NEWS_EDITION";
            String prefix = region ? "news:region:" : weekly ? "news:weekly:" : "news:";
            String parameter = region || weekly ? "jobId" : "runId";
            if (!id.toString().equals(request.businessId()) || (!region && !weekly && !"news_generate_edition".equals(request.taskName())) || !type.equals(request.businessType())
                    || !(prefix + id).equals(request.idempotencyKey()) || request.parentTaskInstanceId() != null || request.priority() != 0
                    || !Map.of(parameter, id.toString()).equals(request.parameters()) || request.requiredNodeLabels() == null
                    || request.requiredNodeLabels().size() != 1 || !(request.requiredNodeLabels().get("news.node") instanceof String node) || node.isBlank()) reject();
        } catch (IllegalArgumentException | NullPointerException exception) { reject(); }
    }

    /** 与创建入口使用相同的业务范围守卫。 */
    public static void read(TaskInstanceView task) {
        boolean domain = domain(task.taskName(), task.idempotencyKey(), task.businessType());
        if (domain != newsIdentity()) reject();
    }

    /** 在线租约接口只能由独立新闻凭据访问。 */
    public static void requireIdentity() { if (!newsIdentity()) reject(); }

    private static boolean newsIdentity() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet && "news-digest-service".equals(
                servlet.getRequest().getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE));
    }

    private static boolean domain(String name, String key, String type) {
        return (name != null && name.toLowerCase(Locale.ROOT).startsWith("news_"))
                || (key != null && key.toLowerCase(Locale.ROOT).startsWith("news:"))
                || (type != null && type.toUpperCase(Locale.ROOT).startsWith("NEWS_"));
    }

    private static void reject() { throw new SchedulerException(ErrorCode.TASK_DOMAIN_FORBIDDEN, HttpStatus.FORBIDDEN, "News task domain rejected"); }
}
