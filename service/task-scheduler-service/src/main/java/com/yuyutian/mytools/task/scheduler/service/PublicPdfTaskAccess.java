package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.config.TaskSecurityProperties;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * 公开工具箱 PDF 任务域的访问守卫。
 *
 * 与只保护单一方向的既有守卫不同，这里做**双向**限制：
 * 其他业务身份不得创建、读取、取消或读取公开 PDF 任务的结果；
 * 公开实例也不得操作公开 PDF 域之外的任何任务。
 *
 * 该守卫只约束调度器内的任务域，不替代存储隔离、匿名 owner 命名空间与计算沙箱。
 */
@Component
public final class PublicPdfTaskAccess {

    /** 公开 PDF 实例在调度器中的服务身份。 */
    public static final String SERVICE_ID = "public-pdf-service";
    /** 公开 PDF 任务定义名，与 pdf-service 的 pdf.task-name 配置对应。 */
    public static final String TASK_NAME = "toolbox_pdf_process";
    /** 公开 PDF 幂等键前缀，与 pdf-service 的 pdf.idempotency-prefix 配置对应。 */
    public static final String IDEMPOTENCY_PREFIX = "public-pdf:";

    private final TaskSecurityProperties security;

    /**
     * 注入已认证服务身份配置。
     *
     * @param security 任务接口安全配置
     */
    public PublicPdfTaskAccess(TaskSecurityProperties security) {
        this.security = security;
    }

    /**
     * 判断任务名与幂等键是否属于公开 PDF 任务域。
     *
     * @param taskName 任务定义名，可为空
     * @param idempotencyKey 幂等键，可为空
     * @return 属于公开 PDF 域时为 true
     */
    public static boolean isPublicDomain(String taskName, String idempotencyKey) {
        if (taskName != null && TASK_NAME.equalsIgnoreCase(taskName)) {
            return true;
        }
        // 幂等键按大小写不敏感前缀判断，避免大小写折叠绕过域检查。
        return idempotencyKey != null && idempotencyKey.toLowerCase(Locale.ROOT).startsWith(IDEMPOTENCY_PREFIX);
    }

    /**
     * 创建任务前校验域归属，两个方向都不允许越界。
     *
     * @param request 创建请求
     */
    public void requireCreate(CreateTaskRequest request) {
        if (request == null) {
            return;
        }
        requireDomain(isPublicDomain(request.taskName(), request.idempotencyKey()), "create");
    }

    /**
     * 读取、取消与结果访问共用同一守卫。
     *
     * @param task 任务实例，可为空
     */
    public void requireAccess(TaskInstanceView task) {
        if (task == null) {
            return;
        }
        requireDomain(isPublicDomain(task.taskName(), task.idempotencyKey()), "access");
    }

    /**
     * 当前请求是否由已配置的公开 PDF 身份发起。
     * 认证未开启或未配置该身份时一律视为非公开身份，因此公开请求会失败关闭。
     */
    private boolean authenticatedAsPublicPdf() {
        if (!security.required() || !security.businessClients().containsKey(SERVICE_ID)) {
            return false;
        }
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                && SERVICE_ID.equals(servlet.getRequest().getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE));
    }

    /**
     * 执行双向域校验。
     *
     * @param publicTask 目标任务是否属于公开 PDF 域
     * @param action 操作名称，仅用于错误描述
     */
    private void requireDomain(boolean publicTask, String action) {
        boolean publicClient = authenticatedAsPublicPdf();
        if (publicTask && !publicClient) {
            // 其他业务身份（含共享令牌与未开启认证）不得触碰公开任务。
            throw new SchedulerException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED,
                    "Public PDF service identity is required to " + action + " public PDF tasks");
        }
        if (publicClient && !publicTask) {
            // 公开实例只能操作公开 PDF 域，不能借共享调度器触达私有任务。
            throw new SchedulerException(ErrorCode.TASK_DOMAIN_FORBIDDEN, HttpStatus.FORBIDDEN,
                    "Public PDF service may only " + action + " public PDF tasks");
        }
    }
}
