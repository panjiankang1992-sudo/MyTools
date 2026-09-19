package com.yuyutian.mytools.reader.controller.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.Catalog;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationStyleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读目录复用 Reader 私网服务鉴权，APP 没有发布入口。 */
@RestController
public class AdaptationStyleController {
    private final AdaptationStyleRepository styles;
    /** 注入模板目录。 */
    public AdaptationStyleController(AdaptationStyleRepository styles) { this.styles = styles; }
    /** 返回可选择的最新修订摘要。 */
    @GetMapping("/api/v1/reader-state/adaptation-style-templates")
    public Catalog catalog() { return styles.catalog(); }
}
