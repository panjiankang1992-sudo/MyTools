package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;

/**
 * 将已支持网站确定性转换为声明式书源的适配器。
 */
public interface BookSourceSiteAdapter {

    /**
     * 判断适配器是否支持目标站点。
     *
     * @param target 目标地址
     * @return 是否支持
     */
    boolean supports(URI target);

    /**
     * 生成可由规则运行时执行的书源快照。
     *
     * @param target 目标地址
     * @param objectMapper JSON转换器
     * @return 书源JSON
     */
    String createSnapshot(URI target, ObjectMapper objectMapper);
}
