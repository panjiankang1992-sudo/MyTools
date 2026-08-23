package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateProviderRequest;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageProviderServiceTest {

    @Test
    void shouldRegisterWithoutExposingProviderSecretsAndListByServerRemote() {
        StorageRepository repository = mock(StorageRepository.class);
        ProviderObjectConnectorRegistry connector = mock(ProviderObjectConnectorRegistry.class);
        StorageProviderService service = new StorageProviderService(repository, connector);
        CreateProviderRequest request = new CreateProviderRequest(
                "reader_provider", "RCLONE", "internal_remote", "secret://storage/reader", true);

        var view = service.create(request);
        var captured = org.mockito.ArgumentCaptor.forClass(com.yuyutian.mytools.storage.model.StorageProvider.class);
        verify(repository).insertProvider(captured.capture());
        when(repository.findProviderById(view.id())).thenReturn(java.util.Optional.of(captured.getValue()));
        when(connector.list(captured.getValue(), "books")).thenReturn(List.of(
                new RemoteObjectView("books/a.txt", "a.txt", false, 3, null, "a".repeat(64))));

        assertThat(service.list(view.id(), "books")).hasSize(1);
        assertThat(view.toString()).doesNotContain("internal_remote", "secret://");
        verify(connector).list(captured.getValue(), "books");
    }

    @Test
    void shouldRejectIdempotencyReplayWithChangedSecretReference() {
        StorageRepository repository = mock(StorageRepository.class);
        ProviderObjectConnectorRegistry connector = mock(ProviderObjectConnectorRegistry.class);
        StorageProviderService service = new StorageProviderService(repository, connector);
        var existing = new com.yuyutian.mytools.storage.model.StorageProvider(java.util.UUID.randomUUID(),
                "remote", "RCLONE", "remote", "secret://old", true,
                java.time.Instant.now(), java.time.Instant.now());
        when(repository.findProviderByName("remote")).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateProviderRequest(
                "remote", "RCLONE", "remote", "secret://new", true)))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_012");
    }

    @Test
    void shouldRegisterNativeWebDavEndpointWithoutReturningIt() {
        StorageRepository repository = mock(StorageRepository.class);
        ProviderObjectConnectorRegistry connector = mock(ProviderObjectConnectorRegistry.class);
        StorageProviderService service = new StorageProviderService(repository, connector);
        CreateProviderRequest request = new CreateProviderRequest(
                "native_webdav", "WEBDAV", "webdav_alias", "https://dav.example.com/root",
                "env://WEBDAV_SECRET", true);

        var view = service.create(request);
        var captured = org.mockito.ArgumentCaptor.forClass(com.yuyutian.mytools.storage.model.StorageProvider.class);
        verify(repository).insertProvider(captured.capture());

        assertThat(captured.getValue().endpointUri()).isEqualTo("https://dav.example.com/root");
        assertThat(view.toString()).doesNotContain("dav.example.com", "WEBDAV_SECRET", "webdav_alias");
    }

    @Test
    void shouldRejectUnsafeNativeEndpointBeforePersistence() {
        StorageRepository repository = mock(StorageRepository.class);
        ProviderObjectConnectorRegistry connector = mock(ProviderObjectConnectorRegistry.class);
        StorageProviderService service = new StorageProviderService(repository, connector);

        assertThatThrownBy(() -> service.create(new CreateProviderRequest(
                "native_webdav", "WEBDAV", "webdav_alias", "http://example.com/root",
                "env://WEBDAV_SECRET", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_013");
    }
}
