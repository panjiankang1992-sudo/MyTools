package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskScheduleCursorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时分页补齐历史定时定义的调度游标。
 */
@Component
public class CronScheduleCursorInitializer implements ApplicationRunner {

    private final TaskDefinitionRepository definitionRepository;
    private final TaskScheduleCursorRepository cursorRepository;
    private final int batchSize;

    /**
     * 创建定时游标初始化器。
     *
     * @param definitionRepository 定义仓储
     * @param cursorRepository 游标仓储
     * @param batchSize 单批初始化上限
     */
    public CronScheduleCursorInitializer(TaskDefinitionRepository definitionRepository,
                                         TaskScheduleCursorRepository cursorRepository,
                                         @Value("${task.scheduler.cron-bootstrap-batch-size:128}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Cron bootstrap batch size must be between 1 and 1000");
        }
        this.definitionRepository = definitionRepository;
        this.cursorRepository = cursorRepository;
        this.batchSize = batchSize;
    }

    /**
     * 分页初始化所有缺失的持久游标。
     *
     * @param arguments 应用启动参数
     */
    @Override
    public void run(ApplicationArguments arguments) {
        while (true) {
            var definitions = definitionRepository.findScheduledWithoutCursor(batchSize);
            if (definitions.isEmpty()) {
                return;
            }
            for (var definition : definitions) {
                // 幂等 INSERT 允许多个 Scheduler 副本同时启动并收敛到同一首次触发时间。
                cursorRepository.initialize(definition.id(),
                        CronTriggerPlanner.nextFire(definition, definition.createdAt().minusSeconds(1)));
            }
        }
    }
}
