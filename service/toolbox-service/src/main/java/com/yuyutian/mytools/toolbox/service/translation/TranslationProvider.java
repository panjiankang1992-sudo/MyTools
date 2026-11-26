package com.yuyutian.mytools.toolbox.service.translation;

import java.io.IOException;

/** 固定翻译上游边界，测试可注入替身但客户端不能指定上游地址。 */
public interface TranslationProvider {

    /**
     * 翻译一项正文并返回经过语言与内容校验的结果。
     *
     * @param text 经过输入限制的正文
     * @param chineseSource 是否由中文翻译到英文
     * @return 有效的目标语言正文
     * @throws IOException 上游不可用或结果不合法
     * @throws InterruptedException 请求被取消
     */
    String translate(String text, boolean chineseSource) throws IOException, InterruptedException;
}
