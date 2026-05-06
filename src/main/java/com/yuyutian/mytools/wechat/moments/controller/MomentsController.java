package com.yuyutian.mytools.wechat.moments.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.wechat.moments.model.MomentsMedia;
import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import com.yuyutian.mytools.wechat.moments.model.dto.BatchCreateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.CreateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.UpdateTaskRequest;
import com.yuyutian.mytools.wechat.moments.model.dto.TaskListResponse;
import com.yuyutian.mytools.wechat.moments.model.dto.RefreshTaskResponse;
import com.yuyutian.mytools.wechat.moments.service.MomentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 朋友圈任务 Controller
 */
@RestController
@RequestMapping("/api/wechat/moments")
@RequiredArgsConstructor
public class MomentsController {

    private final MomentsService momentsService;
    private static final String UPLOAD_DIR = "uploads/moments/";

    /**
     * 分页获取任务列表
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TaskListResponse>> getTaskPage(
            @RequestParam Long page,
            @RequestParam Long pageSize,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean includeExpired,
            @RequestParam(required = false) String keyword) {
        TaskListResponse result = momentsService.getTaskPage(page, pageSize, accountId, status, includeExpired, keyword);
        return ResponseEntity.ok(Result.success(result));
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<java.util.Map<String, Object>>> getTaskDetail(@PathVariable Long id) {
        MomentsTask task = momentsService.getTaskDetail(id);
        List<MomentsMedia> mediaList = momentsService.getTaskMedia(id);
        // 返回任务和媒体文件
        return ResponseEntity.ok(Result.success(java.util.Map.of(
                "task", task,
                "mediaList", mediaList
        )));
    }

    /**
     * 创建任务
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<MomentsTask>> createTask(@RequestBody CreateTaskRequest request) {
        Long creatorId = getCurrentUserId();
        MomentsTask task = momentsService.createTask(request, creatorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("创建成功", task));
    }

    /**
     * 批量创建任务
     */
    @PostMapping("/batch")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<MomentsTask>>> batchCreateTask(@RequestBody BatchCreateTaskRequest request) {
        Long creatorId = getCurrentUserId();
        List<MomentsTask> tasks = momentsService.batchCreateTask(request, creatorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("批量创建成功", tasks));
    }

    /**
     * 更新任务
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> updateTask(
            @PathVariable Long id,
            @RequestBody UpdateTaskRequest request) {
        momentsService.updateTask(id, request);
        return ResponseEntity.ok(Result.success("更新成功"));
    }

    /**
     * 更新任务状态
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> updateTaskStatus(
            @PathVariable Long id,
            @RequestParam Integer status) {
        momentsService.updateTaskStatus(id, status);
        return ResponseEntity.ok(Result.success("状态更新成功"));
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteTask(@PathVariable Long id) {
        momentsService.deleteTask(id);
        return ResponseEntity.ok(Result.success("删除成功"));
    }

    /**
     * 刷新单个任务状态
     */
    @PutMapping("/{id}/refresh")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<RefreshTaskResponse>> refreshTaskStatus(@PathVariable Long id) {
        RefreshTaskResponse result = momentsService.refreshTaskStatus(id);
        return ResponseEntity.ok(Result.success("刷新成功", result));
    }

    /**
     * 上传媒体文件
     */
    @PostMapping("/upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<java.util.Map<String, String>>> uploadMedia(@RequestParam("file") MultipartFile file) {
        try {
            // 创建上传目录
            String uploadPath = System.getProperty("user.dir") + "/" + UPLOAD_DIR;
            File uploadDir = new File(uploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";
            String uniqueFilename = UUID.randomUUID().toString().replace("-", "") + extension;
            // 保存文件
            File destFile = new File(uploadPath + uniqueFilename);
            file.transferTo(destFile);
            // 返回访问路径
            String url = "/" + UPLOAD_DIR + uniqueFilename;
            return ResponseEntity.ok(Result.success(java.util.Map.of("url", url, "filename", uniqueFilename)));
        } catch (IOException e) {
            throw new ServiceException(com.yuyutian.mytools.common.ErrorCode.MOMENTS_004);
        }
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            String name = auth.getName();
            if (name != null && name.matches("\\d+")) {
                return Long.parseLong(name);
            }
        }
        return 1L; // 默认返回1
    }

    public static class ServiceException extends RuntimeException {
        private final com.yuyutian.mytools.common.ErrorCode errorCode;
        public ServiceException(com.yuyutian.mytools.common.ErrorCode errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }
    }
}