package com.yuyutian.mytools.rss.service;

import com.yuyutian.mytools.feed.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** 发现与后台采集共用有界网络槽，禁止用户预览耗尽工作线程。 */
@Service
public class FeedAccessService {
    private final FeedTransport transport;
    private final Semaphore slots = new Semaphore(4);
    private final Set<String> hosts = ConcurrentHashMap.newKeySet();
    /** 注入生产传输器或测试固定响应。 */
    public FeedAccessService(FeedTransport transport) { this.transport = transport; }
    /** 一次最多四个请求，每个主机仅允许一个在途请求。 */
    public FeedModels.Fetch fetch(String url, String etag, String modified) {
        String host = PublicNetworkPolicy.requireHttps(url).getHost().toLowerCase(java.util.Locale.ROOT);
        if (!slots.tryAcquire()) throw new RssException(ErrorCode.RSS_007);
        boolean entered = false;
        try {
            entered = hosts.add(host);
            if (!entered) throw new RssException(ErrorCode.RSS_007);
            return transport.fetch(url, etag, modified);
        } finally {
            if (entered) hosts.remove(host);
            slots.release();
        }
    }
    /** 将传输类别映射为统一业务错误，不泄漏 URL 或正文。 */
    public static ErrorCode code(FeedException exception) {
        return switch (exception.kind()) {
            case URL -> ErrorCode.RSS_008;
            case LIMIT -> ErrorCode.RSS_006;
            case FORMAT -> ErrorCode.RSS_004;
            case TRANSPORT -> ErrorCode.RSS_003;
        };
    }
}
