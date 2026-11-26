package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量翻译请求，字段和值都由服务层严格校验。
 *
 * @param items 待翻译条目
 * @param subject 学科；省略时沿用英语翻译
 */
public record TranslationRequest(List<Item> items, Subject subject) {

    /**
     * 兼容原有英语翻译调用。
     *
     * @param items 待翻译条目
     */
    public TranslationRequest(List<Item> items) {
        this(items, Subject.ENGLISH);
    }

    /**
     * 从请求正文**严格**构造：禁止 Jackson 把数字或布尔值自动转成字符串，也拒绝未知字段。
     *
     * 这段校验原先只在公开访客控制器里（私有方法）✗。管理端要加自己的翻译入口时，
     * **它的形状必须与公开入口完全一致** ✗——否则两个入口对同一个正文的接受度会分叉 ✗。
     * 所以把它提到这里共用，而不是复制一份。
     *
     * @param body 请求正文
     * @return 严格校验后的请求
     */
    public static TranslationRequest strictFrom(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("items").isArray()
                || body.size() != (body.has("subject") ? 2 : 1)) {
            throw BusinessException.invalid("items", "invalidShape");
        }
        Subject subject = Subject.ENGLISH;
        // 新字段只接受明确的学科值，旧请求不需要补字段。
        if (body.has("subject")) {
            if (!body.path("subject").isTextual()) {
                throw BusinessException.invalid("subject", "invalidShape");
            }
            subject = Subject.parse(body.path("subject").textValue())
                    .orElseThrow(() -> BusinessException.invalid("subject", "invalidValue"));
        }
        var items = new ArrayList<Item>();
        for (JsonNode item : body.path("items")) {
            if (!item.isObject() || item.size() != 2 || !item.path("id").isTextual()
                    || !item.path("text").isTextual()) {
                throw BusinessException.invalid("items[" + items.size() + "]", "invalidShape");
            }
            items.add(new Item(item.path("id").textValue(), item.path("text").textValue()));
        }
        return new TranslationRequest(items, subject);
    }

    /**
     * 一项来源正文，身份仅用于把结果匹配回调用方当前修订。
     *
     * @param id 调用方条目标识
     * @param text 来源正文
     */
    public record Item(String id, String text) {
    }
}
