package com.yuyutian.mytools.localfile.dto;

/**
 * 模型生成的受管媒体目录重命名建议。
 *
 * @param sourcePath 当前目录绝对路径
 * @param targetPath 建议目录绝对路径
 * @param currentName 当前目录名称
 * @param suggestedName 建议目录名称
 * @param basis 名称依据
 * @param status 建议状态
 * @param needsReview 是否需要人工复核
 * @param promptVersion 提示词版本
 */
public record DirectoryRenameProposal(
        String sourcePath,
        String targetPath,
        String currentName,
        String suggestedName,
        String basis,
        String status,
        boolean needsReview,
        String promptVersion) {
}
