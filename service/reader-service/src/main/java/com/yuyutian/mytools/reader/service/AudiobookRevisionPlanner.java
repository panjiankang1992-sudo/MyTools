package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.AudiobookChapterChange;
import com.yuyutian.mytools.reader.model.AudiobookChapterPlan;
import com.yuyutian.mytools.reader.model.AudiobookChapterSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于章节身份和规范化正文摘要制定有声书增量处理计划。
 */
@Component
public class AudiobookRevisionPlanner {

    /**
     * 对比上一版本和当前版本，确定每个当前章节的复用或重跑策略。
     *
     * @param previous 上一已完成书籍版本的章节快照
     * @param current 当前冻结书籍版本的章节快照
     * @return 当前章节的有序处理计划
     */
    public List<AudiobookChapterPlan> plan(List<AudiobookChapterSnapshot> previous,
                                           List<AudiobookChapterSnapshot> current) {
        Map<String, AudiobookChapterSnapshot> previousByIdentity = indexByIdentity(previous);
        Map<String, AudiobookChapterSnapshot> previousByContent = indexUniqueByContent(previous);
        Set<String> usedIdentities = new HashSet<>();
        List<AudiobookChapterPlan> plans = new ArrayList<>();
        for (AudiobookChapterSnapshot chapter : current) {
            AudiobookChapterSnapshot sameIdentity = previousByIdentity.get(chapter.identitySha256());
            if (sameIdentity != null) {
                usedIdentities.add(sameIdentity.identitySha256());
                if (sameIdentity.contentSha256().equals(chapter.contentSha256())) {
                    AudiobookChapterChange change = sameIdentity.chapterIndex() == chapter.chapterIndex()
                            ? AudiobookChapterChange.UNCHANGED : AudiobookChapterChange.MOVED_OR_RENUMBERED;
                    plans.add(new AudiobookChapterPlan(chapter, change, true, sameIdentity.chapterIndex()));
                } else {
                    plans.add(new AudiobookChapterPlan(chapter, AudiobookChapterChange.MODIFIED,
                            false, sameIdentity.chapterIndex()));
                }
                continue;
            }
            AudiobookChapterSnapshot sameContent = previousByContent.get(chapter.contentSha256());
            if (sameContent != null && !usedIdentities.contains(sameContent.identitySha256())) {
                usedIdentities.add(sameContent.identitySha256());
                plans.add(new AudiobookChapterPlan(chapter, AudiobookChapterChange.MOVED_OR_RENUMBERED,
                        true, sameContent.chapterIndex()));
                continue;
            }
            plans.add(new AudiobookChapterPlan(chapter, AudiobookChapterChange.APPENDED, false, null));
        }
        return List.copyOf(plans);
    }

    /**
     * 返回不再存在于当前目录的历史章节身份。
     *
     * @param previous 上一已完成书籍版本的章节快照
     * @param current 当前冻结书籍版本的章节快照
     * @return 已删除章节身份摘要
     */
    public Set<String> removedIdentities(List<AudiobookChapterSnapshot> previous,
                                         List<AudiobookChapterSnapshot> current) {
        Set<String> currentIdentities = new HashSet<>();
        for (AudiobookChapterSnapshot chapter : current) {
            currentIdentities.add(chapter.identitySha256());
        }
        Set<String> removed = new HashSet<>();
        for (AudiobookChapterSnapshot chapter : previous) {
            if (!currentIdentities.contains(chapter.identitySha256())) {
                removed.add(chapter.identitySha256());
            }
        }
        return Set.copyOf(removed);
    }

    private Map<String, AudiobookChapterSnapshot> indexByIdentity(List<AudiobookChapterSnapshot> chapters) {
        Map<String, AudiobookChapterSnapshot> indexed = new HashMap<>();
        for (AudiobookChapterSnapshot chapter : chapters) {
            validate(chapter);
            if (indexed.putIfAbsent(chapter.identitySha256(), chapter) != null) {
                throw new IllegalArgumentException("duplicate audiobook chapter identity");
            }
        }
        return indexed;
    }

    private Map<String, AudiobookChapterSnapshot> indexUniqueByContent(List<AudiobookChapterSnapshot> chapters) {
        Map<String, AudiobookChapterSnapshot> indexed = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (AudiobookChapterSnapshot chapter : chapters) {
            validate(chapter);
            AudiobookChapterSnapshot previous = indexed.putIfAbsent(chapter.contentSha256(), chapter);
            if (previous != null) {
                duplicates.add(chapter.contentSha256());
            }
        }
        for (String duplicate : duplicates) {
            indexed.remove(duplicate);
        }
        return indexed;
    }

    private void validate(AudiobookChapterSnapshot chapter) {
        if (chapter == null || chapter.chapterIndex() < 0 || !sha256(chapter.identitySha256())
                || !sha256(chapter.contentSha256())) {
            throw new IllegalArgumentException("invalid audiobook chapter snapshot");
        }
    }

    private boolean sha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
