package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.SourceState;

/** 有界采集传输协议，解析器不自行访问外部链接。 */
public interface FeedTransport {
    /** 获取已注册来源的响应，保留条件请求状态。 */
    FeedResponse fetch(SourceState source);

    /** 补读原文时显式请求网页，避免站点按订阅头返回更新日志 Feed。 */
    default FeedResponse fetchPage(SourceState source) { return fetch(source); }

    /** 采集结果不携带认证信息。 */
    record FeedResponse(int status, byte[] body, String etag, String lastModified) { }
}
