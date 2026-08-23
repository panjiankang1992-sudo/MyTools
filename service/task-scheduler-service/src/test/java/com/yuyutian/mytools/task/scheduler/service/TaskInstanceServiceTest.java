package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskInstanceServiceTest {

    @Test
    void shouldCreateIdempotentlyAndCancel() {
        TaskInstanceService service = new TaskInstanceService();
        CreateTaskRequest request = new CreateTaskRequest(
                "media_generate_tags", "asset_1_v1", "MEDIA_ASSET", "1", null, 50, Map.of("assetId", "1")
        );

        var first = service.create(request);
        var second = service.create(request);

        assertEquals(first.id(), second.id());
        assertEquals(TaskStatus.CANCELLING, service.cancel(first.id()).status());
    }
}
