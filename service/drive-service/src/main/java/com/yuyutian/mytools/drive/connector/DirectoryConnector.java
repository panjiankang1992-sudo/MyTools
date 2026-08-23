package com.yuyutian.mytools.drive.connector;

import com.yuyutian.mytools.drive.model.DriveModels.IndexItem;

import java.util.List;

/**
 * Drive 目录扫描连接器边界。
 */
public interface DirectoryConnector {
    /**
     * 列出一个目录。
     *
     * @param providerKey 服务端 Provider 键
     * @param path 相对路径
     * @return 索引候选
     */
    List<IndexItem> list(String providerKey, String path);
}
