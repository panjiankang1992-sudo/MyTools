package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.EbookCover;
import com.yuyutian.mytools.reader.service.EbookCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbookCatalogControllerTest {
    /**
     * 验证封面接口返回受控图片类型、禁止嗅探头和私有缓存策略。
     */
    @Test
    void shouldReturnSafeEbookCoverResponse() {
        EbookCatalogService service = mock(EbookCatalogService.class);
        byte[] content = new byte[] {1, 2, 3};
        when(service.cover(2L, 8L, true)).thenReturn(new EbookCover(content, "image/png"));
        EbookCatalogController controller = new EbookCatalogController(service);

        var response = controller.cover(8L, 2L, true);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(response.getHeaders().getCacheControl());
        assertArrayEquals(content, response.getBody());
    }
}
