package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionFence;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 后台上下文准备，两段短事务之间只执行受控章节读取；没有公共正文写入接口。 */
@Service
public class AdaptationContextService {
    private final AdaptationContextRepository repository;
    private final ShelfChapterContentReader reader;

    /** 注入可信正文读取器，remote 适配仍需通过同一所有权及摘要契约。 */
    public AdaptationContextService(AdaptationContextRepository repository, ShelfChapterContentReader reader) {
        this.repository = repository;
        this.reader = reader;
    }

    /** 仅处理已获当前执行 claim 的版本，完整封存后不再次访问书源。 */
    public AdaptationContextSnapshot prepare(AdaptationExecutionFence execution) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
        var plan = repository.plan(execution);
        if (plan.alreadySealed() != null) {
            return plan.alreadySealed();
        }
        var identity = plan.identity();
        List<UUID> chapters = new ArrayList<>(List.of(identity.targetChapterId()));
        if (identity.previousChapterId() != null) {
            chapters.add(identity.previousChapterId());
        }
        if (identity.nextChapterId() != null) {
            chapters.add(identity.nextChapterId());
        }
        List<ShelfChapterModels.Content> contents = new ArrayList<>();
        for (UUID chapter : chapters) {
            // 每次网络读取前复核执行、取消及目录身份，不能在已取消后继续读取后续邻章。
            var current = repository.plan(execution);
            if (current.alreadySealed() != null) {
                return current.alreadySealed();
            }
            if (!current.identity().equals(identity)) {
                throw new ChapterAdaptationException(ErrorCode.CHAPTER_CATALOG_STALE);
            }
            contents.add(reader.content(identity.ownerId(), identity.shelfBookId(), chapter));
        }
        // 最多保留三份有上限的正文于内存；失败后不持久写入任何半份上下文。
        return repository.seal(execution, plan, contents);
    }
}
