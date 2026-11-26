package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.service.OutboundException;
import com.yuyutian.mytools.messaging.service.OutboundRetentionService;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OutboundRetentionControllerTest {
    private MockMvc mvc;
    private OutboundRetentionService service;
    private final String admin="a".repeat(32),client="c".repeat(32),worker="w".repeat(32);
    @BeforeEach void setup(){
        service=mock(OutboundRetentionService.class);
        OutboundProperties properties=new OutboundProperties();properties.setAdminToken(admin);properties.setClientToken(client);properties.setWorkerToken(worker);
        mvc=MockMvcBuilders.standaloneSetup(new OutboundRetentionController(service,new OutboundSecurity(properties)))
                .setControllerAdvice(new MessagingExceptionHandler()).build();
    }
    @Test void onlyAdminCanReadOrSweepAndResponsesAreNotCached() throws Exception {
        for(String token:new String[]{client,worker,"invalid"}){
            mvc.perform(get("/internal/v2/messaging/admin/retention").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/v2/messaging/admin/retention/sweep").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/v2/messaging/admin/retention/deadlines/sweep").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
        when(service.status(any())).thenReturn(Map.of("enabled",false));
        mvc.perform(get("/internal/v2/messaging/admin/retention").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("enabled").value(false)).andExpect(header().string("Cache-Control","no-store, private"));
        when(service.sweep(any())).thenReturn(Map.of("bodies",1,"attempts",0));
        mvc.perform(post("/internal/v2/messaging/admin/retention/sweep").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("bodies").value(1)).andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void explicitDeadlineSweepRemainsAvailableWithOrdinaryRetentionDisabled() throws Exception {
        when(service.sweepDeadlines(any())).thenReturn(Map.of("deadlineBodies",1));
        mvc.perform(post("/internal/v2/messaging/admin/retention/deadlines/sweep").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("deadlineBodies").value(1)).andExpect(header().string("Cache-Control","no-store, private"));
        verify(service,never()).sweep(any());
    }
    @Test void disabledMutationReturnsStableServiceError() throws Exception {
        when(service.sweep(any())).thenThrow(new OutboundException(ErrorCode.OUTBOUND_RETENTION_DISABLED));
        mvc.perform(post("/internal/v2/messaging/admin/retention/sweep").header("Authorization","Bearer "+admin))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("MESSAGING_024"));
    }
}
