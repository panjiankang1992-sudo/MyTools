package com.yuyutian.mytools.connectivity.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.connectivity.model.LanBootstrapResponse;
import com.yuyutian.mytools.connectivity.model.LanChallengeRequest;
import com.yuyutian.mytools.connectivity.model.LanChallengeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanVerificationServiceTest {

    private LanVerificationService service;

    @BeforeEach
    void setUp() {
        service = new LanVerificationService();
        ReflectionTestUtils.setField(service, "instanceId", "instance-a");
        ReflectionTestUtils.setField(service, "apiVersion", "v1");
    }

    @Test
    void shouldCreateProofVerifiableWithBootstrapKey() throws Exception {
        LanBootstrapResponse bootstrap = service.issue(7L);
        String nonce = "abcdefghijklmnop";

        LanChallengeResponse response = service.challenge(new LanChallengeRequest(bootstrap.probeId(), nonce));

        byte[] proofKey = Base64.getUrlDecoder().decode(bootstrap.proofKey());
        String message = "instance-a\n" + nonce + "\nv1\n" + bootstrap.expiresAt();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(proofKey, "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        assertThat(response.proof()).isEqualTo(expected);
        assertThat(response.instanceId()).isEqualTo(bootstrap.instanceId());
        assertThat(response.nonce()).isEqualTo(nonce);
    }

    @Test
    void shouldRejectUnknownProbeWithoutAuthenticationData() {
        LanChallengeRequest request = new LanChallengeRequest("a".repeat(32), "abcdefghijklmnop");

        assertThatThrownBy(() -> service.challenge(request)).isInstanceOf(BusinessException.class);
    }
}
