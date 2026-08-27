package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.model.ConnectivityModels.Bootstrap;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.Challenge;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.ChallengeRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Connectivity 临时证明协议测试。
 */
class ConnectivityServiceTest {
    @Test
    void shouldIssueVerifiableChallenge() throws Exception {
        ConnectivityService service = new ConnectivityService("instance-one", "v1");
        Bootstrap bootstrap = service.issue(7L);
        Challenge challenge = service.challenge(new ChallengeRequest(
                bootstrap.probeId(), "abcdefghijklmnop"));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(bootstrap.proofKey()), "HmacSHA256"));
        String message = "instance-one\nabcdefghijklmnop\nv1\n" + bootstrap.expiresAt();

        assertThat(challenge.proof()).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
        assertThat(bootstrap.serviceType()).isEqualTo("_mytools._tcp.local");
    }

    @Test
    void shouldRejectUnknownProbe() {
        ConnectivityService service = new ConnectivityService("instance-one", "v1");
        ChallengeRequest request = new ChallengeRequest("0".repeat(32), "abcdefghijklmnop");

        assertThatThrownBy(() -> service.challenge(request))
                .isInstanceOf(GatewayUnauthorizedException.class);
    }
}
