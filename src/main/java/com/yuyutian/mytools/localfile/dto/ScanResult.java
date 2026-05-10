package com.yuyutian.mytools.localfile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扫描结果响应。
 *
 * @author mytools
 * @since 2026-05-10
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanResult {

    /** 扫描的文件数量 */
    private int scannedCount;

    /** 新增的文件数量 */
    private int newCount;
}
