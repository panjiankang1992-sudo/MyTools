package com.yuyutian.mytools.media.library.controller;

import com.yuyutian.mytools.media.library.common.ErrorCode;
import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.InternalToken;
import com.yuyutian.mytools.media.library.service.MediaVideoPackages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 在内部鉴权边界内提供媒体播放副本。 */
@RestController
@RequestMapping("/internal/v1/media/items/{id}/adaptive")
public class MediaVideoPackageController {
    private final MediaVideoPackages packages;
    private final InternalToken token;

    /** 创建播放副本控制器。 */
    public MediaVideoPackageController(MediaVideoPackages packages, InternalToken token) {
        this.packages = packages;
        this.token = token;
    }

    /** 返回已完整生成的播放入口，并为符合条件的原片安排准备工作。 */
    @PostMapping
    public Map<String, String> prepare(@PathVariable UUID id, @RequestParam long ownerId,
                                       @RequestHeader("Authorization") String authorization) {
        authenticate(authorization);
        return Map.of("entry", packages.prepare(ownerId, id));
    }

    /** 读取指定副本文件，保留 Spring 对资源分段读取的支持。 */
    @GetMapping("/{name}")
    public ResponseEntity<org.springframework.core.io.Resource> part(@PathVariable UUID id, @PathVariable String name,
            @RequestParam long ownerId, @RequestHeader("Authorization") String authorization) throws java.io.IOException {
        authenticate(authorization);
        var file = packages.part(ownerId, id, name);
        String type = name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" :
                name.endsWith(".mp4") ? "video/mp4" : "video/mp2t";
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("Content-Type", type)
                .contentLength(Files.size(file)).body(new FileSystemResource(file));
    }

    private void authenticate(String value) {
        if (token.value().isBlank() || !MessageDigest.isEqual(("Bearer " + token.value()).getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8))) throw new SecurityException(ErrorCode.PREVIEW_UNAUTHORIZED.code());
    }
}
