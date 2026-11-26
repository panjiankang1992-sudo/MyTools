package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.model.ConnectivityModels.Bootstrap;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.Challenge;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.ChallengeRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
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
        assertThat(bootstrap.serviceType()).isEqualTo("_mytools-gateway._tcp.local");
        assertThat(bootstrap.lanPort()).isEqualTo(23201);
    }

    @Test
    void shouldRejectUnknownProbe() {
        ConnectivityService service = new ConnectivityService("instance-one", "v1");
        ChallengeRequest request = new ChallengeRequest("0".repeat(32), "abcdefghijklmnop");

        assertThatThrownBy(() -> service.challenge(request))
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    @Test
    void shouldRejectProbeIssuedByAnotherGatewayProcess() {
        ConnectivityService publicGateway = new ConnectivityService("instance-one", "v1");
        ConnectivityService legacyGateway = new ConnectivityService("instance-one", "v1");
        Bootstrap bootstrap = publicGateway.issue(7L);

        // 即使实例名称相同，也必须由签发材料的同一个进程响应挑战。
        assertThatThrownBy(() -> legacyGateway.challenge(new ChallengeRequest(
                bootstrap.probeId(), "abcdefghijklmnop")))
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    @Test
    void shouldAdvertiseOnlyRfc1918Ipv4Addresses() throws Exception {
        for (String address : new String[]{"10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.8.9"}) {
            assertThat(ConnectivityService.isLanAddress(InetAddress.getByName(address)))
                    .as(address).isTrue();
        }
        for (String address : new String[]{"8.8.8.8", "203.0.113.3", "127.0.0.1", "169.254.1.2",
                "172.15.255.255", "172.32.0.1", "192.169.0.1", "0.0.0.0", "::1", "fc00::1"}) {
            assertThat(ConnectivityService.isLanAddress(InetAddress.getByName(address)))
                    .as(address).isFalse();
        }
    }
    @Test
    void shouldPublishOnlyExplicitlyConfiguredHttpsOrigins() {
        ConnectivityService service = new ConnectivityService("instance-one", "v1");
        assertThat(service.issue(7L).ipv6DirectOrigins()).isEmpty();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredDirectOrigins",
                "https://direct6.example.test,https://direct6.example.test:8443");
        service.validateDirectOrigins();
        Bootstrap bootstrap = service.issue(7L);
        assertThat(bootstrap.ipv6DirectOrigins()).containsExactly(
                "https://direct6.example.test", "https://direct6.example.test:8443");
        assertThat(bootstrap.directConfigExpiresAt()).isGreaterThan(bootstrap.expiresAt());
    }

    @Test
    void shouldRejectUnsafeDirectOriginConfiguration() {
        for (String origin : new String[]{"http://direct6.example.test", "https://127.0.0.1",
                "https://direct6.example.test/api", "https://user@direct6.example.test",
                "https://direct6.example.test?next=evil", "https://direct6.example.test:0",
                "https://direct6.example.test:65536", "https://[::1]"}) {
            ConnectivityService service = new ConnectivityService("instance-one", "v1");
            org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredDirectOrigins", origin);
            assertThatThrownBy(service::validateDirectOrigins).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
