package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.common.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteImageThumbnailServiceTest {

    @Test
    void shouldGenerateBoundedJpegThumbnail() throws Exception {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        byte[] source = createPng(800, 400);
        when(cloudFileService.openDownloadStream(3L, 7L, "/photos/demo.png"))
                .thenReturn(stream(source, "image/png", Integer.toString(source.length)));

        byte[] result = new RemoteImageThumbnailService(cloudFileService)
                .create(3L, 7L, "/photos/demo.png");

        assertTrue(result.length > 2);
        assertEquals(0xFF, Byte.toUnsignedInt(result[0]));
        assertEquals(0xD8, Byte.toUnsignedInt(result[1]));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertTrue(decoded.getWidth() <= 192);
        assertTrue(decoded.getHeight() <= 192);
    }

    @Test
    void shouldRejectNonImageResponse() {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        when(cloudFileService.openDownloadStream(3L, 7L, "/photos/demo.png"))
                .thenReturn(stream("html".getBytes(), "text/html", "4"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new RemoteImageThumbnailService(cloudFileService).create(3L, 7L, "/photos/demo.png"));

        assertEquals("90005", exception.getCode());
    }

    @Test
    void shouldRejectDeclaredOversizeBeforeReading() {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        when(cloudFileService.openDownloadStream(3L, 7L, "/photos/demo.png"))
                .thenReturn(stream(new byte[]{1}, "image/png", Integer.toString(21 * 1024 * 1024)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new RemoteImageThumbnailService(cloudFileService).create(3L, 7L, "/photos/demo.png"));

        assertEquals("90005", exception.getCode());
    }

    @Test
    void shouldRejectTraversalBeforeOpeningRemoteStream() {
        CloudFileService cloudFileService = mock(CloudFileService.class);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new RemoteImageThumbnailService(cloudFileService).create(3L, 7L, "/photos/../secret"));

        assertEquals("90005", exception.getCode());
        org.mockito.Mockito.verifyNoInteractions(cloudFileService);
    }

    @Test
    void shouldAllowBoundedSharePreviewAndRejectArbitraryEdge() throws Exception {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        byte[] source = createPng(2400, 1200);
        when(cloudFileService.openDownloadStream(3L, 7L, "/photos/demo.png"))
                .thenReturn(stream(source, "image/png", Integer.toString(source.length)));
        RemoteImageThumbnailService service = new RemoteImageThumbnailService(cloudFileService);

        BufferedImage share = ImageIO.read(new ByteArrayInputStream(
                service.create(3L, 7L, "/photos/demo.png", 2048)));
        assertEquals(2048, share.getWidth());
        assertThrows(BusinessException.class,
                () -> service.create(3L, 7L, "/photos/demo.png", 4096));
    }

    private RemoteMediaStream stream(byte[] bytes, String contentType, String contentLength) {
        return new RemoteMediaStream(new ByteArrayInputStream(bytes), 200, Optional.of(contentType),
                Optional.of(contentLength), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private byte[] createPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        image.flush();
        return output.toByteArray();
    }
}
