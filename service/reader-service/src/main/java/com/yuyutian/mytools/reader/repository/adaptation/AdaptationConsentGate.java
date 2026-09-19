package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationDisclosurePayload;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在业务事务中锁定授权修订、告知、同意、部署；创建与发送复用相同校验。 */
final class AdaptationConsentGate {
    private AdaptationConsentGate() { }

    static long require(JdbcTemplate jdbc, ObjectMapper mapper, long owner, Object disclosureVersion,
                        Object deploymentId, Long expectedRevision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw invalid();
        var states = jdbc.queryForList("SELECT revision FROM reader_adaptation_consent_state WHERE owner_id = ? FOR UPDATE", owner);
        long revision = states.isEmpty() ? 0 : ((Number) states.getFirst().get("revision")).longValue();
        if (revision == 0) throw invalid();
        if (expectedRevision != null && expectedRevision != revision) throw invalid();
        var events = jdbc.queryForList("SELECT disclosure_version FROM reader_adaptation_consent_event WHERE owner_id = ? AND revision = ? AND operation_kind = 'ACCEPT'", owner, revision);
        if (events.size() != 1 || !java.util.Arrays.equals((byte[]) events.getFirst().get("disclosure_version"), (byte[]) disclosureVersion)) throw invalid();
        var disclosures = jdbc.queryForList("SELECT * FROM reader_adaptation_provider_disclosure WHERE version = ? FOR UPDATE", disclosureVersion);
        var consents = jdbc.queryForList("SELECT * FROM reader_adaptation_provider_consent WHERE owner_id = ? AND disclosure_version = ? FOR UPDATE", owner, disclosureVersion);
        if (disclosures.size() != 1 || consents.size() != 1 || !Boolean.TRUE.equals(disclosures.getFirst().get("enabled"))
                || consents.getFirst().get("revoked_at") != null) throw invalid();
        var disclosure = disclosures.getFirst(); var consent = consents.getFirst();
        if (!disclosure.get("disclosure_sha256").equals(consent.get("disclosure_sha256"))
                || !java.util.Arrays.equals((byte[]) disclosure.get("rights_attestation_version"), (byte[]) consent.get("rights_attestation_version"))) throw invalid();
        var deployments = jdbc.queryForList("SELECT * FROM novel_adaptation_provider_deployment WHERE id = ? FOR UPDATE", deploymentId);
        if (deployments.size() != 1 || !Boolean.TRUE.equals(deployments.getFirst().get("enabled")))
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
        var deployment = deployments.getFirst();
        new AdaptationDisclosurePayload(mapper).parse(disclosure.get("payload_json"), (String) disclosure.get("disclosure_sha256"),
                (String) deployment.get("provider_code"), (String) deployment.get("contract_sha256"));
        return revision;
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED); }
}
