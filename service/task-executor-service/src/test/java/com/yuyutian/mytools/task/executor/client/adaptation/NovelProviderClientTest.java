package com.yuyutian.mytools.task.executor.client.adaptation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Deployment;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Permit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelProviderClientTest {
    private static final Deployment DEPLOYMENT = new Deployment("fixture-deployment", "fixture-model", 1, 1_048_576, 1000, false);
    @TempDir Path temporary;

    @Test
    void v2FullBodyBypassesGlobalInsertionModeAndDoesNotRequestJsonForProse() throws Exception {
        String original = "Ari closed the door.";
        String body = "Under Ari's hand, the door swung shut.";
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            bytes.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "application/json", success(body));
        }); NovelProviderClient client = new NovelProviderClient(DEPLOYMENT, key("fixture-" + UUID.randomUUID()),
                URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/chat/completions"),
                Clock.systemUTC(), Duration.ofSeconds(2), true, true, true)) {
            var old = NovelAdaptationPromptsTest.context("INITIAL", original, null);
            var context = new NovelAdaptationPrompts.Context(old.kind(), old.intent(), "novel-adaptation-v2", "story-constraints-v2",
                    original, old.originalSha256(), old.manifestSha256(), null, true, null, true, null);
            var prepared = new NovelAdaptationPrompts().generate(context,
                    NovelAdaptationPromptsTest.constraints(old).replace("story-constraints-v1", "story-constraints-v2"));
            var ticket = client.prepare(UUID.randomUUID(), DEPLOYMENT.scope(), prepared);
            var result = client.execute(ticket, permit(ticket, 5000), () -> true);
            assertThat(result.status()).isEqualTo("SUCCEEDED"); assertThat(result.outputText()).isEqualTo(body);
            var request = NovelProviderJson.parse(new String(bytes.get(), StandardCharsets.UTF_8));
            assertThat(request.has("response_format")).isFalse();
            assertThat(request.toString()).doesNotContain("insertionSlots", "maximumAdditions");
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void boundedGenerationAndChecklistUseOneExactBoundRequestEach() throws Exception {
        var context = NovelAdaptationPromptsTest.context("INITIAL", NovelInsertionProtocolTest.ORIGINAL, null);
        var prompts = new NovelAdaptationPrompts();
        for (var phase : List.of(NovelProviderModels.Phase.PLAN, NovelProviderModels.Phase.GENERATE, NovelProviderModels.Phase.CRITIC)) {
            AtomicReference<byte[]> bytes = new AtomicReference<>();
            var wire = phase == NovelProviderModels.Phase.PLAN ? NovelPlanReferencesTest.wire()
                    : phase == NovelProviderModels.Phase.GENERATE ? NovelInsertionProtocolTest.wire(2, "Mist cooled the stone.") : NovelCriticChecklistTest.wire();
            try (Fixture fixture = new Fixture(exchange -> {
                bytes.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "application/json", success(NovelProviderJson.encode(wire)));
            }); NovelProviderClient client = new NovelProviderClient(DEPLOYMENT, key("fixture-" + UUID.randomUUID()),
                    URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/chat/completions"),
                    Clock.systemUTC(), Duration.ofSeconds(2), true, true, true)) {
                String constraints = NovelProviderJson.encode(NovelInsertionProtocolTest.input().get("constraints"));
                var prepared = phase == NovelProviderModels.Phase.PLAN ? prompts.plan(context) : phase == NovelProviderModels.Phase.GENERATE ? prompts.generate(context, constraints)
                        : prompts.critic(context, constraints, "Ari waited.");
                var ticket = client.prepare(UUID.randomUUID(), DEPLOYMENT.scope(), prepared);
                var result = client.execute(ticket, permit(ticket, 5000), () -> true);
                assertThat(result.status()).isEqualTo("SUCCEEDED");
                assertThat(NovelProviderJson.sha(bytes.get())).isEqualTo(ticket.requestSha256());
                var request = NovelProviderJson.parse(new String(bytes.get(), StandardCharsets.UTF_8));
                assertThat(request.at("/response_format/type").asText()).isEqualTo("json_object");
                var input = NovelProviderJson.parse(request.at("/messages/1/content").asText());
                assertThat(input.path("providerWireVersion").asText()).isEqualTo(phase == NovelProviderModels.Phase.PLAN ? "adaptation-plan-refs-v1"
                        : phase == NovelProviderModels.Phase.GENERATE ? "adaptation-insert-v1" : "adaptation-critic-checklist-v1");
                if (phase == NovelProviderModels.Phase.PLAN) assertThat(input.get("sourceEvidence")).isEqualTo(NovelPlanReferences.catalog(context.original()));
                if (phase == NovelProviderModels.Phase.GENERATE) assertThat(result.outputText())
                        .contains("Mist cooled the stone.").endsWith("Ari waited for dawn.");
                assertThatThrownBy(() -> client.execute(ticket, permit(ticket, 5000), () -> true)).hasMessage("READER_044");
                assertThat(fixture.calls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void transportDiagnosticsUseBoundedFixedCategoriesAndNeverExceptionMessages() {
        String privateMessage = "fixture-secret-do-not-log";
        Map<Throwable, NovelProviderClient.TransportFailure> failures = Map.of(
                new java.net.http.HttpConnectTimeoutException(privateMessage), NovelProviderClient.TransportFailure.CONNECT_TIMEOUT,
                new javax.net.ssl.SSLHandshakeException(privateMessage), NovelProviderClient.TransportFailure.TLS_FAILURE,
                new java.net.http.HttpTimeoutException(privateMessage), NovelProviderClient.TransportFailure.RESPONSE_TIMEOUT,
                new java.net.ConnectException(privateMessage), NovelProviderClient.TransportFailure.CONNECT_FAILURE,
                new IOException(privateMessage), NovelProviderClient.TransportFailure.IO_FAILURE,
                new IllegalStateException(privateMessage), NovelProviderClient.TransportFailure.OTHER);
        failures.forEach((failure, expected) -> {
            var wrapped = new java.util.concurrent.ExecutionException(privateMessage, failure);
            assertThat(NovelProviderClient.transportFailure(wrapped)).isEqualTo(expected);
            assertThat(NovelProviderClient.transportFailure(wrapped).name()).doesNotContain(privateMessage);
        });
        var cycle = new RuntimeException(privateMessage);
        var child = new RuntimeException(privateMessage, cycle);
        cycle.initCause(child);
        assertThat(NovelProviderClient.transportFailure(cycle)).isEqualTo(NovelProviderClient.TransportFailure.OTHER);
        assertThat(NovelProviderClient.transportFailure(null)).isEqualTo(NovelProviderClient.TransportFailure.OTHER);
    }

    @Test
    void optInCompatibilityUsesBoundOriginalAndNeverTransmitsTheCredentialInJson() throws Exception {
        String secret = "fixture-" + UUID.randomUUID();
        AtomicReference<byte[]> requestBytes = new AtomicReference<>();
        var plan = NovelStageOutputTest.plan();
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.at("/entities/0")).put("sourceStart", 90).put("sourceEnd", 99);
        try (Fixture fixture = new Fixture(exchange -> {
            requestBytes.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "application/json", success("<think>private fixture</think>\n" + NovelProviderJson.encode(plan)));
        }); NovelProviderClient client = new NovelProviderClient(DEPLOYMENT, key(secret),
                URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/chat/completions"), Clock.systemUTC(), Duration.ofSeconds(2), true)) {
            var ticket = ticket(client);
            var result = client.execute(ticket, permit(ticket, 5000), () -> true);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.structuredJson()).doesNotContain("private fixture", secret);
            assertThat(NovelProviderJson.parse(result.structuredJson()).at("/entities/0/sourceStart").intValue()).isZero();
            var request = NovelProviderJson.parse(new String(requestBytes.get(), StandardCharsets.UTF_8));
            assertThat(request.at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(NovelProviderJson.sha(requestBytes.get())).isEqualTo(ticket.requestSha256());
            assertThat(request.toString()).doesNotContain(secret);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void quoteProtocolBindsTheExactTransmittedPromptAndNormalizesPlanAndCritic() throws Exception {
        var context = NovelAdaptationPromptsTest.context("INITIAL", "Ari closed the door.", null);
        var prompts = new NovelAdaptationPrompts();
        for (var phase : List.of(NovelProviderModels.Phase.PLAN, NovelProviderModels.Phase.CRITIC)) {
            AtomicReference<byte[]> bytes = new AtomicReference<>();
            var wire = phase == NovelProviderModels.Phase.PLAN ? NovelProviderQuoteProtocolTest.plan() : NovelCandidateEvidenceTest.critic();
            try (Fixture fixture = new Fixture(exchange -> {
                bytes.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "application/json", success(NovelProviderJson.encode(wire)));
            }); NovelProviderClient client = new NovelProviderClient(DEPLOYMENT, key("fixture-" + UUID.randomUUID()),
                    URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/chat/completions"),
                    Clock.systemUTC(), Duration.ofSeconds(2), true, true)) {
                String constraints = NovelAdaptationPromptsTest.constraints(context);
                var prepared = phase == NovelProviderModels.Phase.PLAN ? prompts.plan(context) : prompts.critic(context, constraints, "Ari waited.");
                var ticket = client.prepare(UUID.randomUUID(), DEPLOYMENT.scope(), prepared);
                var result = client.execute(ticket, permit(ticket, 5000), () -> true);
                assertThat(result.status()).isEqualTo("SUCCEEDED");
                assertThat(NovelProviderJson.sha(bytes.get())).isEqualTo(ticket.requestSha256());
                var request = NovelProviderJson.parse(new String(bytes.get(), StandardCharsets.UTF_8));
                assertThat(request.at("/messages/0/content").asText()).contains(phase == NovelProviderModels.Phase.PLAN ? "-quotes-v1" : "-refs-v1");
                var input = NovelProviderJson.parse(request.at("/messages/1/content").asText());
                assertThat(input.path("providerWireVersion").asText()).isEqualTo(phase == NovelProviderModels.Phase.PLAN ? "quote-v1" : "critic-refs-v1");
                if (phase == NovelProviderModels.Phase.CRITIC) assertThat(input.path("candidateEvidence"))
                        .isEqualTo(NovelCandidateEvidence.catalog("Ari waited."));
                if (phase == NovelProviderModels.Phase.CRITIC) assertThat(NovelProviderJson.parse(result.structuredJson()).path("constraintSha256"))
                        .isEqualTo(input.path("constraintSha256"));
                assertThatThrownBy(() -> client.execute(ticket, permit(ticket, 5000), () -> true)).hasMessage("READER_044");
                assertThat(fixture.calls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void sendsExactHashedRequestWithoutModelOverrideAndConsumesTicketOnce() throws Exception {
        String secret = "fixture-" + UUID.randomUUID();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> encoding = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Auth-Fixture"));
            encoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "application/json; charset=\"UTF-8\"", success("Ari closed the door."));
        }); NovelProviderClient client = fixture.client(key(secret), Duration.ofSeconds(2))) {
            var ticket = ticket(client);
            var result = client.send(ticket, permit(ticket, 5000), () -> true);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(header.get()).isEqualTo(secret);
            assertThat(encoding.get()).isEqualTo("identity");
            assertThat(NovelProviderJson.sha(body.get())).isEqualTo(ticket.requestSha256());
            var request = NovelProviderJson.parse(new String(body.get(), StandardCharsets.UTF_8));
            assertThat(request.path("messages")).hasSize(2);
            assertThat(request.path("model").asText()).isEqualTo("fixture-model");
            assertThat(request.path("stream").booleanValue()).isFalse();
            assertThat(request.path("max_tokens").intValue()).isEqualTo(1000);
            assertThat(request.has("tools")).isFalse();
            assertThat(request.has("response_format")).isFalse();
            assertThat(request.toString()).doesNotContain(secret);
            assertThat(result.toString()).doesNotContain(secret);
            assertThat(ticket.toString()).doesNotContain(secret, "Ari");
            assertThatThrownBy(() -> client.send(ticket, permit(ticket, 5000), () -> true)).hasMessage("READER_044");
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void refusesWrongScopeHashDeadlineAndAuthorizationWithoutContactingServer() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, "application/json", success("Ari closed the door.")));
             NovelProviderClient client = fixture.client(key("fixture-" + UUID.randomUUID()), Duration.ofSeconds(2))) {
            var ticket = ticket(client);
            Instant now = Instant.now();
            for (Permit permit : List.of(
                    new Permit(UUID.randomUUID(), ticket.requestSha256(), true, now.plusSeconds(1), now.plusSeconds(10)),
                    new Permit(ticket.providerAttemptId(), "0".repeat(64), true, now.plusSeconds(1), now.plusSeconds(10)),
                    new Permit(ticket.providerAttemptId(), ticket.requestSha256(), false, now.plusSeconds(1), now.plusSeconds(10)),
                    new Permit(ticket.providerAttemptId(), ticket.requestSha256(), true, now.minusMillis(1), now.plusSeconds(10)),
                    new Permit(ticket.providerAttemptId(), ticket.requestSha256(), true, now.plusSeconds(10), now.plusSeconds(30)),
                    new Permit(ticket.providerAttemptId(), ticket.requestSha256(), true, now.plusSeconds(1), now.plusSeconds(100)))) {
                assertThatThrownBy(() -> client.send(ticket, permit, () -> true)).hasMessage("READER_044");
            }
            assertThatThrownBy(() -> client.send(ticket, permit(ticket, 5000), () -> false)).hasMessage("READER_044");
            assertThatThrownBy(() -> client.send(ticket, permit(ticket, 5000), () -> { throw new IllegalStateException("secret-echo"); }))
                    .hasMessage("READER_044").hasNoCause();
            var prompt = new NovelAdaptationPrompts().plan(NovelAdaptationPromptsTest.context("INITIAL", "Ari closed the door.", null));
            assertThatThrownBy(() -> client.prepare(UUID.randomUUID(), new Deployment("other", "fixture-model", 1, 1048576, 1000, false).scope(), prompt))
                    .hasMessage("READER_049");
            assertThat(fixture.calls.get()).isZero();
        }
    }

    @Test
    void publicExecutionAlwaysNormalizesPhaseBeforeReturningSettlementPayload() throws Exception {
        for (boolean valid : List.of(true, false)) {
            var payload = NovelStageOutputTest.plan();
            if (!valid) payload.put("reasoning_content", "hidden-fixture-reasoning");
            try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, "application/json", success(NovelProviderJson.encode(payload))));
                 NovelProviderClient client = fixture.client(key("fixture-" + UUID.randomUUID()), Duration.ofSeconds(2))) {
                var ticket = ticket(client);
                var result = client.execute(ticket, permit(ticket, 5000), () -> true);
                assertThat(result.status()).isEqualTo(valid ? "SUCCEEDED" : "FAILED");
                assertThat(result.outputText()).isNull();
                if (valid) assertThat(result.structuredJson()).isEqualTo(NovelProviderJson.encode(payload));
                else { assertThat(result.structuredJson()).isNull(); assertThat(result.errorCode()).isEqualTo("READER_041"); }
                assertThat(result.toString()).doesNotContain("hidden-fixture-reasoning");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"401,READER_040", "403,READER_055", "429,READER_039", "500,READER_054", "408,READER_054", "307,READER_041"})
    void neverRetriesOrFollowsRedirectsAndDoesNotExposeErrorEcho(int status, String error) throws Exception {
        String secret = "fixture-" + UUID.randomUUID();
        String response = "<html>" + secret + " original-fixture-text</html>";
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            respond(exchange, status, "text/html", response.getBytes(StandardCharsets.UTF_8));
        }); NovelProviderClient client = fixture.client(key(secret), Duration.ofSeconds(2))) {
            var ticket = ticket(client);
            var result = client.send(ticket, permit(ticket, 5000), () -> true);
            assertThat(result.errorCode()).isEqualTo(error);
            assertThat(result.content()).isNull();
            assertThat(result.toString()).doesNotContain(secret, response);
            assertThat(result.diagnosticSha256()).isEqualTo(NovelProviderJson.sha(response.getBytes(StandardCharsets.UTF_8)));
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void blocksCredentialReflectedInSuccessfulBodyOrProviderId() throws Exception {
        String secret = "fixture-" + UUID.randomUUID();
        for (boolean reflectInId : List.of(true, false)) {
            var body = NovelProviderResponseParserTest.response(reflectInId ? "Ordinary fixture." : secret, "stop");
            if (reflectInId) body.put("id", secret);
            try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, "application/json", NovelProviderJson.encode(body).getBytes(StandardCharsets.UTF_8)));
                 NovelProviderClient client = fixture.client(key(secret), Duration.ofSeconds(2))) {
                var ticket = ticket(client);
                var result = client.send(ticket, permit(ticket, 5000), () -> true);
                assertThat(result.errorCode()).isEqualTo("READER_041");
                assertThat(result.content()).isNull();
                assertThat(result.providerRequestId()).isNull();
            }
        }
    }

    @Test
    void firstByteAndAbsoluteBodyDeadlinesAbortWithoutResending() throws Exception {
        for (boolean sendFirstByte : List.of(false, true)) {
            CountDownLatch release = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                if (sendFirstByte) exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                exchange.close();
            }); NovelProviderClient client = fixture.client(key("fixture-" + UUID.randomUUID()), Duration.ofMillis(250))) {
                long started = System.nanoTime();
                var ticket = ticket(client);
                var result = client.send(ticket, permit(ticket, sendFirstByte ? 700 : 5000), () -> true);
                release.countDown();
                assertThat(result.status()).isEqualTo("CALL_OUTCOME_UNKNOWN");
                assertThat(result.content()).isNull();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(fixture.calls.get()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void revocationAfterSendStopsReadAndKeepsOutcomeUnknown() throws Exception {
        AtomicBoolean authorized = new AtomicBoolean(true);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getRequestBody().readAllBytes();
            authorized.set(false);
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); NovelProviderClient client = fixture.client(key("fixture-" + UUID.randomUUID()), Duration.ofSeconds(2))) {
            var ticket = ticket(client);
            assertThat(client.send(ticket, permit(ticket, 5000), authorized::get).errorCode()).isEqualTo("READER_054");
            assertThat(fixture.calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void rejectsCompressedOversizedAndMalformedMediaResponses() throws Exception {
        for (String mode : List.of("compressed", "oversized", "charset", "html")) {
            try (Fixture fixture = new Fixture(exchange -> {
                if (mode.equals("compressed")) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                if (mode.equals("oversized")) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 1_048_577);
                    exchange.close();
                } else {
                    String type = mode.equals("charset") ? "application/json; charset=\"utf-8" : mode.equals("html") ? "text/html" : "application/json";
                    respond(exchange, 200, type, success("Ari closed the door."));
                }
            }); NovelProviderClient client = fixture.client(key("fixture-" + UUID.randomUUID()), Duration.ofSeconds(2))) {
                var ticket = ticket(client);
                var result = client.send(ticket, permit(ticket, 5000), () -> true);
                assertThat(result.errorCode()).as(mode).isEqualTo("READER_041");
                assertThat(result.content()).isNull();
                assertThat(fixture.calls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void byteSubscriberCancelsBeforeCopyingAndBodyPublisherRefusesSecondSubscription() throws Exception {
        var response = new NovelProviderClient.LimitedBody(HttpHeaders.of(Map.of(), (key, value) -> true), new AtomicBoolean());
        AtomicBoolean cancelled = new AtomicBoolean();
        response.onSubscribe(new Flow.Subscription() {
            /** 测试直接推动有界订阅。 */
            @Override public void request(long amount) { }
            /** 记录超限取消。 */
            @Override public void cancel() { cancelled.set(true); }
        });
        ByteBuffer oversized = ByteBuffer.allocate(1_048_577);
        response.onNext(List.of(oversized));
        assertThat(cancelled.get()).isTrue();
        assertThat(oversized.position()).isZero();
        assertThat(response.getBody().toCompletableFuture()).isCompletedExceptionally();
        var publisher = new NovelProviderClient.OncePublisher("fixture-body".getBytes(StandardCharsets.UTF_8));
        CountingSubscriber first = new CountingSubscriber();
        CountingSubscriber second = new CountingSubscriber();
        publisher.subscribe(first);
        publisher.subscribe(second);
        assertThat(first.bytes.get()).isEqualTo(12);
        assertThat(first.error.get()).isNull();
        assertThat(second.bytes.get()).isZero();
        assertThat(second.error.get()).hasMessage("READER_054");
    }

    @Test
    void credentialMountValidationRejectsPermissionsSymlinksHeaderInjectionAndInvalidBytes() throws Exception {
        Path valid = key("fixture-" + UUID.randomUUID());
        try (var credential = new NovelProviderCredential(valid, "Authorization", "Bearer")) {
            assertThat(credential.toString()).isEqualTo("NovelProviderCredential[REDACTED]");
        }
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new NovelProviderCredential(valid, "Authorization", "Bearer")).hasMessage("READER_046").hasNoCause();
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rw-------"));
        Path link = valid.resolveSibling("credential-link"); Files.createSymbolicLink(link, valid);
        assertThatThrownBy(() -> new NovelProviderCredential(link, "Authorization", "Bearer")).hasMessage("READER_046");
        assertThatThrownBy(() -> new NovelProviderCredential(valid, "Host", "")).hasMessage("READER_046");
        assertThatThrownBy(() -> new NovelProviderCredential(valid, "X-Auth-Test", "Bearer\r\n")).hasMessage("READER_046");
        Path invalid = key("fixture-value\r\ninvalid-value");
        assertThatThrownBy(() -> new NovelProviderCredential(invalid, "Authorization", "Bearer")).hasMessage("READER_046");
    }

    private Path key(String secret) throws IOException {
        Path path = temporary.toRealPath().resolve("credential-" + UUID.randomUUID());
        Files.writeString(path, secret + "\n"); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }
    private static NovelProviderClient.Ticket ticket(NovelProviderClient client) {
        var context = NovelAdaptationPromptsTest.context("INITIAL", "Ari closed the door.", null);
        return client.prepare(UUID.randomUUID(), DEPLOYMENT.scope(), new NovelAdaptationPrompts().plan(context));
    }
    private static Permit permit(NovelProviderClient.Ticket ticket, long deadlineMillis) {
        Instant now = Instant.now();
        return new Permit(ticket.providerAttemptId(), ticket.requestSha256(), true, now.plusSeconds(1), now.plusMillis(deadlineMillis));
    }
    private static byte[] success(String content) { return NovelProviderJson.encode(NovelProviderResponseParserTest.response(content, "stop")).getBytes(StandardCharsets.UTF_8); }
    private static void respond(HttpExchange exchange, int status, String media, byte[] content) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", media);
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content); exchange.close();
    }
    private static final class CountingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final AtomicInteger bytes = new AtomicInteger();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        /** 请求第一次正文。 */
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        /** 只计数，不保存正文。 */
        @Override public void onNext(ByteBuffer item) { bytes.addAndGet(item.remaining()); }
        /** 保存固定异常用于断言。 */
        @Override public void onError(Throwable throwable) { error.set(throwable); }
        /** 无需保存完成事件。 */
        @Override public void onComplete() { }
    }
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger calls = new AtomicInteger();
        private Fixture(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> { calls.incrementAndGet(); handler.handle(exchange); });
            server.setExecutor(workers); server.start();
        }
        private NovelProviderClient client(Path credential, Duration firstByte) {
            return new NovelProviderClient(DEPLOYMENT, credential, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"),
                    Clock.systemUTC(), firstByte);
        }
        /** 停止本机协议夹具，不接触真实 Provider。 */
        @Override public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
