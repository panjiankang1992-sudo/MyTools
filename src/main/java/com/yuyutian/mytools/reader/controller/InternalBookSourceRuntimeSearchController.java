package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeSearchModels;
import com.yuyutian.mytools.reader.service.BookSourceRuntimeSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 供已验证主体的Gateway调用书源并发搜索。
 */
@RestController
@RequestMapping("/internal/v1/reader/source-search")
public class InternalBookSourceRuntimeSearchController {
    private final BookSourceRuntimeSearchService service;
    private final String internalToken;

    /**
     * 创建内部书源搜索控制器。
     *
     * @param service 搜索服务
     * @param internalToken Gateway内部令牌
     */
    public InternalBookSourceRuntimeSearchController(BookSourceRuntimeSearchService service,
            @Value("${migration.gateway.internal-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    /** 启动书源搜索任务。 */
    @PostMapping
    public Result<BookSourceRuntimeSearchModels.Task> start(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody InternalStartRequest request) {
        authorize(authorization);
        return Result.success(service.start(request.ownerId(), request.keyword(), request.page(), request.mode()));
    }

    /** 查询书源搜索任务。 */
    @GetMapping("/{taskId}")
    public Result<BookSourceRuntimeSearchModels.Task> find(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable String taskId, @RequestParam @NotNull Long ownerId,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        authorize(authorization);
        return Result.success(service.find(ownerId, taskId, offset, limit));
    }

    /** 取消书源搜索任务。 */
    @DeleteMapping("/{taskId}")
    public Result<BookSourceRuntimeSearchModels.Task> cancel(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable String taskId, @RequestParam @NotNull Long ownerId) {
        authorize(authorization);
        return Result.success(service.cancel(ownerId, taskId));
    }

    private void authorize(String authorization) {
        byte[] expected = ("Bearer " + internalToken).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (internalToken.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("Gateway internal authorization failed");
        }
    }

    /** Gateway已注入所有者的搜索请求。 */
    public record InternalStartRequest(@NotNull Long ownerId, @Valid BookSourceRuntimeSearchModels.StartRequest search) {
        /** 返回关键词。 */
        public String keyword() { return search.keyword(); }
        /** 返回页码。 */
        public int page() { return search.page(); }
        /** 返回模式。 */
        public String mode() { return search.mode(); }
    }
}
