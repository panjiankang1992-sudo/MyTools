package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.BookSourceReference;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import com.yuyutian.mytools.reader.service.EbookSourceNotFoundException;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已迁移书源内部查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/internal/v1/book-sources")
public class BookSourceLookupController {
    private final DiscoveryRepository repository;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建书源查询控制器。
     *
     * @param repository 书源仓储
     * @param authorizer 内部请求校验器
     */
    public BookSourceLookupController(DiscoveryRepository repository, InternalRequestAuthorizer authorizer) {
        this.repository = repository;
        this.authorizer = authorizer;
    }

    /**
     * 按所有者和规范化地址解析书源。
     *
     * @param authorization 授权头
     * @param ownerId 所有者标识
     * @param sourceUrl 书源地址
     * @return 书源引用
     */
    @GetMapping("/resolve")
    public BookSourceReference resolve(@RequestHeader("Authorization") String authorization,
                                       @RequestParam @Positive long ownerId,
                                       @RequestParam @NotBlank @Size(max = 4096) String sourceUrl) {
        authorizer.requireAuthorized(authorization);
        var source = repository.findExecutionSnapshot(ownerId, sourceUrl)
                .orElseThrow(() -> new EbookSourceNotFoundException(sourceUrl));
        return new BookSourceReference(source.id(), source.sourceUrl(), source.version());
    }
}
