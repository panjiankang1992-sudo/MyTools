package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateDiscoveryRequest;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.reader.service.SourceDiscoveryService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SourceDiscoveryControllerTest {

    @Test
    void shouldAuthorizeBeforeCreatingDiscovery() {
        SourceDiscoveryService service = mock(SourceDiscoveryService.class);
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        SourceDiscoveryController controller = new SourceDiscoveryController(service, authorizer);
        var request = new CreateDiscoveryRequest(
                7L, "legacy-discovery", "https://repository.example/sources.json");

        controller.create("Bearer token", request);

        verify(authorizer).requireAuthorized("Bearer token");
        verify(service).create(request);
    }
}
