package com.yuyutian.mytools.reader.controller.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionViews;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationExecutionService;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.authorization.ReaderWorkloadAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Executor 专属内部执行入口，既有 Reader 静态服务令牌不能替代本路由的 mTLS assertion。 */
@RestController
@RequestMapping("/api/internal/v1/chapter-adaptations/{adaptationId}/executions/{executionId}")
public class AdaptationExecutionController {
    private final ReaderWorkloadAuthorizer authorizer;
    private final AdaptationExecutionService service;

    /** 注入每请求授权器和受控业务处理器。 */
    public AdaptationExecutionController(ReaderWorkloadAuthorizer authorizer, AdaptationExecutionService service) {
        this.authorizer = authorizer;
        this.service = service;
    }

    /** 不接受正文或 owner，单调接管由已验签的 fence 决定。 */
    @PostMapping("/claim")
    public AdaptationExecutionViews.Claim claim(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        return service.claim(authorize(adaptationId, executionId, request));
    }

    /** 只从绑定章节读取原文与必要邻章，不能由请求替换正文。 */
    @PostMapping("/prepare-context")
    public AdaptationExecutionViews.Context prepare(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        return service.prepare(authorize(adaptationId, executionId, request));
    }

    /** 当前有效执行查询进度、取消状态及持久预算。 */
    @GetMapping("/status")
    public AdaptationExecutionViews.State status(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        return service.status(authorize(adaptationId, executionId, request));
    }

    private WorkloadAuthorization authorize(String resource, String execution, HttpServletRequest request) {
        UUID resourceId = canonical(resource);
        UUID executionId = canonical(execution);
        var authorization = authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, resourceId, executionId);
        // 空请求体契约拒绝 owner、任意正文和路由外参数，不让框架先缓冲不受限正文。
        if (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid();
        return authorization;
    }

    private static UUID canonical(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw invalid();
            return result;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
}
