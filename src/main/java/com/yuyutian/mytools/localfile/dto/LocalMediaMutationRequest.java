package com.yuyutian.mytools.localfile.dto;

import java.util.List;

/**
 * 本地媒体写操作请求模型。
 */
public final class LocalMediaMutationRequest {

    private LocalMediaMutationRequest() {
    }

    /** 文件重命名请求。 */
    public record Rename(String name) {
    }

    /** 文件移动请求，目录使用媒体根目录内的绝对路径。 */
    public record Move(String directoryPath) {
    }

    /** 文件完整标签集合替换请求。 */
    public record Tags(List<String> tags) {
    }
}
