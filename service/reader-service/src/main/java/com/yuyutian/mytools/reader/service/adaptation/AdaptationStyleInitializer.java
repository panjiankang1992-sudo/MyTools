package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.Publish;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationStyleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 首次启动初始化六种风格，不覆盖管理端已经发布的修订。 */
@Component
public class AdaptationStyleInitializer implements ApplicationRunner {
    private final AdaptationStyleRepository styles;
    /** 注入不可变版本仓储。 */
    public AdaptationStyleInitializer(AdaptationStyleRepository styles) { this.styles = styles; }
    /** 复用同一发布事务与幂等收据，多实例启动可以安全重试。 */
    @Override
    public void run(ApplicationArguments arguments) {
        var codes = styles.catalog().items().stream().map(item -> item.code()).collect(java.util.stream.Collectors.toSet());
        for (Publish item : defaults()) {
            // 只补缺失的内置模板，后续更新必须显式走管理发布。
            if (!codes.contains(item.code())) styles.publish(item, "builtin");
        }
    }
    /** 固定模板只是初始选项，不形成题材白名单。 */
    public static java.util.List<Publish> defaults() {
        return java.util.List.of(
                new Publish("builtin-style-free-rewrite-v1", "free-rewrite", 0, "\u81ea\u7531\u6539\u5199", "\u5145\u5206\u9075\u5faa\u672c\u6b21\u610f\u56fe\uff0c\u4e0d\u9884\u8bbe\u6587\u98ce\u3002",
                        "Rewrite the whole chapter freely according to the current intent. You may rephrase, restructure paragraphs, expand description and dialogue, or condense repetition. Preserve the chapter's causal plot, established relationships, facts, knowledge boundaries and entry/exit states. Do not introduce a new plot branch."),
                new Publish("builtin-style-inner-life-v1", "inner-life", 0, "\u7ec6\u817b\u5fc3\u7406", "\u4fa7\u91cd\u4eba\u7269\u5fc3\u7406\u3001\u611f\u5b98\u4e0e\u60c5\u7eea\u5c42\u6b21\u3002",
                        "Emphasize nuanced inner life, sensory detail, emotional progression and subtext. Keep thoughts consistent with what each character knows. Avoid inventing backstory, motivations or new decisions that change the plot."),
                new Publish("builtin-style-cinematic-v1", "cinematic", 0, "\u7535\u5f71\u53d9\u4e8b", "\u4fa7\u91cd\u955c\u5934\u611f\u3001\u52a8\u4f5c\u4e0e\u573a\u666f\u8868\u73b0\u3002",
                        "Use cinematic visual staging, precise physical action, atmospheric details and vivid dialogue. Preserve viewpoint and event order; do not invent plot-driving props, locations or actions."),
                new Publish("builtin-style-suspense-v1", "suspense", 0, "\u60ac\u7591\u5f20\u529b", "\u901a\u8fc7\u8282\u594f\u3001\u7559\u767d\u4e0e\u4fe1\u606f\u5448\u73b0\u589e\u5f3a\u5f20\u529b\u3002",
                        "Heighten suspense through pacing, pauses and selective presentation of existing information. Do not add clues, suspects, secrets, threats, foreshadowed events or premature revelations absent from the source."),
                new Publish("builtin-style-high-momentum-v1", "high-momentum", 0, "\u723d\u6587\u8282\u594f", "\u7a81\u51fa\u51b2\u7a81\u3001\u53cd\u5dee\u4e0e\u65e2\u6709\u60c5\u8282\u7684\u60c5\u7eea\u56de\u62a5\u3002",
                        "Strengthen momentum, contrast, crisp dialogue and the emotional payoff of existing events. Do not change outcomes, powers, resources, victories, defeats or relationships."),
                new Publish("builtin-style-classical-v1", "classical", 0, "\u53e4\u98ce\u96c5\u81f4", "\u4f7f\u7528\u96c5\u81f4\u53e4\u5178\u63aa\u8f9e\uff0c\u4fdd\u6301\u6e05\u6670\u6613\u8bfb\u3002",
                        "Use elegant classical Chinese-inspired diction, rhythm and imagery while remaining readable in the source language. Preserve the established era, institutions, identities and factual setting; do not turn a modern story into an ancient one."));
    }
}

