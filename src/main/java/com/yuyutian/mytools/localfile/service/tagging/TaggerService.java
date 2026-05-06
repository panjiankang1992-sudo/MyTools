package com.yuyutian.mytools.localfile.service.tagging;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.entity.FileTag;

import java.util.List;

/**
 * 打标签服务接口。
 *
 * @author mytools
 * @since 2026-05-04
 */
public interface TaggerService {

    /**
     * 对文件进行打标签。
     *
     * @param file 本地文件实体
     * @return 生成的标签列表
     */
    List<FileTag> tagFile(LocalFile file);

    /**
     * 批量处理未打标签的文件。
     *
     * @param batchSize 批量处理大小
     * @return 成功处理的数量
     */
    int processUntaggedFiles(int batchSize);
}
