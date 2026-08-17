package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.cloudfile.service.RemoteImageThumbnailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供仅限已登录用户访问的远程图片缩略图。
 */
@RestController
@RequiredArgsConstructor
public class MediaThumbnailController {

    private final RemoteImageThumbnailService thumbnailService;

    /**
     * 返回当前用户指定远程图片的有界JPEG缩略图。
     *
     * @param userId 认证过滤器注入的用户ID
     * @param accountId 远程账号ID
     * @param path 远程图片路径
     * @return 私有且不可缓存的JPEG响应
     */
    @GetMapping("/api/app/v1/media/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestAttribute("userId") Long userId,
                                            @RequestParam("accountId") Long accountId,
                                            @RequestParam("path") String path,
                                            @RequestParam(value = "edge", defaultValue = "192") int edge) {
        byte[] thumbnail = thumbnailService.create(userId, accountId, path, edge);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(thumbnail.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(thumbnail);
    }
}
