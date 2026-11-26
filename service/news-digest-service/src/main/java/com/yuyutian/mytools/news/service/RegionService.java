package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RegionProperties;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.mapper.RegionRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 用指定执行节点的观测解析地区，不读取 Web 请求 IP 或新闻服务自身出口。 */
@Service
public class RegionService {
    private final RegionRepository repository;
    private final NewsRepository news;
    private final RegionProperties properties;
    private final Clock clock;

    /** 创建地区解析与冻结服务。 */
    public RegionService(RegionRepository repository, NewsRepository news, RegionProperties properties, Clock clock) {
        this.repository = repository; this.news = news; this.properties = properties; this.clock = clock;
    }

    /** 校验指定节点、新鲜观测及报告重放，不接受任意节点自报。 */
    public ObservationReceipt observe(RegionObservation report) { return observe(report,null); }

    /** 使用已接纳作业的报告预留，完整校验仍复用普通观测入口。 */
    public ObservationReceipt observe(RegionObservation report,UUID jobId) {
        if (properties.nodeId().isBlank() || !properties.nodeId().equals(report.nodeId())) throw new NewsException(ErrorCode.NEWS_013);
        var prior = repository.observation(report.id());
        if (prior.isPresent()) {
            if (!prior.orElseThrow().equals(report)) throw new NewsException(ErrorCode.NEWS_004);
            repository.saveObservation(report,clock.instant(),jobId);
            return new ObservationReceipt(report.id(), "STORED");
        }
        Instant now = clock.instant();
        if (report.observedAt().getNano() % 1_000_000 != 0 || report.observedAt().isAfter(now.plusSeconds(30))
                || report.observedAt().isBefore(now.minusSeconds(300))) {
            throw new NewsException(ErrorCode.NEWS_013);
        }
        // 只有真实取得出口的报告才能用于同出口缓存；失败报告不能夹带伪造地区。
        if (report.status() == ObservationStatus.EGRESS_UNAVAILABLE) {
            if (report.egressFingerprint() != null || report.addressFamily() != null || report.geo() != null) throw new NewsException(ErrorCode.NEWS_013);
        } else if (report.egressFingerprint() == null || report.addressFamily() == null
                || (report.status() == ObservationStatus.RESOLVED) != (report.geo() != null)) {
            throw new NewsException(ErrorCode.NEWS_013);
        }
        repository.saveObservation(report, now,jobId);
        return new ObservationReceipt(report.id(), "STORED");
    }

    /** 来源包只绑定同一地区的已登记本地来源，不接受任意网址。 */
    public RegionSourcePack registerPack(RegionSourcePack pack) {
        if (pack.verifiedAt().isAfter(clock.instant()) || new HashSet<>(pack.sourceIds()).size() != pack.sourceIds().size()) {
            throw new NewsException(ErrorCode.NEWS_002);
        }
        for (String id : pack.sourceIds()) {
            SourceSpec source = news.source(id).spec();
            if (source.category() != Category.LOCAL || !pack.regionCode().equals(source.regionCode())) throw new NewsException(ErrorCode.NEWS_002);
        }
        return repository.savePack(pack, clock.instant());
    }

    /** 返回当前执行节点地区说明，完整地址和指纹都不进入用户响应。 */
    public RegionSnapshot current() { return resolve(clock.instant(), false); }

    /** 在生成内容之前冻结请求地区，生成失败重试也复用同一快照。 */
    public RegionSnapshot bind(long ownerId, String key, String hash, Instant cutoff, Instant now) {
        return repository.bind(ownerId, key, hash, cutoff, now, () -> resolve(cutoff, true));
    }

    /** 返回指定截止时刻的地区质量，供能力声明使用。 */
    public String statusAt(Instant cutoff) { return resolve(cutoff, false).status(); }

    private RegionSnapshot resolve(Instant cutoff, boolean freeze) {
        if (properties.nodeId().isBlank()) return RegionSnapshot.unresolved();
        RegionObservation latest = repository.latest(properties.nodeId(), cutoff).orElse(null);
        if (latest == null) return new RegionSnapshot("UNRESOLVED", null, properties.nodeId(), null);
        if (latest.observedAt().isBefore(cutoff.minus(Duration.ofHours(24)))) return unavailable("STALE", latest, freeze);
        if (latest.status() == ObservationStatus.EGRESS_UNAVAILABLE) return unavailable("EGRESS_UNAVAILABLE", latest, freeze);
        boolean cached = latest.status() == ObservationStatus.LOOKUP_FAILED;
        RegionObservation evidence = cached ? repository.cached(latest, cutoff).orElse(null) : latest;
        if (evidence == null) return unavailable("LOOKUP_FAILED", latest, freeze);
        GeoEvidence geo = evidence.geo();
        String province = geo.subdivisionCode() == null ? null : geo.countryCode() + "-" + geo.subdivisionCode();
        boolean city = geo.cityGeonameId() != null && geo.accuracyRadiusKm() != null
                && geo.accuracyRadiusKm() <= properties.maximumCityRadiusKm();
        String code = city ? "GEONAME-" + geo.cityGeonameId() : province;
        String precision = city ? "CITY" : province != null ? "PROVINCE" : "COUNTRY";
        RegionSourcePack pack = city ? repository.pack(code, cutoff).orElse(null) : null;
        // 只有显式允许省级补充时才扩大范围，未知城市不能匹配全部本地来源。
        if (pack == null && province != null && properties.allowProvinceFallback()) {
            RegionSourcePack provincial = repository.pack(province, cutoff).orElse(null);
            if (provincial != null || !city) { pack = provincial; code = province; precision = "PROVINCE"; }
        }
        String status = "COUNTRY".equals(precision) ? "COUNTRY_ONLY"
                : pack == null ? "SOURCE_PACK_MISSING" : cached ? "CACHED"
                : "PROVINCE".equals(precision) ? "PROVINCE_FALLBACK" : "VERIFIED";
        Instant expires = latest.observedAt().plus(Duration.ofHours(24));
        if (cached && evidence.observedAt().plus(Duration.ofDays(7)).isBefore(expires)) expires = evidence.observedAt().plus(Duration.ofDays(7));
        RegionDetails details = new RegionDetails(freeze ? UUID.randomUUID() : null, precision, geo.countryCode(), geo.subdivisionCode(),
                "CITY".equals(precision) ? geo.cityName() : null, geo.accuracyRadiusKm(), evidence.provider(), evidence.providerVersion(),
                latest.networkMode(), latest.observedAt(), expires, cached,
                pack == null ? null : pack.label(), pack == null ? null : pack.version(), pack == null ? List.of() : pack.sourceIds());
        return new RegionSnapshot(status, code, latest.nodeId(), evidence.observedAt(), details);
    }

    private RegionSnapshot unavailable(String status, RegionObservation report, boolean freeze) {
        return new RegionSnapshot(status, null, report.nodeId(), null, new RegionDetails(freeze ? UUID.randomUUID() : null,
                "UNKNOWN", null, null, null, null, report.provider(), report.providerVersion(), report.networkMode(),
                report.observedAt(), report.observedAt().plus(Duration.ofHours(24)), false, null, null, List.of()));
    }
}
