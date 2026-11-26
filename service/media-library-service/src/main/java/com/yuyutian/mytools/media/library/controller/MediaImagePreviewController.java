package com.yuyutian.mytools.media.library.controller;

import com.yuyutian.mytools.media.library.common.ErrorCode;
import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.InternalToken;
import com.yuyutian.mytools.media.library.service.MediaImagePreviewService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** 仅供可信网关访问的有界媒体预览接口。 */
@RestController
public class MediaImagePreviewController {
    private final MediaImagePreviewService service;
    private final InternalToken token;

    /** 创建控制器。 @param service 预览服务。 @param token 内部令牌。 */
    public MediaImagePreviewController(MediaImagePreviewService service, InternalToken token) {
        this.service = service;
        this.token = token;
    }

    /** 返回授权媒体的有限尺寸预览。 @param authorization 内部授权。 @param ownerId 所有者。 @param id 媒体标识。 @param edge 最长边。 @return JPEG。 */
    @GetMapping("/internal/v1/media/items/{id}/preview")
    public ResponseEntity<Resource> preview(@RequestHeader("Authorization") String authorization,
            @RequestParam long ownerId, @PathVariable UUID id, @RequestParam(defaultValue = "512") int edge) {
        if (token.value().isBlank() || !MessageDigest.isEqual(("Bearer " + token.value()).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) throw new SecurityException(ErrorCode.PREVIEW_UNAUTHORIZED.code());
        var preview = service.preview(ownerId, id, edge);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(preview.sizeBytes())
                .header("Cache-Control", "private, max-age=300").body(preview.resource());
    }
}
