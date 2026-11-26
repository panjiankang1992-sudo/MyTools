package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.EvidenceModels.Snapshot;
import com.yuyutian.mytools.news.service.EvidenceService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 证据实时状态与日报正文分开读取，内部令牌及可信所有者仍由统一边界校验。 */
@RestController
@Validated
@RequestMapping("/internal/v1/news/editions")
public class NewsEvidenceController {
    private final EvidenceService service;
    /** 注入当前证据状态服务。 */
    public NewsEvidenceController(EvidenceService service){this.service=service;}
    /** 返回本人日报引用的本地保留状态，禁止共享缓存。 */
    @GetMapping("/{id}/evidence")
    public Snapshot inspect(@RequestParam @Min(1) long ownerId,@PathVariable UUID id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store, private");
        return service.inspect(ownerId,id);
    }
}
