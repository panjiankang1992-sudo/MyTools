package com.yuyutian.mytools.reader.controller.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.SourceShelfChapterService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 私网 Reader 的书架章节接口，owner 由已认证 Gateway 注入，不能直接对公网暴露。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "reader.shelf-chapters", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/reader-state/shelves/{shelfBookId}")
public class ShelfChapterController {
    private final SourceShelfChapterService service;

    /** 注入书架章节服务，复用 Reader 与 Gateway 的现有私网边界。 */
    public ShelfChapterController(SourceShelfChapterService service) {
        this.service = service;
    }

    /** 查询持久准备状态，客户端重启不创建新任务。 */
    @GetMapping("/chapter-adaptation-capability")
    public ShelfChapterModels.Capability capability(@RequestParam @Positive long ownerId, @PathVariable UUID shelfBookId) {
        return service.capability(ownerId, shelfBookId);
    }

    /** 严格接收幂等键，拒绝正文、地址、额外 owner 等未定义字段。 */
    @PostMapping("/chapter-adaptation-capability/ensure")
    public ResponseEntity<ShelfChapterModels.Capability> ensure(@RequestParam @Positive long ownerId,
                                                                @PathVariable UUID shelfBookId,
                                                                @RequestBody Map<String, Object> request) {
        if (request.size() != 1 || !(request.get("idempotencyKey") instanceof String key)) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        }
        return ResponseEntity.accepted().body(service.ensure(ownerId, shelfBookId, key));
    }

    /** 返回无资源地址的权威目录和签名游标。 */
    @GetMapping("/chapters")
    public ShelfChapterModels.Catalog catalog(@RequestParam @Positive long ownerId, @PathVariable UUID shelfBookId,
                                               @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit,
                                               @RequestParam(required = false) String cursor) {
        return service.catalog(ownerId, shelfBookId, limit, cursor);
    }

    /** 仅按稳定章节身份读取原章，禁止请求正文路径或任意 URL。 */
    @GetMapping("/chapters/{chapterId}/content")
    public ShelfChapterModels.Content content(@RequestParam @Positive long ownerId, @PathVariable UUID shelfBookId,
                                               @PathVariable UUID chapterId) {
        return service.content(ownerId, shelfBookId, chapterId);
    }
}
