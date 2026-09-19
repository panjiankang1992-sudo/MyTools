package com.yuyutian.mytools.task.executor.client;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void shouldKeepDefinitionDigestCompatibleWhenOrchestrationMetadataIsPresent() throws Exception {
        UUID definitionId = UUID.randomUUID();
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "a".repeat(64), "scripts/main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        String digest = definitionDigest(definitionId, "sample_task", step);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                definitionId, 1, digest, UUID.randomUUID(), 1L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), true, Map.of(), List.of(step));

        assertDoesNotThrow(task::verifyDefinitionDigest);
    }

    private String definitionDigest(UUID definitionId, String taskName, ClaimedStep step) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, definitionId.toString());
        update(digest, "1");
        update(digest, taskName);
        update(digest, step.stepDefinitionId().toString());
        update(digest, step.name());
        update(digest, step.stepKind());
        update(digest, step.scriptPackage());
        update(digest, step.scriptVersion());
        update(digest, step.scriptReleaseDigest());
        update(digest, step.entrypoint());
        step.argumentsTemplate().forEach(value -> update(digest, value));
        update(digest, Long.toString(step.timeoutSeconds()));
        update(digest, step.failurePolicy());
        update(digest, Integer.toString(step.sequenceNumber()));
        update(digest, Integer.toString(step.maxAttempts()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
