package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFileControllerStreamTest {

    @Test
    void shouldPreservePartialContentHeadersAndStreamBody() throws Exception {
        byte[] expected = "stream-body".getBytes(StandardCharsets.UTF_8);
        CloudFileService service = mock(CloudFileService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.getUserIdFromToken("access-token")).thenReturn(42L);
        when(service.openMediaStream(42L, 7L, "/movie.mp4", "bytes=0-10"))
                .thenReturn(new RemoteMediaStream(
                        new ByteArrayInputStream(expected),
                        HttpStatus.PARTIAL_CONTENT.value(),
                        Optional.of("video/mp4"),
                        Optional.of(String.valueOf(expected.length)),
                        Optional.of("bytes 0-10/100"),
                        Optional.of("bytes"),
                        Optional.of("\"etag-value\""),
                        Optional.empty()));
        CloudFileController controller = new CloudFileController(service, jwtUtils);

        ResponseEntity<StreamingResponseBody> response = controller.streamMedia(
                "Bearer access-token", "/movie.mp4", 7L, "bytes=0-10");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 0-10/100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);
        assertArrayEquals(expected, outputStream.toByteArray());
        verify(service).openMediaStream(42L, 7L, "/movie.mp4", "bytes=0-10");
    }
}
