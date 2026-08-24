package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSourceLookupControllerTest {

    @Test
    void shouldAuthorizeAndResolveEnabledSource() {
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        UUID sourceId = UUID.randomUUID();
        when(repository.findExecutionSnapshot(7L, "https://source.example"))
                .thenReturn(Optional.of(new DiscoveryRepository.SourceExecutionSnapshot(
                        sourceId, "https://source.example", 2, Map.of())));
        BookSourceLookupController controller = new BookSourceLookupController(repository, authorizer);

        var result = controller.resolve("Bearer token", 7L, "https://source.example");

        verify(authorizer).requireAuthorized("Bearer token");
        assertThat(result.id()).isEqualTo(sourceId);
        assertThat(result.version()).isEqualTo(2);
    }
}
