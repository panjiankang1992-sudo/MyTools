package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.service.OutboundService;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OutboundControllerTest {
    private MockMvc mvc;
    private OutboundService service;
    private final String client="c".repeat(32), worker="w".repeat(32), admin="a".repeat(32);
    private final UUID id=UUID.randomUUID();
    @BeforeEach void setup() {
        service=mock(OutboundService.class);
        var p=new OutboundProperties(); p.setClientToken(client); p.setWorkerToken(worker); p.setAdminToken(admin);
        mvc=MockMvcBuilders.standaloneSetup(new OutboundController(service,new OutboundSecurity(p)))
                .setControllerAdvice(new OutboundInputExceptionHandler(), new MessagingExceptionHandler()).build();
    }
    @Test void clientAndWorkerCannotRegisterDestinations() throws Exception {
        String body="""
                {"ownerId":1,"bindingKey":"inbox","channel":"EMAIL","label":"Inbox","address":"private@example.com","signingSecret":"","verificationReference":"verified","ownershipVerified":true}
                """;
        for (String token:new String[]{client,worker}) {
            mvc.perform(post("/internal/v2/messaging/admin/destinations").header("Authorization","Bearer "+token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("code").value("MESSAGING_001"));
        }
        verifyNoInteractions(service);
        when(service.bind(any())).thenReturn(new Destination(id,Channel.EMAIL,"Inbox",true,Instant.now()));
        mvc.perform(post("/internal/v2/messaging/admin/destinations").header("Authorization","Bearer "+admin)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("address").doesNotExist()).andExpect(jsonPath("signingSecret").doesNotExist())
                .andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void capacityRejectionIsRetryableAndContainsNoPrivateRequest() throws Exception {
        when(service.bind(any())).thenThrow(new com.yuyutian.mytools.messaging.service.OutboundException(com.yuyutian.mytools.messaging.model.ErrorCode.OUTBOUND_CAPACITY_EXCEEDED));
        mvc.perform(post("/internal/v2/messaging/admin/destinations").header("Authorization","Bearer "+admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"ownerId":1,"bindingKey":"fixture","channel":"EMAIL","label":"Inbox","address":"fixture@example.test","signingSecret":"","verificationReference":"verified","ownershipVerified":true}
                        """))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("MESSAGING_026"))
                .andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void onlyWorkerCanUseLeaseExecutionBoundary() throws Exception {
        for (String token:new String[]{client,admin}) {
            mvc.perform(post("/internal/v2/messaging/jobs/deliveries/"+id+"/execute?generation=1")
                    .header("Authorization","Bearer "+token).header("X-Task-Execution-Id",UUID.randomUUID())
                    .header("X-Task-Lease-Token",UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/internal/v2/messaging/jobs/deliveries/"+id+"/execute?generation=1")
                .header("Authorization","Bearer "+worker)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void bodylessCancellationRequiresClientAndDoesNotExposeContent() throws Exception {
        String body="{\"ownerId\":1,\"idempotencyKey\":\"key\",\"destinationId\":\""+id+"\",\"payloadHash\":\""+"a".repeat(64)+"\",\"contentIdentityHash\":\""+"b".repeat(64)+"\",\"expiresAt\":\"2026-10-18T00:00:00Z\"}";
        for(String token:new String[]{admin,worker}) {
            mvc.perform(post("/internal/v2/messaging/deliveries/cancel-by-identity").header("Authorization","Bearer "+token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
        when(service.cancelByIdentity(any())).thenReturn(new View(id,id,Channel.EMAIL,"a".repeat(64),"CANCELLED",1,null,Instant.now(),Instant.now()));
        mvc.perform(post("/internal/v2/messaging/deliveries/cancel-by-identity").header("Authorization","Bearer "+client)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("CANCELLED")).andExpect(jsonPath("content").doesNotExist())
                .andExpect(jsonPath("contentExpiresAt").doesNotExist()).andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void expiredContentRetryUsesGoneStatus() throws Exception {
        when(service.retry(eq(1L),eq(id),any())).thenThrow(new com.yuyutian.mytools.messaging.service.OutboundException(com.yuyutian.mytools.messaging.model.ErrorCode.OUTBOUND_CONTENT_EXPIRED));
        mvc.perform(post("/internal/v2/messaging/deliveries/"+id+"/retry?ownerId=1").header("Authorization","Bearer "+client)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedGeneration\":1,\"acknowledgeDuplicateRisk\":true}"))
                .andExpect(status().isGone()).andExpect(jsonPath("code").value("MESSAGING_028"));
    }
    @Test void invalidCredentialInputNeverEchoesRejectedValues() throws Exception {
        mvc.perform(post("/internal/v2/messaging/admin/destinations").header("Authorization","Bearer "+admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"secret-should-not-echo\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("MESSAGING_015"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-should-not-echo"))));
        verifyNoInteractions(service);
    }
}
