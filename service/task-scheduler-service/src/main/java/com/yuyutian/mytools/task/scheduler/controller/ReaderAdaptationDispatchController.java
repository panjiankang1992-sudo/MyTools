package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.ReaderAdaptationDispatchView;
import com.yuyutian.mytools.task.scheduler.service.ReaderAdaptationDispatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 独立 Reader 服务身份调用的业务派发接口，既有业务令牌过滤链覆盖此路径。 */
@RestController
@RequestMapping("/api/v1/task-instances/reader-adaptations/{adaptationId}")
public class ReaderAdaptationDispatchController {
    private final ReaderAdaptationDispatchService service;

    /** 注入受限业务键服务。 */
    public ReaderAdaptationDispatchController(ReaderAdaptationDispatchService service) {
        this.service = service;
    }

    /** 幂等提交，仅路径 ID 可由 Reader 传入。 */
    @PostMapping
    public ResponseEntity<ReaderAdaptationDispatchView> submit(@PathVariable UUID adaptationId, @RequestBody(required = false) String body) {
        requireEmpty(body);
        return response(HttpStatus.ACCEPTED, service.submit(adaptationId));
    }

    /** 按业务键查询，任务未创建时返回空任务身份而非新建。 */
    @GetMapping
    public ResponseEntity<ReaderAdaptationDispatchView> find(@PathVariable UUID adaptationId) {
        return response(HttpStatus.OK, service.find(adaptationId));
    }

    /** 持久取消屏障，阻止尚在网络中的迟到创建。 */
    @PostMapping("/cancel")
    public ResponseEntity<ReaderAdaptationDispatchView> cancel(@PathVariable UUID adaptationId, @RequestBody(required = false) String body) {
        requireEmpty(body);
        return response(HttpStatus.OK, service.cancel(adaptationId));
    }

    private static ResponseEntity<ReaderAdaptationDispatchView> response(HttpStatus status, ReaderAdaptationDispatchView body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store, private").body(body);
    }

    private static void requireEmpty(String body) {
        if (body != null && !body.isBlank()) {
            throw new SchedulerException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Adaptation dispatch body must be empty");
        }
    }
}
