package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.ChapterAdaptationGatewayProperties;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.controller.ChapterAdaptationGatewayController;
import com.yuyutian.mytools.gateway.controller.GatewayExceptionHandler;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.ChapterAdaptationResponseFilter;
import com.yuyutian.mytools.gateway.web.GatewayEnvelopeAdvice;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ChapterAdaptationGatewayTest {
    private static final UUID SHELF = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID CHAPTER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final String ROOT = "/api/app/v1/reader";
    private static final String CREATE = ROOT + "/shelves/" + SHELF + "/chapters/" + CHAPTER + "/adaptations";
    private static final String BODY = "{\"idempotencyKey\":\"fixture-1\",\"intent\":\"More atmosphere\",\"expectedBindingRevision\":1,\"expectedCatalogRevision\":2}";
    private static final String HASH = "a".repeat(64);

    @Test
    void styleCatalogIsAuthenticatedReadOnlyAndCreatePreservesSelection() throws Exception {
        var fixture = fixture(true, true);
        fixture.server.expect(requestTo(internal("/adaptation-style-templates"))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[{\"code\":\"cinematic\",\"version\":2,\"name\":\"Cinematic\",\"description\":\"Visual style\",\"promptSha256\":\"" + HASH + "\"}]}", MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(ROOT + "/adaptation-style-templates")).andExpect(status().isUnauthorized());
        fixture.mvc.perform(get(ROOT + "/adaptation-style-templates").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"));
        fixture.mvc.perform(post(ROOT + "/adaptation-style-templates").header("Authorization", "Bearer app-fixture-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        for (String pair : List.of("\"templateCode\":\"cinematic\"", "\"templateCode\":\"cinematic\",\"templateVersion\":0",
                "\"templateCode\":\"../admin\",\"templateVersion\":1")) {
            fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("{", "{" + pair + ","))).andExpect(status().isBadRequest());
        }
        fixture.server.verify();
    }

    @Test
    void consentRoutesRequireAuthenticationAndForwardOnlyExplicitDeclarations() throws Exception {
        var fixture = fixture(true, true); String path = "/api/app/v1/features/reader-adaptation";
        String body = "{\"disclosureVersion\":\"v1\",\"disclosureSha256\":\"" + HASH + "\",\"accepted\":true,\"rightsAttested\":true,\"expectedConsentRevision\":0}";
        fixture.server.expect(requestTo(internal("/features/reader-adaptation"))).andRespond(withSuccess(features("REQUIRED", 0), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/features/reader-adaptation/consent"))).andExpect(method(HttpMethod.POST)).andExpect(content().json(body))
                .andRespond(withSuccess(features("ACCEPTED", 1), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store, private"));
        for (String invalid : List.of(body.replace("true", "false"), body.replace("true", "\"true\""), body.replace("{", "{\"ownerId\":99,"),
                body + "{}", body.replace("{", "{\"accepted\":false,"), body.replace("Revision\":0", "Revision\":1.1"))) {
            fixture.mvc.perform(post(path + "/consent").header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        fixture.mvc.perform(get(path + "?ownerId=99").header("Authorization", "Bearer app-fixture-token")).andExpect(status().isBadRequest());
        fixture.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token")).andExpect(status().isOk()).andExpect(jsonPath("$.data.consentStatus").value("REQUIRED"));
        fixture.mvc.perform(post(path + "/consent").header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.consentRevision").value(1));
        fixture.server.verify();
    }

    @Test
    void directCreationFeaturesPassThroughWithoutFabricatingConsent() throws Exception {
        var fixture = fixture(true, true);
        fixture.server.expect(requestTo(internal("/features/reader-adaptation"))).andRespond(withSuccess(
                "{\"readEnabled\":true,\"createEnabled\":true,\"consentStatus\":\"NOT_REQUIRED\",\"consentRevision\":0,"
                        + "\"hasActiveConsent\":false,\"acceptedAt\":null,\"reasonCode\":null,\"disclosure\":null}", MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get("/api/app/v1/features/reader-adaptation").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.consentStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.createEnabled").value(true));
        fixture.server.verify();
    }

    @Test
    void consentCanBeReadAndRevokedWhenFeatureSwitchesAreOff() throws Exception {
        var fixture = fixture(false, false); String path = "/api/app/v1/features/reader-adaptation";
        fixture.server.expect(requestTo(internal("/features/reader-adaptation"))).andRespond(withSuccess(features("ACCEPTED", 1), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/features/reader-adaptation/consent") + "&expectedConsentRevision=1"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(content().string(""))
                .andRespond(withSuccess(features("REQUIRED", 2), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.createEnabled").value(false)).andExpect(jsonPath("$.data.readEnabled").value(false))
                .andExpect(jsonPath("$.data.hasActiveConsent").value(true));
        fixture.mvc.perform(delete(path + "/consent?expectedConsentRevision=1").header("Authorization", "Bearer app-fixture-token").content("{}"))
                .andExpect(status().isBadRequest());
        fixture.mvc.perform(delete(path + "/consent?expectedConsentRevision=1&expectedConsentRevision=2").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isBadRequest());
        fixture.mvc.perform(delete(path + "/consent?expectedConsentRevision=1").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasActiveConsent").value(false)).andExpect(header().string("Cache-Control", "no-store, private"));
        fixture.server.verify();
    }

    @Test
    void staleConsentConflictIsFixedCodeOnlyAndDoesNotRetryMutation() throws Exception {
        var fixture = fixture(true, true);
        fixture.server.expect(requestTo(internal("/features/reader-adaptation/consent") + "&expectedConsentRevision=1"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"READER_062\",\"message\":\"private payload\"}"));
        var response = fixture.mvc.perform(delete("/api/app/v1/features/reader-adaptation/consent?expectedConsentRevision=1").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READER_062")).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("private payload")); fixture.server.verify();
    }

    private static String features(String status, int revision) {
        boolean accepted = status.equals("ACCEPTED");
        return "{\"readEnabled\":true,\"createEnabled\":true,\"consentStatus\":\"" + status + "\",\"consentRevision\":" + revision
                + ",\"hasActiveConsent\":" + accepted + ",\"acceptedAt\":" + (accepted ? "\"2026-09-10T10:00:00Z\"" : "null") + ",\"reasonCode\":null,"
                + "\"disclosure\":{\"version\":\"v1\",\"disclosureSha256\":\"" + HASH + "\",\"rightsAttestationVersion\":\"r1\",\"payload\":{"
                + "\"schemaVersion\":\"adaptation-disclosure-v1\",\"providerCode\":\"fixture\",\"contractSha256\":\"" + HASH + "\",\"providerName\":\"Fixture\","
                + "\"providerOrigin\":\"https://api.sillytraven.dev\",\"dataUseNotice\":\"Fixture use\",\"retentionNotice\":\"Fixture retention\",\"rightsNotice\":\"Fixture rights\"}}}";
    }

    @Test
    void comparisonIsAuthenticatedOwnerBoundReadAndDoesNotRequireCreation() throws Exception {
        var fixture = fixture(true, false);
        String path = ROOT + "/chapter-adaptations/" + ID + "/comparison";
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/comparison")))
                .andExpect(method(HttpMethod.GET)).andExpect(content().string(""))
                .andExpect(header("Authorization", "Bearer reader-fixture-token"))
                .andRespond(withSuccess(comparison(false), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(path)).andExpect(status().isUnauthorized());
        fixture.mvc.perform(get(path + "?ownerId=99").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isBadRequest());
        fixture.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token").header("X-Owner-Id", "99"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.data.adaptationId").value(ID.toString()))
                .andExpect(jsonPath("$.data.hunks[0].original[0]").value("A plain chapter."))
                .andExpect(jsonPath("$.data.hunks[0].adapted[0]").value("A restrained chapter."));
        fixture.server.verify();
    }

    @Test
    void comparisonReadGateAndForeignVersionNeverExposeFrozenText() throws Exception {
        String path = ROOT + "/chapter-adaptations/" + ID + "/comparison";
        var disabled = fixture(false, false);
        disabled.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GATEWAY_002"));
        disabled.server.verify();
        var denied = fixture(true, true);
        denied.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/comparison")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"READER_038\",\"message\":\"private frozen text\"}"));
        String body = denied.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("READER_038"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private frozen text")); denied.server.verify();
    }

    @Test
    void comparisonFallbackIsTypedBoundedAndTextIsRedactedFromModelLogging() throws Exception {
        var fixture = fixture(true, false);
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/comparison")))
                .andRespond(withSuccess(comparison(true), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/comparison").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("SIDE_BY_SIDE"))
                .andExpect(jsonPath("$.data.original").value("A plain chapter."))
                .andExpect(jsonPath("$.data.adapted").value("A restrained chapter."));
        var json = new ObjectMapper();
        var projected = json.readValue(comparison(true), com.yuyutian.mytools.gateway.model.ChapterAdaptationGatewayModels.Comparison.class);
        var hunk = json.readValue(comparison(false), com.yuyutian.mytools.gateway.model.ChapterAdaptationGatewayModels.Comparison.class).hunks().getFirst();
        assertFalse(projected.toString().contains("chapter")); assertFalse(hunk.toString().contains("chapter")); fixture.server.verify();
    }

    @Test
    void corruptComparisonCannotExposeUnvalidatedDownstreamBodies() throws Exception {
        String valid = comparison(false);
        for (String body : List.of(valid.replace(ID.toString(), SHELF.toString()), valid.replace("HUNKS", "HTML"),
                valid.replace(HASH, "invalid-hash"), valid.replace("REPLACE", "SCRIPT"),
                valid.replace("\"originalStart\":0", "\"originalStart\":-1"),
                valid.replace("\"originalStart\":0", "\"originalStart\":501"),
                valid.replace("[\"A plain chapter.\"]", "[null]"),
                valid.replace("\"original\":null", "\"original\":\"unexpected body\""),
                valid.replace("{", "{\"providerKey\":\"private-value\","), "x".repeat(2097153))) {
            var fixture = fixture(true, true);
            fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/comparison"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            String response = fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/comparison").header("Authorization", "Bearer app-fixture-token"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GATEWAY_004"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("private-value")); assertFalse(response.contains("plain chapter")); fixture.server.verify();
        }
    }

    private static String comparison(boolean fallback) throws Exception {
        var json = new ObjectMapper(); var row = json.createObjectNode();
        row.put("adaptationId", ID.toString()).put("attemptId", ATTEMPT.toString()).put("originalSha256", HASH).put("resultSha256", HASH)
                .put("mode", fallback ? "SIDE_BY_SIDE" : "HUNKS");
        var hunks = row.putArray("hunks");
        if (fallback) row.put("original", "A plain chapter.").put("adapted", "A restrained chapter.").put("fallbackReason", "COMPUTE_BUDGET");
        else {
            row.putNull("original").putNull("adapted").putNull("fallbackReason");
            var hunk = hunks.addObject().put("kind", "REPLACE").put("originalStart", 0).put("adaptedStart", 0);
            hunk.putArray("original").add("A plain chapter."); hunk.putArray("adapted").add("A restrained chapter.");
        }
        return json.writeValueAsString(row);
    }

    @Test
    void requestPassesThroughRealFilterControllerClientAndKeepsAcceptedSemantics() throws Exception {
        var fixture = fixture(true, true);
        fixture.server.expect(requestTo(internal("/shelves/" + SHELF + "/chapters/" + CHAPTER + "/adaptations")))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer reader-fixture-token"))
                .andExpect(content().json(BODY)).andExpect(request -> {
                    assertNull(request.getHeaders().getFirst("X-Owner-Id"));
                    assertEquals(1, request.getHeaders().get("Authorization").size());
                    assertNotNull(UUID.fromString(request.getHeaders().getFirst("X-Correlation-Id")));
                }).andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(accepted("INITIAL")));
        fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").header("X-Owner-Id", "999")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.adaptationId").value(ID.toString()));
        fixture.server.verify();
    }

    @Test
    void templateSelectionIsForwardedWithoutClientPrompt() throws Exception {
        var fixture = fixture(true, true);
        String body = BODY.replace("{", "{\"templateCode\":\"cinematic\",\"templateVersion\":2,");
        fixture.server.expect(requestTo(internal("/shelves/" + SHELF + "/chapters/" + CHAPTER + "/adaptations")))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json(body))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(accepted("INITIAL")));
        fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("{", "{\"prompt\":\"untrusted\","))).andExpect(status().isBadRequest());
        fixture.server.verify();
    }

    @Test
    void sourceCheckIsAsyncIdentityBoundBodylessAndAvailableWithCreationDisabled() throws Exception {
        var fixture = fixture(true, false);
        String path = ROOT + "/chapter-adaptations/" + ID + "/source-check";
        String response = "{\"adaptationId\":\"" + ID + "\",\"status\":\"QUEUED\",\"sourceCheckedAt\":null,\"validUntil\":null,"
                + "\"pollAfterMs\":1500,\"reasonCode\":null,\"bindingRevision\":1,\"catalogRevision\":2,\"sourceSha256\":null}";
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/source-check")))
                .andExpect(method(HttpMethod.POST)).andExpect(content().string(""))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(response));
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/source-check")))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        fixture.mvc.perform(post(path).header("Authorization", "Bearer app-fixture-token").content("{}"))
                .andExpect(status().isBadRequest());
        fixture.mvc.perform(post(path + "?ownerId=99").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isBadRequest());
        fixture.mvc.perform(post(path).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        fixture.mvc.perform(get(path).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.adaptationId").value(ID.toString()));
        fixture.server.verify();
    }

    @Test
    void sourceCheckRejectsForgedIdentityAndMissingOrUnboundedCurrentEvidence() throws Exception {
        String response = "{\"adaptationId\":\"" + ID + "\",\"status\":\"CURRENT\","
                + "\"sourceCheckedAt\":\"2026-09-10T10:00:00Z\",\"validUntil\":\"2026-09-10T10:01:00Z\","
                + "\"pollAfterMs\":0,\"reasonCode\":null,\"bindingRevision\":1,\"catalogRevision\":2,\"sourceSha256\":\"" + HASH + "\"}";
        for (String invalid : List.of(response.replace(ID.toString(), CHAPTER.toString()), response.replace("10:01:00Z", "10:01:01Z"),
                response.replace("\"" + HASH + "\"", "null"), response.replace("\"CURRENT\"", "\"TRUST_ME\""))) {
            var fixture = fixture(true, false);
            fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/source-check")))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
            fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/source-check").header("Authorization", "Bearer app-fixture-token"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GATEWAY_004"));
            fixture.server.verify();
        }
    }

    @Test
    void invalidExtraDuplicateOrUnboundedInputsNeverReachReader() throws Exception {
        var fixture = fixture(true, true);
        for (String invalid : List.of(BODY.replace("More atmosphere", "four"), BODY.replace("More atmosphere", "     "),
                BODY.replace("\"expectedCatalogRevision\":2", "\"expectedCatalogRevision\":2.5"),
                BODY.replace("\"expectedCatalogRevision\":2", "\"expectedCatalogRevision\":9007199254740992"),
                BODY.replace("{", "{\"ownerId\":999,"), BODY.replace("{", "{\"body\":\"private-text\","),
                BODY.replace("{", "{\"intent\":\"duplicate\","), BODY + "{}", BODY.replace("fixture-1", "bad key"),
                BODY.replace("More atmosphere", "x".repeat(2001)), BODY.replace("More atmosphere", "xxxx\\ud800"))) {
            fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(jsonPath("$.code").value("GATEWAY_003"));
        }
        fixture.mvc.perform(post(CREATE + "?ownerId=999").header("Authorization", "Bearer app-fixture-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        fixture.mvc.perform(get(ROOT + "/shelves/" + SHELF + "/chapters?limit=1&limit=2")
                        .header("Authorization", "Bearer app-fixture-token")).andExpect(status().isBadRequest());
        fixture.server.verify();
    }

    @Test
    void authenticationAndCreateSwitchFailuresRemainPrivateAndDoNotContactReader() throws Exception {
        var fixture = fixture(true, true);
        fixture.mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store, private"));
        when(fixture.validator.validate("other-user")).thenReturn(new GatewayPrincipal(56, "other", List.of(), UUID.randomUUID()));
        fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer other-user").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
        fixture.server.verify();
        var disabled = fixture(false, false);
        disabled.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isServiceUnavailable()); disabled.server.verify();
        var readOnly = fixture(true, false);
        readOnly.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()); readOnly.server.verify();
    }

    @Test
    void readOnlyModeStillProvidesHistoryProgressAndExplicitCancellation() throws Exception {
        var fixture = fixture(true, false);
        fixture.server.expect(requestTo(internal("/shelves/" + SHELF + "/chapters/" + CHAPTER + "/adaptations") + "&limit=20"))
                .andRespond(withSuccess("{\"items\":[],\"nextCursor\":null}", MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/status")))
                .andRespond(withSuccess(progress(), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/cancel")))
                .andExpect(method(HttpMethod.POST)).andExpect(content().string(""))
                        .andRespond(withSuccess(progress().replace("GENERATING", "CANCEL_REQUESTED"), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(CREATE).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/status").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("GENERATING"));
        fixture.mvc.perform(post(ROOT + "/chapter-adaptations/" + ID + "/cancel").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"));
        fixture.server.verify();
    }

    @Test
    void fixedOptimizeAndRegenerateRoutesNeverAcceptAnotherBookOrRawContent() throws Exception {
        for (String operation : List.of("optimize", "regenerate")) {
            var fixture = fixture(true, true);
            UUID trigger = UUID.randomUUID();
            fixture.server.expect(requestTo(internal("/chapter-adaptations/" + trigger + "/" + operation)))
                    .andExpect(method(HttpMethod.POST)).andExpect(content().json(BODY))
                    .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                            .body(accepted(operation.toUpperCase(java.util.Locale.ROOT))));
            fixture.mvc.perform(post(ROOT + "/chapter-adaptations/" + trigger + "/" + operation)
                            .header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.requestKind").value(operation.toUpperCase(java.util.Locale.ROOT)));
            fixture.server.verify();
        }
    }

    @Test
    void capabilityCatalogAndSelectedDetailUseExactReaderPublicProjections() throws Exception {
        var fixture = fixture(true, true);
        String capability = "{\"shelfBookId\":\"" + SHELF + "\",\"status\":\"READY\",\"bindingRevision\":1,\"catalogRevision\":2,\"catalogSha256\":\"" + HASH + "\",\"reasonCode\":null,\"pollAfterMs\":null}";
        fixture.server.expect(requestTo(internal("/shelves/" + SHELF + "/chapter-adaptation-capability"))).andRespond(withSuccess(capability, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/shelves/" + SHELF + "/chapters") + "&limit=200"))
                .andRespond(withSuccess("{\"shelfBookId\":\"" + SHELF + "\",\"bindingRevision\":1,\"catalogRevision\":2,\"catalogSha256\":\"" + HASH + "\",\"items\":[],\"nextCursor\":null}", MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID))).andRespond(withSuccess(detail(), MediaType.APPLICATION_JSON));
        fixture.mvc.perform(get(ROOT + "/shelves/" + SHELF + "/chapter-adaptation-capability").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shelfBookId").value(SHELF.toString()));
        fixture.mvc.perform(get(ROOT + "/shelves/" + SHELF + "/chapters").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk());
        fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID).header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.content").value("A restrained chapter."))
                .andExpect(jsonPath("$.data.version.lineage.childAdaptationId").value(ID.toString()));
        fixture.server.verify();
    }

    @Test
    void downstreamUnknownFieldsDuplicateKeysResourceMismatchOrWrongStatusFailClosed() throws Exception {
        for (String body : List.of(progress().replace(ID.toString(), SHELF.toString()),
                progress().replace("{", "{\"providerKey\":\"private-value\","),
                progress().replace("{", "{\"status\":\"COMPLETED\","), progress() + "{}", "null", "x".repeat(2097153))) {
            var fixture = fixture(true, true);
            fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/status"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            String response = fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/status").header("Authorization", "Bearer app-fixture-token"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GATEWAY_004"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("private-value")); fixture.server.verify();
        }
    }

    @Test
    void downstreamErrorsRetainOnlyFixedCodeIncludingTemporaryUnavailable() throws Exception {
        var fixture = fixture(true, true);
        fixture.server.expect(requestTo(internal("/chapter-adaptations/" + ID + "/status")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"READER_059\",\"message\":\"private fixture data\"}"));
        String response = fixture.mvc.perform(get(ROOT + "/chapter-adaptations/" + ID + "/status").header("Authorization", "Bearer app-fixture-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("READER_059"))
                .andExpect(header().string("Cache-Control", "no-store, private")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("private fixture data")); fixture.server.verify();
    }

    @Test
    void rawRequestLimitAndUtf8AreEnforcedBeforeMvcStringBinding() throws Exception {
        var fixture = fixture(true, true);
        for (byte[] content : List.of(new byte[32769], new byte[]{(byte) 0xc3, 0x28})) {
            fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        fixture.mvc.perform(post(CREATE).header("Authorization", "Bearer app-fixture-token").contentType("application/json;charset=ISO-8859-1").content(BODY))
                .andExpect(status().isBadRequest()); fixture.server.verify();
    }

    private static Fixture fixture(boolean read, boolean create) {
        var properties = properties(); var mapper = new ObjectMapper().findAndRegisterModules();
        RestTemplate transport = new RestTemplate(); var server = MockRestServiceServer.bindTo(transport).build();
        var client = new ChapterAdaptationGatewayClient(properties, mapper, transport.getRequestFactory());
        var validator = mock(PrincipalValidator.class);
        when(validator.validate("app-fixture-token")).thenReturn(new GatewayPrincipal(55, "fixture", List.of(), UUID.randomUUID()));
        var controller = new ChapterAdaptationGatewayController(properties, new ChapterAdaptationGatewayProperties(read, create), client, mapper);
        var consent = new com.yuyutian.mytools.gateway.controller.AdaptationConsentGatewayController(properties,
                new ChapterAdaptationGatewayProperties(read, create), client, mapper);
        var mvc = MockMvcBuilders.standaloneSetup(controller, consent).setControllerAdvice(new GatewayExceptionHandler(), new GatewayEnvelopeAdvice())
                .addFilters(new ChapterAdaptationResponseFilter(), new GatewayRequestFilter(validator, properties)).build();
        return new Fixture(mvc, server, validator);
    }
    private static String internal(String path) { return "http://reader/api/v1/reader-state" + path + "?ownerId=55"; }
    private static String accepted(String kind) { return "{\"adaptationId\":\"" + ID + "\",\"chapterId\":\"" + CHAPTER + "\",\"revisionNumber\":1,\"requestKind\":\"" + kind + "\",\"status\":\"PENDING_DISPATCH\",\"currentStage\":\"CONTEXT_PENDING\",\"pollAfterMs\":1500,\"createdAt\":\"2026-09-10T10:00:00Z\"}"; }
    private static String progress() { return "{\"adaptationId\":\"" + ID + "\",\"status\":\"GENERATING\",\"currentStage\":\"GENERATE\",\"version\":3,\"candidateCount\":0,\"lastErrorCode\":null,\"pollAfterMs\":1500,\"createdAt\":\"2026-09-10T10:00:00Z\",\"startedAt\":null,\"finishedAt\":null}"; }
    private static String detail() throws Exception {
        var json = new ObjectMapper(); var row = json.createObjectNode(); var version = row.putObject("version");
        version.put("adaptationId", ID.toString()).put("shelfBookId", SHELF.toString()).put("chapterId", CHAPTER.toString()).put("chapterTitle", "Chapter")
                .put("revisionNumber", 1).put("requestKind", "INITIAL").put("intent", "More atmosphere").put("status", "COMPLETED").put("currentStage", "COMPLETED")
                .put("selectedAttemptId", ATTEMPT.toString()).put("attemptCount", 3).put("modelId", "fixture").putNull("lastErrorCode")
                .put("createdAt", "2026-09-10T10:00:00Z").put("finishedAt", "2026-09-10T10:00:01Z");
        version.putObject("lineage").put("childAdaptationId", ID.toString()).put("rootAdaptationId", ID.toString()).putNull("parentAdaptationId").putNull("triggerAdaptationId");
        row.put("sourceRelation", "UNKNOWN").putNull("sourceCheckedAt");
        row.putObject("result").put("attemptId", ATTEMPT.toString()).put("content", "A restrained chapter.").put("contentSha256", HASH).put("viewStatus", "SELECTED");
        row.putArray("attempts"); return json.writeValueAsString(row);
    }
    private static GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true, Set.of(55L), false, Set.of(), false, Set.of(),
                "http://mytools", "http://identity", "http://reader", "http://drive", "http://download", "gateway-fixture-token", "identity-fixture-token",
                "reader-fixture-token", "drive-fixture-token", "download-fixture-token", 1000, 3000, false, "", "", false, "", "");
    }
    private record Fixture(MockMvc mvc, MockRestServiceServer server, PrincipalValidator validator) { }
}
