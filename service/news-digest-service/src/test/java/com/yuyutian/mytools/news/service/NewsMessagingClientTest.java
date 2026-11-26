package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NewsMessagingClientTest {
    private final Instant now=Instant.parse("2026-10-18T00:00:00Z");
    private final Batch batch=new Batch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),55,1,UUID.randomUUID(),Channel.EMAIL,RenderMode.FULL,UUID.randomUUID(),"QUEUED",false,now.plusSeconds(3600),now,null);
    private final Content content=new Content("Subject","Text",null);
    private final Dispatch part=new Dispatch(UUID.randomUUID(),batch.id(),1,1,content,NewsDeliveryRenderer.hash(content),"SUBMITTING",null,1,null,false,1);
    @Test void creationAndCancellationUseSameFrozenIdentityWithIsoExpiry() {
        var builder=RestClient.builder().baseUrl("http://messaging");var server=MockRestServiceServer.bindTo(builder).build();var client=new NewsMessagingClient(builder.build());
        for(boolean cancel:new boolean[]{false,true}){
            server.expect(requestTo("http://messaging/internal/v2/messaging/deliveries"+(cancel?"/cancel-by-key":"")))
                    .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"news-part:"+part.id()+"\",\"destinationId\":\""+batch.destinationId()+"\",\"content\":{\"subject\":\"Subject\",\"text\":\"Text\",\"html\":null},\"expiresAt\":\"2026-10-18T01:00:00Z\",\"payloadHash\":\""+part.payloadHash()+"\"}"))
                    .andRespond(withSuccess(receipt(cancel?"CANCELLED":"ACCEPTED",1),MediaType.APPLICATION_JSON));
            assertThat(client.submit(batch,part,cancel).status()).isEqualTo(cancel?"CANCELLED":"ACCEPTED");server.verify();server.reset();
        }
    }
    @Test void mismatchedDestinationHashOrGenerationIsRejected() {
        for(String response:new String[]{receipt("ACCEPTED",1).replace(batch.destinationId().toString(),UUID.randomUUID().toString()),receipt("ACCEPTED",1).replace(part.payloadHash(),"wrong"),receipt("ACCEPTED",0)}){
            var builder=RestClient.builder().baseUrl("http://messaging");var server=MockRestServiceServer.bindTo(builder).build();var client=new NewsMessagingClient(builder.build());
            server.expect(requestTo("http://messaging/internal/v2/messaging/deliveries")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->client.submit(batch,part,false)).isInstanceOf(NewsMessagingClient.Failure.class)
                    .extracting(e->((NewsMessagingClient.Failure)e).permanent()).isEqualTo(true);
        }
    }
    @Test void retryPreservesOriginalExpectedGenerationAndExplicitRisk() {
        var builder=RestClient.builder().baseUrl("http://messaging");var server=MockRestServiceServer.bindTo(builder).build();var client=new NewsMessagingClient(builder.build());
        Dispatch retry=new Dispatch(part.id(),batch.id(),1,1,content,part.payloadHash(),"UNKNOWN",part.id(),1,1,true,1);
        server.expect(requestTo("http://messaging/internal/v2/messaging/deliveries/"+part.id()+"/retry?ownerId=55"))
                .andExpect(content().json("{\"expectedGeneration\":1,\"acknowledgeDuplicateRisk\":true}"))
                .andRespond(withSuccess(receipt("ACCEPTED",2),MediaType.APPLICATION_JSON));
        assertThat(client.retry(batch,retry).generation()).isEqualTo(2);server.verify();
    }
    @Test void temporaryAndPermanentFailuresNeverExposeProviderResponse() {
        for(HttpStatus code:new HttpStatus[]{HttpStatus.SERVICE_UNAVAILABLE,HttpStatus.TOO_MANY_REQUESTS,HttpStatus.UNAUTHORIZED}){
            var builder=RestClient.builder().baseUrl("http://messaging");var server=MockRestServiceServer.bindTo(builder).build();var client=new NewsMessagingClient(builder.build());
            server.expect(requestTo("http://messaging/internal/v2/messaging/deliveries")).andRespond(withStatus(code).body("private-provider-response"));
            assertThatThrownBy(()->client.submit(batch,part,false)).isInstanceOf(NewsMessagingClient.Failure.class).hasMessageNotContaining("private-provider-response")
                    .extracting(e->((NewsMessagingClient.Failure)e).permanent()).isEqualTo(code==HttpStatus.UNAUTHORIZED);
        }
    }
    private String receipt(String state,int generation){return "{\"id\":\""+part.id()+"\",\"destinationId\":\""+batch.destinationId()+"\",\"channel\":\"EMAIL\",\"payloadHash\":\""+part.payloadHash()+"\",\"status\":\""+state+"\",\"generation\":"+generation+",\"errorCode\":null,\"expiresAt\":\"2026-10-18T01:00:00Z\",\"updatedAt\":\"2026-10-18T00:00:00Z\"}";}
}
