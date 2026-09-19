package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;

import java.util.UUID;

/** 按本人书架的规范章节身份取文，实现必须验证来源并回写实际内容摘要。 */
@FunctionalInterface
public interface ShelfChapterContentReader {
    /** 在数据库事务之外读取完整正文，不能用客户端路径或正文替代。 */
    ShelfChapterModels.Content content(long ownerId, UUID shelfBookId, UUID chapterId);
}
