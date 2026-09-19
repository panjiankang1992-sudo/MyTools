package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 可一次封存的完整上下文，领域校验不能替代数据库中的所有权与版本复核。 */
public record AdaptationContextSnapshot(AdaptationContextIdentity identity, AdaptationRequestKind kind,
                                        List<AdaptationContextFragment> fragments) {

    public static final String MANIFEST_VERSION = "adaptation-context-framed-v1";

    /** 复制所有片段并校验角色集合、邻接关系和底稿来源。 */
    public AdaptationContextSnapshot {
        if (identity == null || kind == null || fragments == null || fragments.size() < 4
                || fragments.size() > 5 || fragments.stream().anyMatch(fragment -> fragment == null)) {
            throw incomplete();
        }
        Map<AdaptationContextRole, AdaptationContextFragment> roles = new EnumMap<>(AdaptationContextRole.class);
        for (AdaptationContextFragment fragment : fragments) {
            // 首版每个角色只能包含一段，不能靠重复角色掩盖缺失邻章。
            if (roles.putIfAbsent(fragment.role(), fragment) != null) {
                throw incomplete();
            }
        }
        requireRole(roles, AdaptationContextRole.TARGET_ORIGINAL, identity.targetChapterId());
        requireRole(roles, AdaptationContextRole.CATALOG_METADATA, null);
        requireRole(roles, identity.previousChapterId() == null ? AdaptationContextRole.BOOK_START_MARKER
                : AdaptationContextRole.PREVIOUS_TAIL, identity.previousChapterId());
        requireRole(roles, identity.nextChapterId() == null ? AdaptationContextRole.BOOK_END_MARKER
                : AdaptationContextRole.NEXT_HEAD, identity.nextChapterId());
        if (kind == AdaptationRequestKind.OPTIMIZE) {
            requireRole(roles, AdaptationContextRole.BASE_INPUT, identity.targetChapterId());
        }
        int expectedCount = kind == AdaptationRequestKind.OPTIMIZE ? 5 : 4;
        if (roles.size() != expectedCount) {
            throw incomplete();
        }
        // 固定角色名排序并防御性复制，调用方修改原集合不会改变已计算的 manifest。
        fragments = fragments.stream().sorted(Comparator.comparing(fragment -> fragment.role().name())).toList();
    }

    /** 返回绑定任务、租户、目录版本、角色及全文摘要的确定性封存摘要。 */
    public String manifestSha256() {
        List<String> fields = new ArrayList<>(List.of(identity.adaptationId().toString(),
                Long.toString(identity.ownerId()), identity.shelfBookId().toString(), identity.bindingId().toString(),
                Long.toString(identity.bindingRevision()), Long.toString(identity.catalogRevision()),
                identity.targetChapterId().toString(), Integer.toString(identity.targetIndex()),
                Integer.toString(identity.catalogChapterCount()), kind.name(), Integer.toString(fragments.size())));
        for (AdaptationContextFragment fragment : fragments) {
            fields.add(fragment.role().name());
            fields.add("0");
            fields.add(fragment.sourceChapterId() == null ? null : fragment.sourceChapterId().toString());
            fields.add(fragment.contentSha256());
            fields.add(Integer.toString(fragment.codepointCount()));
        }
        return AdaptationText.fingerprint(MANIFEST_VERSION, fields);
    }

    /** 返回原章摘要，优化底稿始终与原章分别保留。 */
    public String originalContentSha256() {
        return fragment(AdaptationContextRole.TARGET_ORIGINAL).contentSha256();
    }

    /** 返回本次生成的底稿摘要，重新改编不能继承旧输出。 */
    public String baseContentSha256() {
        return fragment(kind == AdaptationRequestKind.OPTIMIZE ? AdaptationContextRole.BASE_INPUT
                : AdaptationContextRole.TARGET_ORIGINAL).contentSha256();
    }

    /** 避免记录类型的默认字符串递归输出正文。 */
    @Override
    public String toString() {
        return "AdaptationContextSnapshot[kind=" + kind + ", fragments=" + fragments.size() + ", content=redacted]";
    }

    private AdaptationContextFragment fragment(AdaptationContextRole role) {
        return fragments.stream().filter(fragment -> fragment.role() == role).findFirst().orElseThrow(
                AdaptationContextSnapshot::incomplete);
    }

    private static void requireRole(Map<AdaptationContextRole, AdaptationContextFragment> roles,
                                    AdaptationContextRole role, UUID sourceChapterId) {
        AdaptationContextFragment fragment = roles.get(role);
        if (fragment == null || !java.util.Objects.equals(sourceChapterId, fragment.sourceChapterId())) {
            throw incomplete();
        }
    }

    private static ChapterAdaptationException incomplete() {
        return new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
    }
}
