package com.yuyutian.mytools.dshconnector.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** DSH 探测词原子能力契约。 */
public final class ProbeTermModels {
    private ProbeTermModels() { }

    /** 探测词分析请求。 */
    public record Request(@Positive long ownerId, @NotNull UUID taskInstanceId,
                          @NotBlank @Size(max = 200) String clue) { }

    /** 有界探测词分析结果。 */
    public record Result(List<String> terms) {
        /** 返回不可变词集。 */
        @Override public List<String> terms() { return List.copyOf(terms); }
    }
}
