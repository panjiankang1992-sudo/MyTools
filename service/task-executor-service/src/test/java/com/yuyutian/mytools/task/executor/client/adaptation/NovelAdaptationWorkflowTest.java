package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationWorkflow.Action;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NovelAdaptationWorkflowTest {
    private static final Instant NOW = Instant.parse("2026-09-10T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ORIGINAL = "Ari closed the door.";
    private static final String CANDIDATE = "Ari gently closed the door.";

    @Test
    void initialFlowRunsThreeCallsAndWaitsForReaderCompletion() {
        Fixture fixture = new Fixture("INITIAL");
        NovelAdaptationWorkflow workflow = fixture.workflow();
        assertThat(fixture.run(workflow)).isEqualTo(Action.COMPLETED);
        assertThat(fixture.phases).containsExactly(Phase.PLAN, Phase.GENERATE, Phase.CRITIC);
        assertThat(fixture.state.path("callsRemaining").intValue()).isEqualTo(2);
        assertThat(fixture.commands).containsSubsequence("reserve:PLAN", "send:PLAN", "model:PLAN", "settle:PLAN", "seal",
                "reserve:GENERATE", "send:GENERATE", "model:GENERATE", "settle:GENERATE", "progress:false",
                "reserve:CRITIC", "send:CRITIC", "model:CRITIC", "settle:CRITIC", "validate:1", "complete");
        assertThat(fixture.inputs.get(0).path("original").asText()).isEqualTo(ORIGINAL);
        assertThat(fixture.inputs.get(2).path("candidate").asText()).isEqualTo(CANDIDATE);
        assertThat(fixture.inputs).allSatisfy(input -> assertThat(input.has("baseInput")).isFalse());
        assertThat(workflow.advance().toString()).doesNotContain(ORIGINAL, CANDIDATE, "Expand");
    }

    @Test
    void firstRepairableReviewAllowsExactlyOneRepairAndSecondCritic() {
        Fixture fixture = new Fixture("OPTIMIZE"); fixture.repairable = true;
        assertThat(fixture.run(fixture.workflow())).isEqualTo(Action.COMPLETED);
        assertThat(fixture.phases).containsExactly(Phase.PLAN, Phase.GENERATE, Phase.CRITIC, Phase.REPAIR, Phase.CRITIC);
        assertThat(fixture.state.path("callsRemaining").intValue()).isZero();
        assertThat(fixture.inputs.get(1).path("baseInput").asText()).isEqualTo("Ari softly closed the door.");
        assertThat(fixture.inputs.get(3).path("feedback").path("report").path("round").intValue()).isEqualTo(1);
        assertThat(fixture.commands).contains("validate:2", "progress:true");
    }

    @Test
    void newHostWorkflowReusesSettledPlanAndCandidateWithoutResettingBudget() {
        Fixture fixture = new Fixture("REGENERATE");
        var first = fixture.workflow();
        for (int index = 0; index < 4; index++) assertThat(first.advance().action()).isEqualTo(Action.PROGRESSED);
        assertThat(fixture.attempts.size()).isEqualTo(2);
        assertThat(fixture.phases).containsExactly(Phase.PLAN, Phase.GENERATE);
        var replacement = fixture.workflow();
        assertThat(fixture.run(replacement)).isEqualTo(Action.COMPLETED);
        assertThat(fixture.phases).containsExactly(Phase.PLAN, Phase.GENERATE, Phase.CRITIC);
        assertThat(fixture.inputs).allSatisfy(input -> assertThat(input.has("baseInput")).isFalse());
    }

    @Test
    void failedSettlementReplaysSamePayloadEvenAfterOrdinaryAuthorizationIsRevoked() {
        Fixture fixture = new Fixture("INITIAL");
        var workflow = fixture.workflow(); workflow.advance();
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503))
                .doAnswer(fixture::settle).when(fixture.reader).settle(any(), any());
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        when(fixture.reader.active()).thenReturn(false);
        assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
        assertThat(workflow.advance().action()).isEqualTo(Action.STOPPED);
        var payloads = ArgumentCaptor.forClass(NovelStageOutput.Terminal.class);
        verify(fixture.reader, times(2)).settle(any(), payloads.capture());
        assertThat(payloads.getAllValues().get(0)).isSameAs(payloads.getAllValues().get(1));
        verify(fixture.provider, times(1)).execute(any(), any(), any());
        verify(fixture.reader, never()).seal(any());
    }

    @Test
    void lostSendPermissionResponseReplaysPermissionButNeverCallsModel() {
        Fixture fixture = new Fixture("INITIAL"); fixture.maySend = false;
        var workflow = fixture.workflow(); workflow.advance();
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503))
                .doAnswer(fixture::send).when(fixture.reader).sendStarted(any());
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
        assertThat(fixture.run(workflow)).isEqualTo(Action.FAILED);
        verify(fixture.reader, times(1)).reserve(any());
        verify(fixture.reader, times(2)).sendStarted(any());
        verify(fixture.provider, never()).execute(any(), any(), any());
        assertThat(fixture.attempts.get(0).path("status").asText()).isEqualTo("CALL_OUTCOME_UNKNOWN");
        verify(fixture.reader).fail(ErrorCode.UNKNOWN);
    }

    @Test
    void lostReservationResponseReusesTicketAndProviderIdentity() {
        Fixture fixture = new Fixture("INITIAL");
        var workflow = fixture.workflow(); workflow.advance();
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503))
                .doAnswer(fixture::reserve).when(fixture.reader).reserve(any());
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
        var tickets = ArgumentCaptor.forClass(NovelProviderClient.Ticket.class);
        verify(fixture.reader, times(2)).reserve(tickets.capture());
        assertThat(tickets.getAllValues().get(0)).isSameAs(tickets.getAllValues().get(1));
        verify(fixture.provider, times(1)).prepare(any(), any(), any());
        verify(fixture.provider, times(1)).execute(any(), any(), any());
    }

    @Test
    void restartWithUnknownPendingCallRecoversOnlyAndNeverResends() {
        Fixture fixture = new Fixture("INITIAL");
        fixture.claim.put("nextAction", "RECOVER_ATTEMPTS");
        var workflow = fixture.workflow();
        for (int index = 0; index < 3; index++) assertThat(workflow.advance().action()).isEqualTo(Action.WAITING);
        verify(fixture.reader, times(3)).recover();
        verify(fixture.provider, never()).prepare(any(), any(), any());
        verify(fixture.reader, never()).reserve(any());
    }

    @Test
    void pendingLedgerDuringActiveWorkflowIsNotMistakenForNewStage() {
        Fixture fixture = new Fixture("INITIAL");
        var workflow = fixture.workflow(); workflow.advance();
        fixture.attempts.add(fixture.attempt(Phase.PLAN).put("status", "SEND_STARTED"));
        fixture.state.put("callsRemaining", 4).withArray("pendingAttempts").addObject().put("status", "SEND_STARTED");
        assertThat(workflow.advance().action()).isEqualTo(Action.WAITING);
        verify(fixture.reader).recover();
        verify(fixture.provider, never()).prepare(any(), any(), any());
    }

    @Test
    void intentConflictAndBlockedSecondReviewDoNotStartFurtherGeneration() {
        Fixture conflict = new Fixture("INITIAL");
        doThrow(new ReaderAdaptationException(ErrorCode.INTENT_CONFLICT, 409)).when(conflict.reader).seal(any());
        assertThat(conflict.run(conflict.workflow())).isEqualTo(Action.FAILED);
        assertThat(conflict.phases).containsExactly(Phase.PLAN);
        verify(conflict.reader).fail(ErrorCode.INTENT_CONFLICT);
        Fixture blocked = new Fixture("INITIAL"); blocked.repairable = true; blocked.secondBlocked = true;
        assertThat(blocked.run(blocked.workflow())).isEqualTo(Action.FAILED);
        assertThat(blocked.phases).hasSize(5);
        verify(blocked.reader, never()).complete(any(), any());
    }

    @Test
    void malformedContextBudgetAndReviewFailClosedBeforeNextModelCall() {
        Fixture context = new Fixture("REGENERATE");
        ((ArrayNode) context.claim.path("context").path("fragments")).add(context.fragment("BASE_INPUT", UUID.randomUUID(), "injected body"));
        assertThatThrownBy(() -> context.workflow().advance()).isInstanceOf(ReaderAdaptationException.class);
        verify(context.provider, never()).prepare(any(), any(), any());
        Fixture budget = new Fixture("INITIAL"); var workflow = budget.workflow(); workflow.advance();
        budget.state.put("callsRemaining", 3);
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        verify(budget.provider, never()).prepare(any(), any(), any());
        Fixture review = new Fixture("INITIAL"); review.repairable = true; var repairing = review.workflow();
        for (int index = 0; index < 7; index++) repairing.advance();
        ((ObjectNode) review.view.get("review")).put("candidateAttemptId", UUID.randomUUID().toString());
        assertThatThrownBy(repairing::advance).isInstanceOf(ReaderAdaptationException.class);
        assertThat(review.phases).hasSize(3);
    }

    @Test
    void concurrentAdvanceCannotCreateASecondProviderCall() throws Exception {
        Fixture fixture = new Fixture("INITIAL"); var workflow = fixture.workflow(); workflow.advance();
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return fixture.generate(invocation); })
                .when(fixture.provider).execute(any(), any(), any());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(workflow::advance);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workflow.advance().action()).isEqualTo(Action.WAITING);
            release.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS).action()).isEqualTo(Action.PROGRESSED);
            verify(fixture.provider, times(1)).execute(any(), any(), any());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void preparationIsFollowedByFreshClaimInsteadOfTrustingPreparationBody() {
        Fixture fixture = new Fixture("INITIAL");
        fixture.claim.put("nextAction", "PREPARE_CONTEXT");
        when(fixture.reader.prepareContext()).thenAnswer(invocation -> { fixture.claim.put("nextAction", "ANALYZE"); return reply(object().put("untrusted", "body")); });
        var workflow = fixture.workflow();
        assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
        assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
        verify(fixture.reader, times(2)).claim(); verify(fixture.reader).prepareContext();
        verify(fixture.provider, never()).prepare(any(), any(), any());
    }

    @Test
    void expiredUnsettledCapabilityIsNeverReplayedOrRegenerated() {
        Fixture fixture = new Fixture("INITIAL");
        var clock = mock(Clock.class); when(clock.instant()).thenReturn(NOW);
        var workflow = new NovelAdaptationWorkflow(fixture.reader, fixture.provider, clock); workflow.advance();
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503)).when(fixture.reader).settle(any(), any());
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertThat(workflow.advance().action()).isEqualTo(Action.WAITING);
        assertThat(workflow.advance().action()).isEqualTo(Action.WAITING);
        verify(fixture.reader, times(1)).settle(any(), any());
        verify(fixture.reader).recover();
        verify(fixture.provider, times(1)).execute(any(), any(), any());
    }

    @Test
    void unexpectedProviderFailureIsSettledUnknownWithoutExternalMessage() {
        Fixture fixture = new Fixture("INITIAL");
        doThrow(new IllegalStateException("untrusted-external-body")).when(fixture.provider).execute(any(), any(), any());
        assertThat(fixture.run(fixture.workflow())).isEqualTo(Action.FAILED);
        var payload = ArgumentCaptor.forClass(NovelStageOutput.Terminal.class);
        verify(fixture.reader).settle(any(), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("CALL_OUTCOME_UNKNOWN");
        assertThat(NovelProviderJson.MAPPER.valueToTree(payload.getValue()).toString()).doesNotContain("untrusted-external-body");
        verify(fixture.provider, times(1)).execute(any(), any(), any());
    }

    @Test
    void lostCompletionResponseIsResolvedByReaderStateWithoutRepeatingModelCalls() {
        Fixture fixture = new Fixture("INITIAL"); var workflow = fixture.workflow();
        doAnswer(invocation -> {
            fixture.state.put("status", "COMPLETED").put("currentStage", "COMPLETED");
            throw new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503);
        }).when(fixture.reader).complete(any(), any());
        assertThatThrownBy(() -> fixture.run(workflow)).isInstanceOf(ReaderAdaptationException.class);
        assertThat(workflow.advance().action()).isEqualTo(Action.COMPLETED);
        verify(fixture.reader, times(1)).complete(any(), any());
        verify(fixture.provider, times(3)).execute(any(), any(), any());
    }

    @Test
    void frozenConstraintHashMismatchAndMissingDeploymentDoNotReserveModelCall() {
        Fixture fixture = new Fixture("INITIAL"); var workflow = fixture.workflow();
        workflow.advance(); workflow.advance(); workflow.advance();
        ((ObjectNode) fixture.view.get("constraints")).put("mergedSha256", "0".repeat(64));
        assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        verify(fixture.reader, times(1)).reserve(any());
        Fixture deployment = new Fixture("INITIAL");
        doThrow(new NovelProviderException(ErrorCode.DISABLED)).when(deployment.provider).prepare(any(), any(), any());
        var rejected = deployment.workflow(); rejected.advance();
        assertThatThrownBy(rejected::advance).isInstanceOf(NovelProviderException.class);
        verify(deployment.reader, never()).reserve(any());
    }

    @Test
    void durableArmPrecedesModelAndDurableTerminalPrecedesReaderSettlement() {
        Fixture fixture = new Fixture("INITIAL");
        var relay = mock(ReaderSettlementRelay.class); var lease = mock(ReaderSettlementRelay.Lease.class);
        when(relay.arm(any(), any())).thenReturn(lease);
        try (var workflow = new NovelAdaptationWorkflow(fixture.reader, fixture.provider, relay, CLOCK)) {
            workflow.advance(); workflow.advance();
            var order = inOrder(fixture.reader, fixture.provider, relay, lease);
            order.verify(fixture.reader).reserve(any()); order.verify(fixture.reader).sendStarted(any());
            order.verify(relay).arm(any(), any()); order.verify(fixture.provider).execute(any(), any(), any());
            order.verify(lease).record(any()); order.verify(fixture.reader).settle(any(), any()); order.verify(lease).acknowledge();
        }
    }

    @Test
    void armFailurePreventsSendingAndTerminalPersistenceFailureNeverRepeatsModel() {
        Fixture fixture = new Fixture("INITIAL"); var relay = mock(ReaderSettlementRelay.class);
        doThrow(new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0)).when(relay).arm(any(), any());
        try (var workflow = new NovelAdaptationWorkflow(fixture.reader, fixture.provider, relay, CLOCK)) {
            workflow.advance();
            assertThatThrownBy(workflow::advance).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
            verify(fixture.provider, never()).execute(any(), any(), any());
        }
        Fixture retry = new Fixture("INITIAL"); var store = mock(ReaderSettlementRelay.class); var lease = mock(ReaderSettlementRelay.Lease.class);
        when(store.arm(any(), any())).thenReturn(lease);
        doThrow(new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0)).doNothing().when(lease).record(any());
        try (var workflow = new NovelAdaptationWorkflow(retry.reader, retry.provider, store, CLOCK)) {
            workflow.advance();
            assertThatThrownBy(workflow::advance).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
            verify(retry.reader, never()).settle(any(), any());
            assertThat(workflow.advance().action()).isEqualTo(Action.PROGRESSED);
            verify(retry.provider, times(1)).execute(any(), any(), any());
            verify(lease, times(2)).record(any()); verify(lease).acknowledge();
        }
    }

    @Test
    void closingWorkflowReleasesSavedResultForIndependentRecovery() {
        Fixture fixture = new Fixture("INITIAL"); var relay = mock(ReaderSettlementRelay.class); var lease = mock(ReaderSettlementRelay.Lease.class);
        when(relay.arm(any(), any())).thenReturn(lease);
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503)).when(fixture.reader).settle(any(), any());
        var workflow = new NovelAdaptationWorkflow(fixture.reader, fixture.provider, relay, CLOCK);
        workflow.advance(); assertThatThrownBy(workflow::advance).isInstanceOf(ReaderAdaptationException.class);
        workflow.close(); workflow.close();
        verify(lease).record(any()); verify(lease).close(); verify(lease, never()).acknowledge();
        assertThat(workflow.advance().action()).isEqualTo(Action.STOPPED);
        verify(fixture.provider, times(1)).execute(any(), any(), any());
        assertThatThrownBy(() -> new NovelAdaptationWorkflow(fixture.reader, fixture.provider, (ReaderSettlementRelay) null))
                .hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
    }

    private static final class Fixture {
        private final ReaderAdaptationClient reader = mock(ReaderAdaptationClient.class);
        private final NovelProviderClient provider = mock(NovelProviderClient.class);
        private final ObjectNode state = object().put("status", "ANALYZING").put("currentStage", "PLAN_PENDING")
                .put("callsRemaining", 5).put("stopRequested", false);
        private final ArrayNode attempts = NovelProviderJson.MAPPER.createArrayNode();
        private final ObjectNode view = object().putNull("errorCode").putNull("review").putNull("constraints");
        private final ObjectNode claim = object().put("nextAction", "ANALYZE");
        private final List<Phase> phases = new ArrayList<>();
        private final List<JsonNode> inputs = new ArrayList<>();
        private final List<String> commands = new ArrayList<>();
        private final String rules;
        private boolean repairable;
        private boolean secondBlocked;
        private boolean maySend = true;

        private Fixture(String kind) {
            state.putArray("pendingAttempts"); view.set("state", state); view.set("attempts", attempts); claim.set("state", state);
            var context = NovelAdaptationPromptsTest.context(kind, ORIGINAL, "OPTIMIZE".equals(kind) ? "Ari softly closed the door." : null);
            rules = NovelAdaptationPromptsTest.constraints(context);
            ObjectNode inputs = object().put("intent", context.intent()).put("promptVersion", context.promptVersion()).put("constraintVersion", context.constraintVersion())
                    .put("providerDeploymentId", "fixture-v1").put("providerCode", "fixture").put("modelId", "fixture-model")
                    .put("credentialGeneration", 1).put("disclosureVersion", "fixture-disclosure-v1");
            claim.set("inputs", inputs);
            ObjectNode frozen = object().put("manifestVersion", "adaptation-context-framed-v1").put("manifestSha256", context.manifestSha256()).put("kind", kind);
            UUID chapter = UUID.randomUUID();
            var fragments = frozen.putArray("fragments").add(fragment("TARGET_ORIGINAL", chapter, ORIGINAL))
                    .add(fragment("CATALOG_METADATA", null, "fixture catalog"))
                    .add(fragment("BOOK_START_MARKER", null, "BOOK_START")).add(fragment("BOOK_END_MARKER", null, "BOOK_END"));
            if (context.baseInput() != null) fragments.add(fragment("BASE_INPUT", chapter, context.baseInput()));
            claim.set("context", frozen);
            when(reader.active()).thenReturn(true);
            when(reader.claim()).thenAnswer(invocation -> reply(claim));
            when(reader.workflow()).thenAnswer(invocation -> reply(view));
            when(provider.prepare(any(), any(), any())).thenAnswer(invocation -> {
                NovelAdaptationPrompts.Prepared prompt = invocation.getArgument(2);
                this.inputs.add(NovelProviderJson.parse(prompt.user()));
                var ticket = mock(NovelProviderClient.Ticket.class);
                when(ticket.providerAttemptId()).thenReturn(invocation.getArgument(0));
                when(ticket.phase()).thenReturn(prompt.phase()); when(ticket.requestSha256()).thenReturn(sha(prompt.user()));
                return ticket;
            });
            when(reader.reserve(any())).thenAnswer(this::reserve);
            when(reader.sendStarted(any())).thenAnswer(this::send);
            when(provider.execute(any(), any(), any())).thenAnswer(this::generate);
            when(reader.settle(any(), any())).thenAnswer(this::settle);
            when(reader.seal(any())).thenAnswer(invocation -> {
                commands.add("seal"); state.put("status", "GENERATING").put("currentStage", "GENERATE_PENDING");
                ObjectNode constraints = object(); JsonNode merged = NovelProviderJson.parse(rules);
                for (String field : new String[]{"deterministic", "supplement", "merged"}) {
                    String json = "merged".equals(field) ? rules : NovelProviderJson.encode(merged.get(field));
                    constraints.put(field + "Json", json).put(field + "Sha256", sha(json));
                }
                view.set("constraints", constraints); return reply(constraints);
            });
            when(reader.progress(anyBoolean())).thenAnswer(invocation -> {
                commands.add("progress:" + invocation.getArgument(0)); state.put("status", "VALIDATING").put("currentStage", "CRITIC_PENDING"); return reply(state);
            });
            when(reader.validate(any(), any())).thenAnswer(invocation -> {
                int round = attempts.size() == 3 ? 1 : 2; commands.add("validate:" + round);
                String outcome = round == 1 && repairable ? "REPAIRABLE" : "PASS";
                String deterministic = "{}"; String critic = "{}";
                ObjectNode report = object().put("version", "adaptation-validation-v1").put("round", round).put("outcome", outcome)
                        .put("contentPolicyOutcome", "PASS").put("candidateSha256", sha(CANDIDATE)).put("constraintSha256", sha(rules))
                        .put("deterministicSha256", sha(deterministic)).put("criticSha256", sha(critic));
                String reportJson = NovelProviderJson.encode(report);
                ObjectNode review = object().put("validationId", UUID.randomUUID().toString()).put("candidateAttemptId", invocation.getArgument(0).toString())
                        .put("criticAttemptId", invocation.getArgument(1).toString()).put("round", round).put("outcome", outcome).put("contentPolicyOutcome", "PASS")
                        .put("deterministicJson", deterministic).put("criticJson", critic).put("reportJson", reportJson).put("reportSha256", sha(reportJson));
                view.set("review", review);
                state.put("status", "REPAIRABLE".equals(outcome) ? "REPAIRING" : "PERSISTING")
                        .put("currentStage", "REPAIRABLE".equals(outcome) ? "REPAIR_PENDING" : "PERSISTING");
                if (round == 2 && secondBlocked) { state.put("status", "FAILED").put("currentStage", "FAILED"); view.put("errorCode", ErrorCode.CONSTRAINTS.code()); }
                return reply(review);
            });
            when(reader.complete(any(), any())).thenAnswer(invocation -> { commands.add("complete"); state.put("status", "COMPLETED").put("currentStage", "COMPLETED"); return reply(state); });
            when(reader.fail(any())).thenAnswer(invocation -> { state.put("status", "FAILED").put("currentStage", "FAILED"); view.put("errorCode", ((ErrorCode) invocation.getArgument(0)).code()); return reply(state); });
        }

        private NovelAdaptationWorkflow workflow() { return new NovelAdaptationWorkflow(reader, provider, CLOCK); }
        private Action run(NovelAdaptationWorkflow workflow) {
            for (int index = 0; index < 25; index++) {
                var result = workflow.advance();
                if (result.action() != Action.PROGRESSED) return result.action();
            }
            throw new AssertionError("Workflow did not converge");
        }
        private ObjectNode fragment(String role, UUID chapter, String text) { return object().put("role", role).put("sourceChapterId", chapter == null ? null : chapter.toString()).put("text", text); }
        private ObjectNode attempt(Phase phase) {
            return object().put("attemptId", UUID.randomUUID().toString()).put("providerAttemptId", UUID.randomUUID().toString())
                    .put("attemptNo", attempts.size() + 1).put("callKind", phase.name()).put("status", "REGISTERED").put("archivedOnly", false)
                    .putNull("errorCode").putNull("outputText").putNull("structuredJson");
        }
        private Object reserve(org.mockito.invocation.InvocationOnMock invocation) {
            NovelProviderClient.Ticket ticket = invocation.getArgument(0);
            Phase phase = ticket.phase();
            ObjectNode row = attempt(ticket.phase()).put("providerAttemptId", ticket.providerAttemptId().toString()); attempts.add(row);
            state.put("callsRemaining", 5 - attempts.size()).put("currentStage", ticket.phase().name());
            state.withArray("pendingAttempts").addObject().put("status", "REGISTERED"); commands.add("reserve:" + ticket.phase());
            var reservation = mock(ReaderAdaptationClient.Reservation.class);
            when(reservation.attemptId()).thenReturn(UUID.fromString(row.get("attemptId").asText())); when(reservation.phase()).thenReturn(phase);
            return reservation;
        }
        private Object send(org.mockito.invocation.InvocationOnMock invocation) {
            ReaderAdaptationClient.Reservation reservation = invocation.getArgument(0);
            ObjectNode row = (ObjectNode) attempts.get(attempts.size() - 1); row.put("status", "SEND_STARTED");
            var capability = mock(ReaderAdaptationClient.SendCapability.class);
            when(capability.expiresAt()).thenReturn(NOW.plusSeconds(300));
            when(capability.providerPermit()).thenReturn(new NovelProviderModels.Permit(UUID.fromString(row.path("providerAttemptId").asText()),
                    "a".repeat(64), maySend, NOW.plusSeconds(2), NOW.plusSeconds(180)));
            commands.add("send:" + reservation.phase()); return capability;
        }
        private Object generate(org.mockito.invocation.InvocationOnMock invocation) {
            Phase phase = ((NovelProviderClient.Ticket) invocation.getArgument(0)).phase(); phases.add(phase); commands.add("model:" + phase);
            boolean plain = phase == Phase.GENERATE || phase == Phase.REPAIR;
            return new NovelStageOutput.Terminal("SUCCEEDED", plain ? CANDIDATE : null, plain ? null : "{}", "stop", null, null, null, 200, null, null);
        }
        private Object settle(org.mockito.invocation.InvocationOnMock invocation) {
            NovelStageOutput.Terminal terminal = invocation.getArgument(1);
            ObjectNode row = (ObjectNode) attempts.get(attempts.size() - 1);
            row.put("status", terminal.status()).put("outputText", terminal.outputText()).put("structuredJson", terminal.structuredJson()).put("errorCode", terminal.errorCode());
            state.withArray("pendingAttempts").removeAll(); commands.add("settle:" + row.path("callKind").asText()); return reply(row);
        }
    }

    private static ObjectNode object() { return NovelProviderJson.MAPPER.createObjectNode(); }
    private static String sha(String value) { return NovelProviderJson.sha(value.getBytes(StandardCharsets.UTF_8)); }
    private static ReaderAdaptationClient.Reply reply(JsonNode value) {
        var reply = mock(ReaderAdaptationClient.Reply.class); when(reply.body()).thenAnswer(invocation -> value.deepCopy()); return reply;
    }
}
