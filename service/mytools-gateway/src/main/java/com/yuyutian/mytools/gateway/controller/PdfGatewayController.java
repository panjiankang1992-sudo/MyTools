package com.yuyutian.mytools.gateway.controller;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.net.*;
import java.util.*;
/** PDF 固定路由代理：流式请求、可信身份和独立开关。 */
@RestController
@RequestMapping("/api/app/v1/pdf")
public class PdfGatewayController {
 private final Environment env;
 private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 /** 注入受控后端地址与凭据。 */
 public PdfGatewayController(Environment env){this.env=env;}
 /** 代理白名单方法和路径，拒绝内部 Worker 路由和客户端身份头。 */
 @RequestMapping(value={"/**",""},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT})
 public void forward(HttpServletRequest request,HttpServletResponse response)throws IOException {
  if(!(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE) instanceof GatewayPrincipal principal)){error(response,401,"PDF_014");return;}
  if(!env.getProperty("PDF_ROUTE_ENABLED",Boolean.class,false)||env.getProperty("PDF_INTERNAL_TOKEN","").isBlank()){error(response,503,"PDF_009");return;}
  String allow=env.getProperty("PDF_OWNER_ALLOWLIST","");if(!allow.isBlank()&&!Set.of(allow.split(",")).contains(Long.toString(principal.userId()))){error(response,503,"PDF_009");return;}
  String path=request.getRequestURI().substring("/api/app/v1/pdf".length());
  if(!allowed(request.getMethod(),path)){error(response,404,"PDF_015");return;}
  String query="";
  if((path.equals("/jobs")||path.equals("/history"))&&request.getMethod().equals("GET")){String page=request.getParameter("page");if(page!=null){if(!page.matches("[0-9]{1,5}")){error(response,422,"PDF_001");return;}query="?page="+page;}}
  long limit=request.getMethod().equals("PUT")?1048576:1048576;
  if(request.getContentLengthLong()>limit){error(response,413,"PDF_003");return;}
  HttpURLConnection connection=(HttpURLConnection)URI.create(env.getProperty("PDF_SERVICE_URL","http://127.0.0.1:23342")+"/internal/v1/pdf"+path+query).toURL().openConnection();
  connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(60000);connection.setRequestMethod(request.getMethod());
  connection.setRequestProperty("Authorization","Bearer "+env.getProperty("PDF_INTERNAL_TOKEN"));connection.setRequestProperty("X-Owner-Id",Long.toString(principal.userId()));
  connection.setRequestProperty("Content-Type",request.getMethod().equals("PUT")?"application/octet-stream":"application/json");connection.setRequestProperty("Accept-Encoding","identity");
  for(String header:List.of("Idempotency-Key","X-Upload-Offset")){String value=request.getHeader(header);if(value!=null){if(value.length()>128||value.contains("\r")||value.contains("\n")){error(response,422,"PDF_001");connection.disconnect();return;}connection.setRequestProperty(header,value);}}
  try{
   if(!request.getMethod().equals("GET")){connection.setDoOutput(true);connection.setChunkedStreamingMode(65536);try(var out=connection.getOutputStream()){long total=0;byte[] buffer=new byte[65536];int count;while((count=request.getInputStream().read(buffer))!=-1){total+=count;if(total>limit){error(response,413,"PDF_003");return;}out.write(buffer,0,count);}}}
   int status=connection.getResponseCode();response.setStatus(status);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
   for(String header:List.of("Content-Type","Content-Length","Content-Disposition")){String value=connection.getHeaderField(header);if(value!=null)response.setHeader(header,value);}
   InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();if(input!=null)try(input){
    // 直接流式代理不会经过 MVC 的信封转换器，JSON 在这里显式适配 APP 协议。
    if(request.getMethod().equals("PUT")||(!path.endsWith("/thumbnail")&&!path.endsWith("/content"))){
     byte[] raw=input.readNBytes(1048577);if(raw.length>1048576){error(response,502,"PDF_010");return;}
     byte[] body=status>=200&&status<300?json.writeValueAsBytes(Map.of("code","0000","message","OK","data",json.readTree(raw))):raw;
     response.setContentLength(body.length);response.setContentType("application/json");response.getOutputStream().write(body);
    }else input.transferTo(response.getOutputStream());
   }
  }catch(IOException e){if(!response.isCommitted())error(response,503,"PDF_009");}finally{connection.disconnect();}
 }
 /** 校验唯一开放的领域路径，不允许路径穿越或 Worker 接口访问。 */
 public static boolean allowed(String method,String path){String id="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";return switch(method){
  case "GET" -> path.equals("/capabilities")||path.equals("/jobs")||path.equals("/history")||path.matches("/(inputs|plans|jobs)/"+id)||path.matches("/inputs/"+id+"/thumbnail")||path.matches("/plans/"+id+"/pages/[0-9]{1,3}/thumbnail")||path.matches("/jobs/"+id+"/artifacts/[0-9]{1,3}/content");
  case "POST" -> Set.of("/uploads","/plans","/jobs").contains(path)||path.matches("/uploads/"+id+"/complete")||path.matches("/(plans|jobs)/"+id+"/cancel");
  case "PUT" -> path.matches("/uploads/"+id+"/content");default -> false;};}
 private void error(HttpServletResponse response,int status,String code)throws IOException{response.reset();response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+code+"\"}");}
}
