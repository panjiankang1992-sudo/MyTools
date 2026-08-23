package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.ErrorCode;

/**
 * 资产自派生关系异常。
 */
public class ArtifactCycleException extends RuntimeException {

    /**
     * 创建资产自派生异常。
     */
    public ArtifactCycleException() {
        super(ErrorCode.ARTIFACT_CYCLE.code());
    }
}
