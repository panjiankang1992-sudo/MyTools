package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.StorageObject;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageObjectService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageObjectControllerTest {

    @Test
    void shouldReturnRequestedSingleByteRangeWithoutReadingRemainingObject() throws Exception {
        Path file = Files.createTempFile("mytools-storage-range", ".bin");
        Files.writeString(file, "0123456789");
        try {
            StorageObjectService objects = mock(StorageObjectService.class);
            InternalAuthorizer authorizer = mock(InternalAuthorizer.class);
            when(objects.requireReadable("managed", "audiobooks/chapter.mp3"))
                    .thenReturn(new StorageObject(file, 10L));
            StorageObjectController controller = new StorageObjectController(objects, authorizer);

            var response = controller.content("Bearer internal", "managed", "audiobooks/chapter.mp3", "bytes=2-5");

            assertThat(response.getStatusCode().value()).isEqualTo(206);
            assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 2-5/10");
            assertThat(response.getHeaders().getContentLength()).isEqualTo(4L);
            assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo("2345".getBytes());
            verify(authorizer).require("Bearer internal");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
