package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.EvidenceModels.*;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.config.RetentionProperties;
import com.yuyutian.mytools.news.mapper.EvidenceRepository;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 历史阅读按当前策略说明原始证据保留状态，不修改历史节选。 */
@Service
public class EvidenceService {
    private final NewsRepository news;
    private final EvidenceRepository evidence;
    private final RetentionProperties policy;
    private final Clock clock;
    /** 注入所有者校验、证据读取及服务端时钟。 */
    public EvidenceService(NewsRepository news,EvidenceRepository evidence,RetentionProperties policy,Clock clock){this.news=news;this.evidence=evidence;this.policy=policy;this.clock=clock;}
    /** 必须先成功读取本人日报，不能将接口作为任意文章存在性探测器。 */
    public Snapshot inspect(long owner,UUID id) {
        Edition edition=news.edition(owner,id);
        Instant now=clock.instant();
        Map<String,List<Citation>> references=new LinkedHashMap<>();
        edition.sections().values().forEach(section->section.items().forEach(item->{
            if(item.detail()!=null)for(Citation citation:item.detail().citations())
                references.computeIfAbsent(citation.articleId(),ignored->new java.util.ArrayList<>()).add(citation);
        }));
        Map<String,EvidenceRepository.Stored> stored=evidence.find(references.keySet());
        return new Snapshot(id,now,references.entrySet().stream().map(entry->item(entry.getKey(),entry.getValue(),stored.get(entry.getKey()),now)).toList());
    }
    private Item item(String id,List<Citation> references,EvidenceRepository.Stored stored,Instant now) {
        // 缺失与版本不符均不能冒充可用，保留不可变引用供用户回看。
        if(stored==null || references.stream().anyMatch(citation->!matches(citation,stored.article())))return new Item(id,Status.UNAVAILABLE,null);
        Article article=stored.article();
        int hours=stored.hours()==null?policy.evidenceHours():Math.min(policy.evidenceHours(),stored.hours());
        Instant expires=article.fetchedAt().plusSeconds(hours*3600L);
        // 到期即显示过期，不依赖清理任务是否已执行，也不会因策略放宽恢复已清除的正文。
        if(stored.expired() || article.evidenceExpiredAt()!=null || !expires.isAfter(now))return new Item(id,Status.RETENTION_EXPIRED,expires);
        return new Item(id,article.evidenceText()==null || article.evidenceText().isBlank()?Status.UNAVAILABLE:Status.AVAILABLE,expires);
    }
    private boolean matches(Citation citation,Article article) {
        return Objects.equals(citation.articleId(),article.id()) && Objects.equals(citation.sourceId(),article.source().id())
                && Objects.equals(citation.contentHash(),article.contentHash()) && Objects.equals(citation.url(),DigestEngine.evidenceUrl(article))
                && Objects.equals(citation.fetchedAt(),article.fetchedAt());
    }
}
