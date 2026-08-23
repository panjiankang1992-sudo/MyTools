package com.yuyutian.mytools.storage.model;

/**
 * 受控 rclone 远端任务状态。
 *
 * @param jobId rclone 任务标识
 * @param finished 是否结束
 * @param success 是否成功
 * @param errorCode 稳定错误码
 */
public record RemoteJobView(long jobId, boolean finished, boolean success, String errorCode) {
}
