package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import org.springframework.stereotype.Service;

/**
 * 有声书新工作受控灰度策略。已完成版本的查询和播放不经过该策略。
 */
@Service
public class AudiobookGenerationAvailability {

    private final ReaderProperties properties;

    /**
     * 创建有声书灰度策略。
     *
     * @param properties 阅读服务配置
     */
    public AudiobookGenerationAvailability(ReaderProperties properties) {
        this.properties = properties;
    }

    /**
     * 确认指定所有者可以创建会触发分析或合成的新工作。
     *
     * @param ownerId 所有者标识
     */
    public void requireNewWorkAllowed(long ownerId) {
        if (!properties.audiobookGenerationEnabled()) {
            throw new AudiobookGenerationUnavailableException();
        }
        var allowedOwnerIds = properties.audiobookAllowedOwnerIds();
        // 白名单为空时保留全量开放语义；非空时只允许明确列出的所有者。
        if (allowedOwnerIds != null && !allowedOwnerIds.isEmpty() && !allowedOwnerIds.contains(ownerId)) {
            throw new AudiobookGenerationUnavailableException();
        }
    }
}
