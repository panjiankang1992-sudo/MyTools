package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskScheduleCursorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于数据库游标和租约触发定时任务实例。
 */
@Service
public class CronTaskTriggerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CronTaskTriggerService.class);
    private final TaskDefinitionRepository definitionRepository;
    private final TaskScheduleCursorRepository cursorRepository;
    private final TaskInstanceRepository instanceRepository;
    private final TaskInstanceService instanceService;
    private final String schedulerInstanceId = UUID.randomUUID().toString();
    private final int maxCatchUp;
    private final long leaseSeconds;

    /**
     * 创建定时任务触发服务。
     *
     * @param definitionRepository 定义仓储
     * @param cursorRepository 游标仓储
     * @param instanceRepository 实例仓储
     * @param instanceService 实例服务
     * @param maxCatchUp 单轮最大补偿次数
     * @param leaseSeconds 调度租约秒数
     */
    public CronTaskTriggerService(TaskDefinitionRepository definitionRepository,
                                  TaskScheduleCursorRepository cursorRepository,
                                  TaskInstanceRepository instanceRepository,
                                  TaskInstanceService instanceService,
                                  @Value("${task.scheduler.cron-max-catch-up:100}") int maxCatchUp,
                                  @Value("${task.scheduler.cron-lease-seconds:30}") long leaseSeconds) {
        this.definitionRepository = definitionRepository;
        this.cursorRepository = cursorRepository;
        this.instanceRepository = instanceRepository;
        this.instanceService = instanceService;
        this.maxCatchUp = maxCatchUp;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 周期扫描并触发所有到期定义。
     */
    @Scheduled(fixedDelayString = "${task.scheduler.cron-scan-delay-ms:1000}")
    public void triggerDueTasks() {
        triggerDueTasks(Instant.now());
    }

    /**
     * 在指定时间触发到期定义，供确定性测试与管理操作使用。
     *
     * @param now 当前时间
     */
    public void triggerDueTasks(Instant now) {
        for (TaskDefinitionView definition : definitionRepository.findLatestEnabledScheduled()) {
            try {
                processDefinition(definition, now);
            } catch (RuntimeException exception) {
                LOGGER.warn("Cron task trigger failed: taskName={}, error={}",
                        definition.name(), exception.getMessage());
            }
        }
    }

    private void processDefinition(TaskDefinitionView definition, Instant now) {
        Instant firstFire = CronTriggerPlanner.nextFire(definition, definition.createdAt().minusSeconds(1));
        cursorRepository.initialize(definition.id(), firstFire);
        var cursor = cursorRepository.find(definition.id()).orElseThrow();
        if (cursor.nextFireAt().isAfter(now)
                || !cursorRepository.claim(definition.id(), schedulerInstanceId, now, now.plusSeconds(leaseSeconds))) {
            return;
        }
        CronTriggerPlanner.TriggerPlan plan = CronTriggerPlanner.plan(
                definition, cursor.nextFireAt(), now, maxCatchUp);
        for (Instant scheduledAt : plan.fireTimes()) {
            applyOverlapPolicy(definition);
            if (!"SKIP".equals(definition.overlapPolicy()) || instanceRepository.countActive(definition.id()) == 0) {
                createInstance(definition, scheduledAt);
            }
        }
        cursorRepository.advance(definition.id(), schedulerInstanceId, plan.lastProcessedAt(), plan.nextFireAt());
    }

    private void applyOverlapPolicy(TaskDefinitionView definition) {
        if (!"REPLACE".equals(definition.overlapPolicy())) {
            return;
        }
        // REPLACE 先请求取消旧实例，新实例随后进入队列等待旧租约退出。
        instanceRepository.findActive(definition.id()).forEach(instance -> instanceService.cancel(instance.id()));
    }

    private void createInstance(TaskDefinitionView definition, Instant scheduledAt) {
        String scheduledText = DateTimeFormatter.ISO_INSTANT.format(scheduledAt);
        instanceService.create(new CreateTaskRequest(
                definition.name(), "cron:" + definition.id() + ":" + scheduledAt.toEpochMilli(),
                "SCHEDULED_TASK", definition.id().toString(), null, 50,
                Map.of("scheduledAt", scheduledText, "definitionVersion", definition.version())
        ));
    }

}
