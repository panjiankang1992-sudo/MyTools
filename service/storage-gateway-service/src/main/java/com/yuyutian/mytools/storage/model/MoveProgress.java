package com.yuyutian.mytools.storage.model;

/**
 * 远端移动推进结果。
 *
 * @param phase 当前阶段
 * @param finished 是否已经结束
 * @param success 是否成功
 * @param recoveryRequired 是否需要恢复处理
 * @param errorCode 可选错误码
 */
public record MoveProgress(String phase, boolean finished, boolean success, boolean recoveryRequired,
                           String errorCode) {
}
