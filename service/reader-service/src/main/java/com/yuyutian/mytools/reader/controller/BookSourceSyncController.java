package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Reader 书源快照同步接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/reader-state/sources")
public class BookSourceSyncController {
    private final DiscoveryRepository repository;

    /**
     * 创建书源同步控制器。
     *
     * @param repository 书源仓储
     */
    public BookSourceSyncController(DiscoveryRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询当前所有者的书源快照。
     *
     * @param ownerId 所有者标识
     * @return 书源快照
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam @Positive long ownerId) {
        return repository.listSyncSnapshots(ownerId);
    }

    /**
     * 保存当前所有者的书源快照。
     *
     * @param request 保存请求
     * @return 同步回执
     */
    @PutMapping
    public Map<String, Object> save(@Valid @RequestBody SaveRequest request) {
        Map<String, Object> source = repository.saveSyncSnapshot(request.ownerId(), request.syncKey(),
                request.sourceUrl(), request.snapshotJson(), request.deleted());
        return Map.of("accepted", true, "source", source);
    }

    /**
     * 批量保存当前所有者的书源快照。
     *
     * @param request 批量保存请求
     * @return 批量同步回执
     */
    @PutMapping("/batch")
    public Map<String, Object> saveBatch(@Valid @RequestBody BatchSaveRequest request) {
        request.sources().forEach(source -> repository.saveSyncSnapshot(source.ownerId(), source.syncKey(),
                source.sourceUrl(), source.snapshotJson(), source.deleted()));
        return Map.of("accepted", request.sources().size());
    }

    /** 书源保存请求。 */
    public record SaveRequest(@Positive long ownerId,
                              @NotBlank @Size(max = 255) String syncKey,
                              @NotBlank @Size(max = 2000) String sourceUrl,
                              @NotBlank @Size(max = 524288) String snapshotJson,
                              boolean deleted) {
    }

    /** 书源批量保存请求。 */
    public record BatchSaveRequest(@NotEmpty @Size(max = 200) List<@Valid SaveRequest> sources) {
    }
}
