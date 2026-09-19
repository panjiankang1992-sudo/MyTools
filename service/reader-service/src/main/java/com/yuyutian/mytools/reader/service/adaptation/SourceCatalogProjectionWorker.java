package com.yuyutian.mytools.reader.service.adaptation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 依靠数据库租约恢复目录准备，不在内存 future 中保存业务状态。 */
@Component
public class SourceCatalogProjectionWorker {
    private final SourceShelfChapterService service;
    private final String workerId = UUID.randomUUID().toString();

    /** 创建独立 worker 身份，多个 Reader 实例可安全竞争任务。 */
    public SourceCatalogProjectionWorker(SourceShelfChapterService service) {
        this.service = service;
    }

    /** 小批领取并执行一个任务，网络调用不占用数据库事务。 */
    @Scheduled(fixedDelayString = "${reader.shelf-chapters.poll-ms:1500}")
    public void tick() {
        service.prepareOne(workerId);
    }
}
