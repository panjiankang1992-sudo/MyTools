package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.EbookImportModels;
import com.yuyutian.mytools.reader.service.EbookImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 电子书上传和后台书源下载接口。
 */
@RestController
@RequestMapping("/api/ebooks/import")
@RequiredArgsConstructor
public class EbookImportController {
    private final EbookImportService service;

    /**
     * 上传用户主动选择的本地电子书。
     *
     * @param file 文件
     * @param filename 客户端文件名
     * @param directoryId 电子书目录ID
     * @return 上传结果
     */
    @PostMapping("/upload")
    public Result<EbookImportModels.UploadResult> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) Long directoryId) {
        return Result.success(service.upload(directoryId, file, filename));
    }

    /**
     * 启动书源图书后台下载。
     *
     * @param userId 用户ID
     * @param directoryId 电子书目录ID
     * @param request 书源请求
     * @return 任务快照
     */
    @PostMapping("/source")
    public Result<EbookImportModels.Task> source(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) Long directoryId,
            @Valid @RequestBody EbookImportModels.SourceRequest request) {
        return Result.success(service.startSourceImport(userId, directoryId, request));
    }

    /**
     * 查询书源图书后台下载状态。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 任务快照
     */
    @GetMapping("/source/{taskId}")
    public Result<EbookImportModels.Task> sourceTask(@RequestAttribute("userId") Long userId,
                                                      @PathVariable String taskId) {
        return Result.success(service.find(userId, taskId));
    }
}
