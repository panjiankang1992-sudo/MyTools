package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.RefreshIndexRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DriveGatewayClientTest {
    @Test
    void shouldSendOnlyBoundOwnerAndEncodedParentPath() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID accountId = UUID.randomUUID();
        server.expect(requestTo("http://drive/internal/v1/drive/accounts/" + accountId
                        + "/items?ownerId=55&parentPath=books%20%26%202026"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer drive-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess("[]", org.springframework.http.MediaType.APPLICATION_JSON));
        DriveGatewayClient client = new DriveGatewayClient(restTemplate, properties());

        assertThat(client.items(accountId, 55L, "books & 2026", "correlation")).isEmpty();
        server.verify();
    }

    @Test
    void shouldReturnSanitizedOwnerBoundAccounts() {
        RestTemplate restTemplate=new RestTemplate();
        MockRestServiceServer server=MockRestServiceServer.bindTo(restTemplate).build();
        UUID accountId=UUID.randomUUID();
        server.expect(requestTo("http://drive/internal/v1/drive/accounts?ownerId=55"))
            .andExpect(method(GET)).andExpect(header("Authorization","Bearer drive-token"))
            .andRespond(withSuccess("[{\"id\":\""+accountId+"\",\"ownerId\":55,\"externalAccountId\":\"private\","
                +"\"displayName\":\"Primary\",\"providerType\":\"RCLONE\",\"remoteKey\":\"secret_remote\","
                +"\"readOnly\":true,\"enabled\":true,\"indexGeneration\":2}]",
                org.springframework.http.MediaType.APPLICATION_JSON));

        var accounts=new DriveGatewayClient(restTemplate,properties()).accounts(55L,"correlation");

        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().displayName()).isEqualTo("Primary");
        assertThat(accounts.getFirst().toString()).doesNotContain("private","secret_remote");
        server.verify();
    }

    @Test
    void shouldCreateOwnerBoundRefreshOperation() {
        RestTemplate restTemplate=new RestTemplate();
        MockRestServiceServer server=MockRestServiceServer.bindTo(restTemplate).build();
        UUID accountId=UUID.randomUUID(); UUID operationId=UUID.randomUUID(); UUID taskId=UUID.randomUUID();
        server.expect(requestTo("http://drive/internal/v1/drive/accounts/"+accountId+"/refresh-index?ownerId=55"))
            .andExpect(method(POST)).andExpect(header("Authorization","Bearer drive-token"))
            .andRespond(withSuccess("{\"id\":\""+operationId+"\",\"accountId\":\""+accountId
                +"\",\"taskInstanceId\":\""+taskId+"\",\"operationType\":\"INDEX_ACCOUNT\",\"status\":\"PENDING\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));

        var result=new DriveGatewayClient(restTemplate,properties()).refreshIndex(accountId,55L,
            new RefreshIndexRequest("refresh-1"),"correlation");

        assertThat(result.id()).isEqualTo(operationId); server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                true, Set.of(55L), false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "");
    }
}
