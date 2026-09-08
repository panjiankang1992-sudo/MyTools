package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.NodeRegistrationPolicyProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionTopologyPolicyTest {

    private final ExecutionTopologyService service = new ExecutionTopologyService(null, null,
            new NodeRegistrationPolicyProperties(true, Set.of("media"),
                    Map.of("storage.mount.managed", "present"), Set.of("runtimes", "scriptReleases")));

    @Test
    void shouldUseSchedulerOwnedLabelValues() {
        Map<String, Object> labels = service.authorizedLabels("executor-a", Map.of(
                "storage.mount.managed", "forged",
                "executor.node", "forged-node"));

        assertEquals(Map.of("storage.mount.managed", "present", "executor.node", "executor-a"), labels);
    }

    @Test
    void shouldRejectUnauthorizedLabelClusterAndCapability() {
        assertForbidden(() -> service.authorizedLabels("executor-a", Map.of("admin", true)));
        assertForbidden(() -> service.authorizedClusters(Set.of("identity")));
        assertForbidden(() -> service.authorizedCapabilities(Map.of("root", true)));
        assertForbidden(() -> service.authorizedCapabilities(
                Map.of("scriptReleases", Map.of("../unsafe:1.0.0", "a".repeat(64)))));
        assertForbidden(() -> service.authorizedCapabilities(
                Map.of("scriptReleases", Map.of("sample:1.0.0", "not-a-digest"))));
    }

    private void assertForbidden(Runnable operation) {
        SchedulerException exception = assertThrows(SchedulerException.class, operation::run);
        assertEquals(ErrorCode.NODE_REGISTRATION_FORBIDDEN, exception.errorCode());
    }
}
