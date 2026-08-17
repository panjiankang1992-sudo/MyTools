package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveAccountView;
import com.yuyutian.mytools.drive.model.DriveDirectoryView;
import com.yuyutian.mytools.drive.model.DriveOpenTarget;

import java.util.List;

/**
 * 用户隔离的统一网盘查询服务。
 */
public interface DriveService {

    /** 查询当前用户启用的网盘。 */
    List<DriveAccountView> listDrives(Long userId);

    /** 浏览目录或从已同步索引中模糊搜索。 */
    DriveDirectoryView listItems(Long userId, Long driveId, String itemId, String keyword,
                                 String sort, String direction);

    /** 校验用户归属并解析一个可打开的文件。 */
    DriveOpenTarget resolveOpenTarget(Long userId, Long driveId, Long itemId);
}
