package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.config.ReaderChapterContentProperties;
import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationSourceCheck;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationSourceCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 独立于模型生成的来源核验；查询线程不访问书源，正文只在有界后台检查中存在。 */
@Service
public class AdaptationSourceCheckService {
    private final AdaptationSourceCheckRepository repository;
    private final ShelfChapterContentReader reader;
    private final ReaderShelfChapterProperties shelf;
    private final int leaseSeconds;

    /** 每章最多目录和正文各一次请求，为三章完整读取预留有限租约。 */
    public AdaptationSourceCheckService(AdaptationSourceCheckRepository repository, ShelfChapterContentReader reader,
                                        ReaderShelfChapterProperties shelf, ReaderChapterContentProperties content) {
        this.repository = repository; this.reader = reader; this.shelf = shelf;
        leaseSeconds = content.requestTimeoutSeconds() * 6 + 30;
    }

    /** 显式请求核验时复用在途检查，不要求新建模型版本的开关开启。 */
    public AdaptationSourceCheck request(long owner, UUID id) {
        if (!shelf.enabled() || !shelf.runtimeEgressVerified()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_UNAVAILABLE);
        return repository.request(owner, id);
    }

    /** 只查询短期证明，不在读取历史时触发书源请求。 */
    public AdaptationSourceCheck status(long owner, UUID id) { return repository.status(owner, id); }

    /** 网络调用之前和之后复核领取及目录；失败不重复发送外部读取。 */
    public boolean processOne() {
        if (!shelf.enabled() || !shelf.runtimeEgressVerified()) return false;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        var claim = repository.claim(leaseSeconds);
        if (claim == null) return false;
        try {
            var plan = repository.plan(claim);
            var identity = plan.identity();
            List<UUID> ids = new ArrayList<>(List.of(identity.targetChapterId()));
            if (identity.previousChapterId() != null) ids.add(identity.previousChapterId());
            if (identity.nextChapterId() != null) ids.add(identity.nextChapterId());
            List<ShelfChapterModels.Content> contents = new ArrayList<>();
            for (UUID chapter : ids) {
                // 不把初次计划当作整个检查期间仍然有效的所有权证明。
                if (!repository.plan(claim).identity().equals(identity)) throw new ChapterAdaptationException(ErrorCode.CHAPTER_CATALOG_STALE);
                contents.add(reader.content(identity.ownerId(), identity.shelfBookId(), chapter));
            }
            repository.complete(claim, plan, contents);
        } catch (ChapterAdaptationException exception) {
            repository.fail(claim, exception.errorCode());
        } catch (RuntimeException exception) {
            // 只记录稳定码；网络或数据库异常链可能包含私有数据。
            repository.fail(claim, ErrorCode.ADAPTATION_UNAVAILABLE);
        }
        return true;
    }
}
