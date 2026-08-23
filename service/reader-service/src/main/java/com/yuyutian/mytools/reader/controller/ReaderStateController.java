package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.MarkerStateRequest;
import com.yuyutian.mytools.reader.model.MarkerStateView;
import com.yuyutian.mytools.reader.model.ProgressStateRequest;
import com.yuyutian.mytools.reader.model.ProgressStateView;
import com.yuyutian.mytools.reader.model.ShelfStateRequest;
import com.yuyutian.mytools.reader.model.ShelfStateView;
import com.yuyutian.mytools.reader.service.ReaderStateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reader 书架、进度和标记同步数据接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/reader-state")
public class ReaderStateController {

    private final ReaderStateService service;

    /**
     * 创建 Reader 同步状态控制器。
     */
    public ReaderStateController(ReaderStateService service) {
        this.service = service;
    }

    /**
     * 查询书架及可选墓碑。
     */
    @GetMapping("/shelves")
    public List<ShelfStateView> shelves(@RequestParam @Positive long ownerId,
                                        @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return service.shelves(ownerId, includeDeleted);
    }

    /**
     * 创建或乐观更新书架状态。
     */
    @PostMapping("/shelves")
    public ShelfStateView saveShelf(@Valid @RequestBody ShelfStateRequest request) {
        return service.saveShelf(request);
    }

    /**
     * 查询阅读进度及可选墓碑。
     */
    @GetMapping("/progress")
    public List<ProgressStateView> progress(@RequestParam @Positive long ownerId,
                                            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return service.progress(ownerId, includeDeleted);
    }

    /**
     * 创建或乐观更新阅读进度。
     */
    @PostMapping("/progress")
    public ProgressStateView saveProgress(@Valid @RequestBody ProgressStateRequest request) {
        return service.saveProgress(request);
    }

    /**
     * 查询阅读标记及可选墓碑。
     */
    @GetMapping("/markers")
    public List<MarkerStateView> markers(@RequestParam @Positive long ownerId,
                                         @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return service.markers(ownerId, includeDeleted);
    }

    /**
     * 创建或乐观更新阅读标记。
     */
    @PostMapping("/markers")
    public MarkerStateView saveMarker(@Valid @RequestBody MarkerStateRequest request) {
        return service.saveMarker(request);
    }
}
