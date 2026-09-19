package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationConsentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;

/** 只发布虚构合约文案并调用真实同意事务，不包含真实留存承诺或 Provider 凭据。 */
public final class AdaptationConsentFixtures {
    private AdaptationConsentFixtures() { }

    /** 供 Reader 集成夹具替换早期未经审计的手写同意行。 */
    public static void installAndAccept(JdbcTemplate jdbc, String deployment, String version, String rights, String contract, Clock clock) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var payloads = new AdaptationDisclosurePayload(mapper);
        var payload = payload(contract);
        jdbc.update("INSERT INTO reader_adaptation_provider_disclosure VALUES (?, ?, ?, ?, TRUE, ?)",
                version.getBytes(StandardCharsets.UTF_8), payloads.sha256(payload), rights.getBytes(StandardCharsets.UTF_8),
                payloads.canonical(payload), Timestamp.from(clock.instant()));
        var repository = new AdaptationConsentRepository(jdbc,
                new ReaderAdaptationProperties(true, false, deployment, version, "fixture", "fixture", 2), mapper,
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())), clock);
        repository.accept(41, new AdaptationConsentModels.Accept(version, payloads.sha256(payload), true, true,
                repository.features(41).consentRevision()));
    }

    /** 所有外部处理说明明确标注为测试内容。 */
    public static AdaptationConsentModels.Payload payload(String contract) {
        return new AdaptationConsentModels.Payload("adaptation-disclosure-v1", "fixture", contract, "Fixture provider",
                "https://api.sillytraven.dev", "Fixture only: target, neighboring excerpts, intent and generated candidates.",
                "Fixture only: no real processing location or retention commitment.", "Fixture only: confirm authority to submit these test materials.");
    }
}
