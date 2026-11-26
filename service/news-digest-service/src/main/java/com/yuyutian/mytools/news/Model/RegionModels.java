package com.yuyutian.mytools.news.Model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 执行节点报告与对用户可见的地区协议分离，公开协议不包含出口指纹。 */
public final class RegionModels {
    private RegionModels() { }

    /** 探测失败与定位失败必须区分，否则无法安全复用同一出口的缓存。 */
    public enum ObservationStatus { RESOLVED, LOOKUP_FAILED, EGRESS_UNAVAILABLE }

    /** 只接受供应商给出的行政区标识，不能由经纬度猜测城市。 */
    public record GeoEvidence(@NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
                              @Pattern(regexp = "[A-Z0-9]{1,3}") String subdivisionCode,
                              @Positive Long cityGeonameId, @Size(max = 160) String cityName,
                              @Min(0) @Max(20000) Integer accuracyRadiusKm) { }

    /** 节点专用鉴权下的去标识报告，不接收或存储完整公网地址。 */
    public record RegionObservation(@NotNull UUID id,
                                     @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String nodeId,
                                     @NotNull Instant observedAt, @NotNull ObservationStatus status,
                                     @Pattern(regexp = "[a-f0-9]{64}") String egressFingerprint,
                                     @Pattern(regexp = "IPV4|IPV6") String addressFamily,
                                     @NotBlank @Pattern(regexp = "DIRECT|EXPLICIT_PROXY") String networkMode,
                                     @NotBlank @Pattern(regexp = "MAXMIND_MMDB|MAXMIND_WEB|IPWHOIS") String provider,
                                     @NotBlank @Size(max = 120) String providerVersion,
                                     @Valid GeoEvidence geo) { }

    /** 来源包内容不可修改，新版本替代旧版本，选择时保留实际生效时间。 */
    public record RegionSourcePack(@NotBlank @Pattern(regexp = "GEONAME-[1-9][0-9]{0,18}|[A-Z]{2}-[A-Z0-9]{1,3}") String regionCode,
                                    @Min(1) int version, @NotBlank @Size(max = 160) String label,
                                    @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> sourceIds,
                                    @NotNull Instant verifiedAt) {
        /** 防止调用方在登记后修改来源列表。 */
        public RegionSourcePack { if (sourceIds != null) sourceIds = List.copyOf(sourceIds); }
    }

    /** 日报冻结后的地区说明不含公网地址或可关联的出口指纹。 */
    public record RegionDetails(UUID snapshotId, String precision, String countryCode, String subdivisionCode,
                                 String cityName, Integer accuracyRadiusKm, String provider, String providerVersion,
                                 String networkMode, Instant observedAt, Instant expiresAt, boolean cached,
                                 String sourcePackLabel, Integer sourcePackVersion, List<String> sourceIds) { }

    /** 回执只确认报告持久化，不能误认为本地来源或日报已完备。 */
    public record ObservationReceipt(UUID id, String status) { }
}
