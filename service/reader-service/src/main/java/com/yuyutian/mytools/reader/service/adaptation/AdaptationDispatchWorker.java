package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.repository.adaptation.AdaptationDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 网络调用位于持久领取与回写两个短事务之间；进程退出后由租约和相同业务键恢复。 */
@Component
@ConditionalOnProperty(prefix = "reader.adaptation-dispatch", name = "enabled", havingValue = "true")
public class AdaptationDispatchWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdaptationDispatchWorker.class);
    private final AdaptationDispatchRepository repository;
    private final AdaptationSchedulerGateway gateway;
    private final String worker = "adaptation-" + UUID.randomUUID();

    /** 注入数据库控制面和只接受改编身份的 Scheduler 客户端。 */
    public AdaptationDispatchWorker(AdaptationDispatchRepository repository, AdaptationSchedulerGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    /** 每轮处理一个持久动作；失败由数据库租约恢复，日志不包含异常正文、地址和凭据。 */
    @Scheduled(fixedDelayString = "${reader.adaptation-dispatch.poll-ms:1500}")
    public void tick() {
        try {
            processOne();
        } catch (RuntimeException exception) {
            // 不输出可能包含 SQL 参数或请求头的异常链，未释放领取会在短租约后接管。
            LOG.warn("Adaptation dispatch iteration failed");
        }
    }

    /** 执行一次可测试的派发或取消，返回是否实际领取了任务。 */
    public boolean processOne() {
        var claim = repository.claim(worker);
        if (claim == null) {
            return false;
        }
        try {
            var view = switch (claim.action()) {
                case SUBMIT -> gateway.submit(claim.adaptationId());
                case OBSERVE -> gateway.find(claim.adaptationId());
                case CANCEL -> gateway.cancel(claim.adaptationId());
            };
            if (repository.acknowledge(claim, view)) {
                // 迟到回复只有在业务确实停止时才补偿；同键的新正常领取不能被旧 worker 误杀。
                gateway.cancel(claim.adaptationId());
            }
        } catch (AdaptationSchedulerException exception) {
            repository.failed(claim, exception.retryable());
        }
        return true;
    }
}
