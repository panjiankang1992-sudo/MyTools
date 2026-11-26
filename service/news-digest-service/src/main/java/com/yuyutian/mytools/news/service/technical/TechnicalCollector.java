package com.yuyutian.mytools.news.service.technical;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import java.time.Instant;

/** 专用来源在联网阶段产生提交单元，不直接修改文章或快照表。 */
public interface TechnicalCollector {
    /** 返回对应的来源协议。 */
    SourceKind kind();

    /** 在有限预算内采集，原始来源列表不保证覆盖整个社区。 */
    CollectedBatch collect(SourceState source, Instant startedAt);
}
