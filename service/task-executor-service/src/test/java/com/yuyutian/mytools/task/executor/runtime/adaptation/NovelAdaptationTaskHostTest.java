package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.config.ExecutorNovelAdaptationProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorWorkloadTlsProperties;
import com.yuyutian.mytools.task.executor.runtime.ScriptProcessRunner;
import com.yuyutian.mytools.task.executor.runtime.ScriptReleaseVerifier;
import com.yuyutian.mytools.task.executor.runtime.WorkloadAuthorizationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovelAdaptationTaskHostTest {
    private static final String TASK = "reader_adapt_novel_chapter";

    @Test
    void defaultPropertiesAndHostDoNotLoadCredentialsFilesOrContactServices() {
        var properties = properties(Map.of());
        assertFalse(properties.enabled()); assertFalse(properties.recoveryEnabled());
        assertFalse(properties.isolationVerified()); assertEquals("", properties.modelId());
        var tls = mock(ExecutorWorkloadTls.class); var executor = mock(ExecutorProperties.class);
        var tlsProperties = mock(ExecutorWorkloadTlsProperties.class); var registry = mock(WorkloadAuthorizationRegistry.class);
        var processes = mock(ScriptProcessRunner.class); var releases = mock(ScriptReleaseVerifier.class);
        try (var host = new NovelAdaptationTaskHost(properties, executor, tls, tlsProperties, registry, processes, releases)) {
            assertFalse(host.dedicated()); host.recover();
            var error = assertThrows(ReaderAdaptationException.class, () -> host.validate(task(List.of(step()))));
            assertEquals(ErrorCode.DISABLED, error.error());
        }
        verifyNoInteractions(tls, executor, tlsProperties, registry, processes, releases);
    }

    @Test
    void enablingGenerationRequiresRecoveryAndIsolationGate() {
        assertThrows(RuntimeException.class, () -> properties(Map.of("enabled", "true")));
        assertThrows(RuntimeException.class, () -> properties(Map.of("enabled", "true", "recovery-enabled", "true")));
        assertThrows(RuntimeException.class, () -> properties(Map.of("enabled", "true", "isolation-verified", "true")));
        assertTrue(properties(Map.of("enabled", "true", "recovery-enabled", "true", "isolation-verified", "true")).enabled());
    }

    @Test
    void propertyDiagnosticsDoNotExposeConfiguredIdentityOrPaths() {
        var properties = properties(Map.of("provider-credential-file", "/private/test-only-provider-key",
                "relay-key-file", "/private/test-only-relay-key", "reader-url", "https://reader.example",
                "model-id", "test-only-private-model"));
        assertEquals("ExecutorNovelAdaptationProperties[enabled=false, recoveryEnabled=false, configuration=REDACTED]", properties.toString());
    }

    @Test
    void recoveryCannotStartWithoutNativeTlsOnDedicatedNode() {
        var properties = properties(Map.of("recovery-enabled", "true"));
        var tls = mock(ExecutorWorkloadTls.class);
        var error = assertThrows(ReaderAdaptationException.class, () -> new NovelAdaptationTaskHost(properties,
                mock(ExecutorProperties.class), tls, mock(ExecutorWorkloadTlsProperties.class),
                mock(WorkloadAuthorizationRegistry.class), mock(ScriptProcessRunner.class), mock(ScriptReleaseVerifier.class)));
        assertEquals(ErrorCode.DISABLED, error.error());
        verify(tls).thumbprint(); verifyNoMoreInteractions(tls);
    }

    @Test
    void fixedSingleStepContractIsAcceptedWithoutBypassingRuntimeGates() {
        assertDoesNotThrow(() -> NovelAdaptationTaskHost.validateTaskContract(task(List.of(step()))));
    }

    @Test
    void emptyDuplicateNullOrCompensationStepsCannotBypassContract() {
        assertThrows(ReaderAdaptationException.class, () -> NovelAdaptationTaskHost.validateTaskContract(null));
        for (List<ClaimedStep> steps : List.of(List.<ClaimedStep>of(), List.of(step(), step()),
                java.util.Collections.<ClaimedStep>singletonList(null))) {
            assertThrows(ReaderAdaptationException.class, () -> NovelAdaptationTaskHost.validateTaskContract(task(steps)));
        }
        ClaimedTask good = task(List.of(step()));
        var nested = new ClaimedTask(good.executionId(), good.taskInstanceId(), UUID.randomUUID(), TASK, good.leaseToken(),
                good.fencingToken(), good.leaseUntil(), good.deadlineAt(), good.parameters(), good.steps());
        var children = new ClaimedTask(good.executionId(), good.taskInstanceId(), null, TASK, good.leaseToken(),
                good.fencingToken(), good.leaseUntil(), good.deadlineAt(), true, good.parameters(), good.steps());
        var extra = new ClaimedTask(good.executionId(), good.taskInstanceId(), null, TASK, good.leaseToken(),
                good.fencingToken(), good.leaseUntil(), good.deadlineAt(), Map.of("adaptationId", "id", "body", "test-only"), good.steps());
        var casing = new ClaimedTask(good.executionId(), good.taskInstanceId(), null, TASK.toUpperCase(java.util.Locale.ROOT), good.leaseToken(),
                good.fencingToken(), good.leaseUntil(), good.deadlineAt(), good.parameters(), good.steps());
        for (var invalid : List.of(nested, children, extra, casing)) {
            assertThrows(ReaderAdaptationException.class, () -> NovelAdaptationTaskHost.validateTaskContract(invalid));
        }
    }

    @Test
    void everyExecutionContractFieldMustMatchPublishedBrokerPackage() {
        ClaimedStep good = step();
        List<ClaimedStep> invalid = new ArrayList<>();
        for (int field = 0; field < 11; field++) {
            invalid.add(new ClaimedStep(good.stepDefinitionId(), field == 0 ? "other" : good.name(),
                    field == 1 ? "COMPENSATION" : good.stepKind(), field == 2 ? "generic" : good.scriptPackage(),
                    field == 3 ? "2.0.0" : good.scriptVersion(), field == 4 ? null : good.scriptReleaseDigest(),
                    field == 5 ? "main.py" : good.entrypoint(), field == 6 ? List.of("--body") : good.argumentsTemplate(),
                    field == 7 ? 901 : good.timeoutSeconds(), field == 8 ? "IGNORE" : good.failurePolicy(),
                    field == 9 ? 2 : good.sequenceNumber(), field == 10 ? 2 : good.maxAttempts()));
        }
        for (var step : invalid) {
            assertThrows(ReaderAdaptationException.class, () -> NovelAdaptationTaskHost.validateTaskContract(task(List.of(step))));
        }
    }

    private static ClaimedStep step() {
        return new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", TASK, "1.0.0", "a".repeat(64),
                "scripts/main.py", List.of(), 900, "FAIL_TASK", 1, 1);
    }

    private static ClaimedTask task(List<ClaimedStep> steps) {
        return new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, TASK, UUID.randomUUID(), 1,
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(900), Map.of("adaptationId", UUID.randomUUID().toString()), steps);
    }

    private static ExecutorNovelAdaptationProperties properties(Map<String, String> values) {
        Map<String, String> source = new java.util.HashMap<>();
        values.forEach((key, value) -> source.put("executor.novel-adaptation." + key, value));
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("executor.novel-adaptation", Bindable.of(ExecutorNovelAdaptationProperties.class));
    }
}
