package com.yuyutian.mytools.task.executor.client;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimedTaskDigestTest {

    @Test
    void shouldRejectTamperedDefinitionPayload() {
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "a".repeat(64), "scripts/main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), 1, "0".repeat(64), UUID.randomUUID(), 1L,
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), Map.of(), List.of(step));

        assertThrows(IllegalArgumentException.class, task::verifyDefinitionDigest);
    }
}
