package com.yuyutian.mytools.gateway.controller;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.*;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ImageGenerationGatewayControllerTest {
 MockEnvironment env(){return new MockEnvironment().withProperty("IMAGE_GENERATION_ROUTE_ENABLED","true").withProperty("IMAGE_GENERATION_INTERNAL_TOKEN","test-token").withProperty("IMAGE_GENERATION_URL","http://image");}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID()));r.addHeader("X-Owner-Id","999");return r;}
 @Test void refusesMissingTrustedPrincipal(){var controller=new ImageGenerationGatewayController(env());assertThrows(GatewayUnauthorizedException.class,()->controller.models(new MockHttpServletRequest()));}
 @Test void routeDisabledDoesNotCallUpstream(){var e=env().withProperty("IMAGE_GENERATION_ROUTE_ENABLED","false");assertEquals(503,new ImageGenerationGatewayController(e).models(request()).getStatusCode().value());}
 @Test void clientOwnerHeaderCannotSpoofOwner(){var controller=new ImageGenerationGatewayController(env());var server=MockRestServiceServer.bindTo((RestTemplate)Objects.requireNonNull(ReflectionTestUtils.getField(controller,"client"))).build();server.expect(requestTo("http://image/internal/v1/images/models")).andExpect(header("Authorization","Bearer test-token")).andExpect(header("X-Owner-Id","42")).andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));assertEquals(200,controller.models(request()).getStatusCode().value());server.verify();}
 @Test void rawImageBypassesJsonEnvelope()throws Exception {
  var controller=new ImageGenerationGatewayController(env());String id=UUID.randomUUID().toString();byte[] bytes=new byte[]{(byte)0x89,80,78,71};
  var server=MockRestServiceServer.bindTo((RestTemplate)Objects.requireNonNull(ReflectionTestUtils.getField(controller,"client"))).build();server.expect(requestTo("http://image/internal/v1/images/jobs/"+id+"/images/0")).andRespond(withSuccess(bytes,MediaType.IMAGE_PNG));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build().perform(get("/api/app/v1/image-generation/jobs/"+id+"/images/0").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID()))).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(bytes)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));server.verify();
 }
 @Test void styleCrudUsesFixedRoutesAndTrustedOwner()throws Exception {
  var controller=new ImageGenerationGatewayController(env());UUID id=UUID.randomUUID();
  var server=MockRestServiceServer.bindTo((RestTemplate)Objects.requireNonNull(ReflectionTestUtils.getField(controller,"client"))).build();
  var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"name\":\"Ink\",\"promptTemplate\":\"{prompt}, ink\"}");
  for(var method:List.of(org.springframework.http.HttpMethod.GET,org.springframework.http.HttpMethod.POST,org.springframework.http.HttpMethod.PUT,org.springframework.http.HttpMethod.DELETE)) {
   server.expect(requestTo("http://image/internal/v1/images/styles"+(method==org.springframework.http.HttpMethod.GET?"":"/"+id)))
    .andExpect(method(method)).andExpect(header("X-Owner-Id","42")).andExpect(header("Authorization","Bearer test-token"))
    .andRespond(withSuccess(method==org.springframework.http.HttpMethod.GET?"[]":"{}",MediaType.APPLICATION_JSON));
  }
  assertEquals(200,controller.styles(request()).getStatusCode().value());
  assertEquals(200,controller.createStyle(request(),id,body).getStatusCode().value());
  assertEquals(200,controller.updateStyle(request(),id,body).getStatusCode().value());
  assertEquals(200,controller.deleteStyle(request(),id).getStatusCode().value());server.verify();
 }

}
