package com.yuyutian.mytools.wechat.moments.controller;

import com.yuyutian.mytools.common.MediaNotFoundException;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.wechat.moments.model.MomentsMedia;
import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import com.yuyutian.mytools.wechat.moments.model.dto.BatchCreateTaskRequest;
import com.yuyutian.mytools.wechat.moments.service.MomentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 朋友圈公开接口（无需鉴权）
 */
@RestController
@RequestMapping("/api/public/wechat/moments")
@RequiredArgsConstructor
public class PublicMomentsController {

    private final MomentsService momentsService;
    private static final String UPLOAD_DIR = "uploads/moments/";

    /**
     * 公开批量创建任务
     */
    @PostMapping("/batch")
    public ResponseEntity<Result<List<MomentsTask>>> batchCreateTask(@RequestBody BatchCreateTaskRequest request) {
        // 使用默认创建者ID 1（公开接口无用户上下文）
        List<MomentsTask> tasks = momentsService.batchCreateTask(request, 1L);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("批量创建成功", tasks));
    }

    /**
     * 公开获取任务详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<Result<Map<String, Object>>> getTaskDetail(@PathVariable Long id) {
        MomentsTask task = momentsService.getTaskDetail(id);
        List<MomentsMedia> mediaList = momentsService.getTaskMedia(id);
        return ResponseEntity.ok(Result.success(Map.of(
                "task", task,
                "mediaList", mediaList
        )));
    }

    /**
     * 公开下载多媒体文件
     */
    @GetMapping("/media/{mediaId}")
    public ResponseEntity<Resource> downloadMedia(@PathVariable Long mediaId) {
        // 获取媒体信息
        MomentsMedia targetMedia = momentsService.getMediaById(mediaId);

        String url = targetMedia.getUrl();
        // 去掉开头的 "/"
        String filePath = url.startsWith("/") ? url.substring(1) : url;

        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new MediaNotFoundException("文件不存在: " + filePath);
            }

            Resource resource = new FileSystemResource(path);
            String filename = targetMedia.getOriginalName() != null
                    ? targetMedia.getOriginalName()
                    : path.getFileName().toString();

            String contentType = Files.probeContentType(path);
            if (contentType == null) {
                contentType = targetMedia.getType() == 2 ? "video/mp4" : "image/jpeg";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (IOException e) {
            throw new MediaNotFoundException("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 公开上传媒体文件
     */
    @PostMapping("/upload")
    public ResponseEntity<Result<Map<String, String>>> uploadMedia(@RequestParam("file") MultipartFile file) {
        try {
            String uploadPath = System.getProperty("user.dir") + "/" + UPLOAD_DIR;
            File uploadDir = new File(uploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";
            String uniqueFilename = UUID.randomUUID().toString().replace("-", "") + extension;
            File destFile = new File(uploadPath + uniqueFilename);
            file.transferTo(destFile);
            String url = "/" + UPLOAD_DIR + uniqueFilename;
            return ResponseEntity.ok(Result.success(Map.of("url", url, "filename", uniqueFilename)));
        } catch (IOException e) {
            throw new UploadFailedException("文件上传失败");
        }
    }

    public static class MediaNotFoundException extends RuntimeException {
        public MediaNotFoundException(String message) {
            super(message);
        }
    }

    public static class UploadFailedException extends RuntimeException {
        public UploadFailedException(String message) {
            super(message);
        }
    }
}
