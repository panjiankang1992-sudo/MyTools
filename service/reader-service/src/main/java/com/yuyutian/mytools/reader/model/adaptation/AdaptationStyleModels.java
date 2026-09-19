package com.yuyutian.mytools.reader.model.adaptation;

import java.util.List;

/** 风格模板的只读摘要、不可变快照和独立管理发布契约。 */
public final class AdaptationStyleModels {
    private AdaptationStyleModels() { }

    /** APP 只读取展示信息，不取得或提交管理提示词。 */
    public record Summary(String code, int version, String name, String description, String promptSha256) { }
    /** 目录仅包含各模板的最新已发布版本。 */
    public record Catalog(List<Summary> items) {
        /** 防止外部修改响应集合。 */
        public Catalog { items = List.copyOf(items); }
    }
    /** 已发布版本及提示词，随业务版本原子冻结。 */
    public record Snapshot(Summary template, String prompt) {
        /** 模板提示词不进入默认诊断。 */
        @Override public String toString() { return "StyleSnapshot[redacted]"; }
    }
    /** 仅管理接口接收提示词；发布以当前修订和幂等键共同保护。 */
    public record Publish(String idempotencyKey, String code, int expectedLatestVersion,
                          String name, String description, String prompt) {
        /** 隐藏发布正文和请求键。 */
        @Override public String toString() { return "StylePublish[redacted]"; }
    }
}
