package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateSearchRequest;
import com.yuyutian.mytools.reader.model.SearchMode;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.reader.service.ReaderSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReaderSearchControllerTest {
    @Test
    void shouldAuthorizeBeforeCreatingSearch() {
        ReaderSearchService service = mock(ReaderSearchService.class);
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        ReaderSearchController controller = new ReaderSearchController(service, authorizer);
        CreateSearchRequest request = new CreateSearchRequest(7L, "key", "Book", SearchMode.FUZZY, 1, List.of(),
                List.of(new CreateSearchRequest.SourceSnapshot(
                        "source", "Source", "https://source.example", 1, Map.of())));

        controller.create("Bearer token", request);

        verify(authorizer).requireAuthorized("Bearer token");
        verify(service).create(request);
    }
}
