package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.cloudfile.service.RemoteImageThumbnailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaThumbnailControllerTest {

    @Test
    void shouldReturnPrivateNoStoreJpeg() {
        RemoteImageThumbnailService service = mock(RemoteImageThumbnailService.class);
        byte[] bytes = new byte[]{1, 2, 3};
        when(service.create(3L, 7L, "/photos/demo.png", 192)).thenReturn(bytes);

        ResponseEntity<byte[]> response = new MediaThumbnailController(service)
                .thumbnail(3L, 7L, "/photos/demo.png", 192);

        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("3", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertArrayEquals(bytes, response.getBody());
        verify(service).create(3L, 7L, "/photos/demo.png", 192);
    }
}
