package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateEbookImportRequest;
import com.yuyutian.mytools.reader.service.EbookImportService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EbookImportControllerTest {

    @Test
    void shouldAuthorizeBeforeCreatingImport() {
        EbookImportService service = mock(EbookImportService.class);
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        EbookImportController controller = new EbookImportController(service, authorizer);
        var request = new CreateEbookImportRequest(
                7L, "legacy-import", UUID.randomUUID(), "https://source.example/book", "Book", null);

        controller.create("Bearer token", request);

        verify(authorizer).requireAuthorized("Bearer token");
        verify(service).create(request);
    }
}
