package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationInput;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationViews;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationSourceCheck;
import com.yuyutian.mytools.reader.repository.adaptation.ChapterAdaptationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/** 书架改编应用服务，只编排身份及持久操作，不在请求线程等待模型生成。 */
@Service
public class ChapterAdaptationService {
    private final ChapterAdaptationRepository repository;
    private final SourceLocatorCipher cursorSigner;
    private final Clock clock;
    private final AdaptationSourceCheckService sourceChecks;

    /** 注入版本库及具有用途隔离的游标签名器。 */
    @Autowired
    public ChapterAdaptationService(ChapterAdaptationRepository repository, SourceLocatorCipher cursorSigner,
                                     AdaptationSourceCheckService sourceChecks) {
        this(repository, cursorSigner, sourceChecks, Clock.systemUTC());
    }

    /** 使用固定时钟测试游标的过期边界。 */
    public ChapterAdaptationService(ChapterAdaptationRepository repository, SourceLocatorCipher cursorSigner,
                                     AdaptationSourceCheckService sourceChecks, Clock clock) {
        this.repository = repository;
        this.cursorSigner = cursorSigner;
        this.clock = clock;
        this.sourceChecks = sourceChecks;
    }

    /** 首次改编只接受本人书架的规范章节标识。 */
    public AdaptationViews.Accepted create(long ownerId, UUID shelfId, UUID chapterId, AdaptationInput input) {
        return repository.create(input.command(ownerId, shelfId, chapterId, AdaptationRequestKind.INITIAL, null));
    }

    /** 以已存在的本人版本解析目标，再由创建事务验证成功结果及谱系。 */
    public AdaptationViews.Accepted derive(long ownerId, UUID triggerId, AdaptationRequestKind kind, AdaptationInput input) {
        if (kind == null || kind == AdaptationRequestKind.INITIAL) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        }
        var target = repository.targetForDerivation(ownerId, triggerId, kind, input.idempotencyKey());
        return repository.create(input.command(ownerId, target.shelfBookId(), target.chapterId(), kind, triggerId));
    }

    /** 返回持久状态；退出 App 不会触发此服务取消后台工作。 */
    public AdaptationViews.Progress progress(long ownerId, UUID adaptationId) {
        return repository.progress(ownerId, adaptationId);
    }

    /** 详情只合并短期核验收据；历史正文不因书源失效而重新读取或改变。 */
    public AdaptationViews.Detail detail(long ownerId, UUID adaptationId) {
        var detail = repository.detail(ownerId, adaptationId);
        if (detail.result() == null || "STALE".equals(detail.sourceRelation())) return detail;
        var check = sourceChecks.status(ownerId, adaptationId);
        String relation = "CURRENT".equals(check.status()) || "STALE".equals(check.status()) ? check.status() : "UNKNOWN";
        return new AdaptationViews.Detail(detail.version(), relation, check.sourceCheckedAt(), detail.result(), detail.attempts());
    }

    /** 请求后台核验，仅接受业务版本身份。 */
    public AdaptationSourceCheck verifySource(long ownerId, UUID adaptationId) { return sourceChecks.request(ownerId, adaptationId); }

    /** 查询核验进度，不触发额外网络读取。 */
    public AdaptationSourceCheck sourceStatus(long ownerId, UUID adaptationId) { return sourceChecks.status(ownerId, adaptationId); }

    /** 按需展开允许展示的生成尝试。 */
    public AdaptationViews.Output attempt(long ownerId, UUID adaptationId, UUID attemptId) {
        return repository.attempt(ownerId, adaptationId, attemptId);
    }

    /** 用户主动取消时才提交取消状态。 */
    public AdaptationViews.Progress cancel(long ownerId, UUID adaptationId) {
        return repository.cancel(ownerId, adaptationId);
    }

    /** 游标绑定用途、用户、图书、章节和期限，不接受任意版本号作为公共游标。 */
    public AdaptationViews.History history(long ownerId, UUID shelfId, UUID chapterId, int limit, String cursor) {
        Long beforeRevision = null;
        if (cursor != null) {
            try {
                String[] fields = cursorSigner.verifyCursor(cursor).split(":", -1);
                if (fields.length != 6 || !"adaptation-history-v1".equals(fields[0])
                        || !Long.toString(ownerId).equals(fields[1]) || !shelfId.toString().equals(fields[2])
                        || !chapterId.toString().equals(fields[3]) || Long.parseLong(fields[5]) <= clock.instant().getEpochSecond()) {
                    throw new IllegalArgumentException("Invalid adaptation cursor");
                }
                beforeRevision = Long.parseLong(fields[4]);
            } catch (IllegalArgumentException | ChapterAdaptationException exception) {
                // 游标签名错误不透传目录错误码或原始令牌。
                throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
            }
        }
        var page = repository.history(ownerId, shelfId, chapterId, limit, beforeRevision);
        String next = page.nextBeforeRevision() == null ? null : cursorSigner.signCursor("adaptation-history-v1:"
                + ownerId + ":" + shelfId + ":" + chapterId + ":" + page.nextBeforeRevision() + ":"
                + clock.instant().plusSeconds(900).getEpochSecond());
        return new AdaptationViews.History(page.items(), next);
    }
}
