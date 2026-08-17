package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFileControllerStreamingTest {

    @Test
    void shouldStreamRemoteFileWithoutCreatingByteArrayResource() throws Exception {
        CloudFileService service = mock(CloudFileService.class);
        byte[] content = "streamed-content".getBytes(StandardCharsets.UTF_8);
        RemoteMediaStream stream = new RemoteMediaStream(
                new ByteArrayInputStream(content),
                200,
                Optional.of("application/octet-stream"),
                Optional.of(String.valueOf(content.length)),
                Optional.empty(),
                Optional.empty(),
                Optional.of("etag-value"),
                Optional.empty());
        when(service.openDownloadStream(42L, 7L, "/folder/test.bin")).thenReturn(stream);
        CloudFileController controller = new CloudFileController(service, mock(JwtUtils.class));

        ResponseEntity<StreamingResponseBody> response = controller.downloadFileStream(
                42L, "/folder/test.bin", 7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(String.valueOf(content.length), response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertNotNull(response.getBody());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertArrayEquals(content, output.toByteArray());
        verify(service).openDownloadStream(42L, 7L, "/folder/test.bin");
    }
}
