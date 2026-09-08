package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.OutboxReplayView;
import com.yuyutian.mytools.task.scheduler.service.TaskEventService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 运维控制器。
 */
@RestController
@RequestMapping("/internal/v1/outbox")
public class TaskOutboxController {

    private final TaskEventService taskEventService;

    /**
     * 创建 Outbox 运维控制器。
     *
     * @param taskEventService 任务事件服务
     */
    public TaskOutboxController(TaskEventService taskEventService) {
        this.taskEventService = taskEventService;
    }

    /**
     * 按事件标识重投死信。
     *
     * @param eventId 事件标识
     * @return 重投结果
     */
    @PostMapping("/events/{eventId}/replay")
    public OutboxReplayView replayEvent(@PathVariable UUID eventId) {
        return new OutboxReplayView(taskEventService.replayEvent(eventId, Instant.now()));
    }

    /**
     * 按任务标识重投全部死信。
     *
     * @param taskId 任务标识
     * @return 重投结果
     */
    @PostMapping("/tasks/{taskId}/replay")
    public OutboxReplayView replayTask(@PathVariable UUID taskId) {
        return new OutboxReplayView(taskEventService.replayTask(taskId, Instant.now()));
    }
}
