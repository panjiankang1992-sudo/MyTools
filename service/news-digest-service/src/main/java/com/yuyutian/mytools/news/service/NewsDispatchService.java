package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.mapper.DispatchRepository;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** 新闻出站只提交固定内容，不接触提供方凭据。 */
@Service
public class NewsDispatchService {
    private final DispatchRepository repository;
    private final NewsMessagingClient messaging;
    private final DeliveryProperties properties;
    private final Clock clock;
    private final Validator validator;
    /** 注入持久出站队列和受控客户端。 */
    public NewsDispatchService(DispatchRepository repository, NewsMessagingClient messaging, DeliveryProperties properties, Clock clock, Validator validator) {
        this.repository=repository;this.messaging=messaging;this.properties=properties;this.clock=clock;this.validator=validator;
    }
    /** 禁用时不请求消息服务，页面仍可查看已有接收配置和历史。 */
    public List<Destination> destinations(long owner) { requireEnabled(); return messaging.destinations(owner); }
    /** 获取个人接收设置。 */
    public Targets targets(long owner) { return repository.targets(owner); }
    /** 开启目标前在线核验所有者和渠道能力；纯停用无需依赖远端可用。 */
    public Targets changeTargets(long owner, TargetChange request) {
        if(!validator.validate(request).isEmpty())throw new NewsException(ErrorCode.NEWS_002);
        Set<UUID> unique=new HashSet<>(); for(Selection s:request.targets()) if(!unique.add(s.destinationId()))throw new NewsException(ErrorCode.NEWS_002);
        Targets current=repository.targets(owner);
        if(current.revision()!=request.expectedRevision())throw new NewsException(ErrorCode.NEWS_022);
        Map<UUID,Destination> allowed=new HashMap<>();
        if(request.targets().stream().anyMatch(Selection::enabled)) {
            requireEnabled();for(Destination d:messaging.destinations(owner))allowed.put(d.id(),d);
        }
        Map<UUID,Target> old=new HashMap<>();for(Target t:current.targets())old.put(t.destinationId(),t);
        List<Target> targets=new ArrayList<>();
        for(Selection s:request.targets()) {
            Destination remote=allowed.get(s.destinationId());Target prior=old.get(s.destinationId());
            if(s.enabled() && (remote==null||!remote.enabled()||remote.channel()==Channel.QQ))throw new NewsException(ErrorCode.NEWS_024);
            if(!s.enabled() && prior==null && remote==null)throw new NewsException(ErrorCode.NEWS_003);
            targets.add(new Target(s.destinationId(),remote==null?prior.channel():remote.channel(),s.mode(),s.enabled()));
        }
        return repository.changeTargets(owner,request.expectedRevision(),targets,clock.instant());
    }
    /** 查询本人投递批次。 */
    public List<BatchView> history(long owner) { return repository.history(owner); }
    /** 查询本人投递详情。 */
    public BatchView get(long owner,UUID id) { return repository.get(owner,id); }
    /** 停止批次不要求发送开关开启。 */
    public BatchView cancel(long owner,UUID id) { return repository.cancel(owner,id,clock.instant()); }
    /** 重试只作用于用户选中的失败片段。 */
    public BatchView retry(long owner,UUID batch,UUID part,Retry request) {
        requireEnabled();if(!validator.validate(request).isEmpty())throw new NewsException(ErrorCode.NEWS_002);
        return repository.retry(owner,batch,part,request,clock.instant());
    }
    /** 修复配置后显式恢复原片段，不改写内容和身份。 */
    public void redrive(long owner,UUID batch,UUID part) { repository.redrive(owner,batch,part,clock.instant()); }
    /** 单轮最多二十次网络操作；失败退避、未知结果和取消都保留在原记录。 */
    public int relay() {
        if(!properties.enabled())return 0;
        repository.recover(clock.instant());
        int count=0;
        while(count<20) {
            var next=repository.claim(clock.instant());if(next.isEmpty())break;
            Claim claim=next.orElseThrow();process(claim);count++;
        }
        return count;
    }
    private void process(Claim claim) {
        Dispatch part=repository.part(claim.id(),false);Batch batch=repository.batch(part.batchId(),false);
        try {
            boolean stop=batch.cancelRequested()||!clock.instant().isBefore(batch.expiresAt());
            Receipt receipt;
            if(stop)receipt=messaging.submit(batch,part,true);
            else if(part.remoteId()==null)receipt=messaging.submit(batch,part,false);
            else if(part.retryGeneration()!=null)receipt=messaging.retry(batch,part);
            else receipt=messaging.get(batch,part);
            // 网络调用期间发生停用时，再次按原键请求取消，不谎报已终止的调用。
            if(!stop && repository.batch(batch.id(),false).cancelRequested())receipt=messaging.submit(batch,part,true);
            repository.reconcile(claim,receipt,clock.instant());
        } catch(NewsMessagingClient.Failure e) {repository.failed(claim,e.permanent(),clock.instant());}
        catch(NewsException e) {if(e.code()!=ErrorCode.NEWS_016)throw e;}
    }
    private void requireEnabled(){if(!properties.enabled())throw new NewsException(ErrorCode.NEWS_021);}
}
