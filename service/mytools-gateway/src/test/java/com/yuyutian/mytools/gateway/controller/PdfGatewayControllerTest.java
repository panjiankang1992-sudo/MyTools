package com.yuyutian.mytools.gateway.controller;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class PdfGatewayControllerTest {
 @Test void fixedRoutesRejectInternalAndTraversal(){
  assertFalse(PdfGatewayController.allowed("POST","/work/00000000-0000-0000-0000-000000000000/claim"));
  assertFalse(PdfGatewayController.allowed("GET","/../../actuator/env"));
  assertFalse(PdfGatewayController.allowed("DELETE","/jobs"));
  assertTrue(PdfGatewayController.allowed("GET","/history"));
 }
 @Test void injectsVerifiedOwnerAndDiscardsForgedIdentity()throws Exception{
  HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  AtomicReference<String> owner=new AtomicReference<>();AtomicReference<String> token=new AtomicReference<>();
  upstream.createContext("/internal/v1/pdf/capabilities",exchange->{owner.set(exchange.getRequestHeaders().getFirst("X-Owner-Id"));token.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"operations\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});upstream.start();
  try{
   var env=new MockEnvironment().withProperty("PDF_ROUTE_ENABLED","true").withProperty("PDF_INTERNAL_TOKEN","test-internal").withProperty("PDF_SERVICE_URL","http://127.0.0.1:"+upstream.getAddress().getPort());
   var controller=new PdfGatewayController(env);var request=new MockHttpServletRequest("GET","/api/app/v1/pdf/capabilities");request.addHeader("X-Owner-Id","999");request.addHeader("Authorization","Bearer forged");
   var unauthorized=new MockHttpServletResponse();controller.forward(request,unauthorized);assertEquals(401,unauthorized.getStatus());
   request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(7,"test",List.of(),UUID.randomUUID()));
   var response=new MockHttpServletResponse();controller.forward(request,response);assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("\"code\":\"0000\""));assertTrue(response.getContentAsString().contains("\"data\""));assertEquals("7",owner.get());assertEquals("Bearer test-internal",token.get());
  }finally{upstream.stop(0);}
 }
}
