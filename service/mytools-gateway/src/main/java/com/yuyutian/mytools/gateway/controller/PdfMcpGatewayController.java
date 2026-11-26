package com.yuyutian.mytools.gateway.controller;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.net.*;
import java.util.*;
/** 将 PDF MCP 的独立认证协议转发到固定本机服务。 */
@RestController
@RequestMapping("/api/app/v1/pdf-mcp")
public class PdfMcpGatewayController {
 private final Environment env;
 /** 注入受控地址与开关。 */
 public PdfMcpGatewayController(Environment env){this.env=env;}
 /** 保留 MCP 协议头；二进制路径与协议路径分别执行白名单检查。 */
 @RequestMapping(value={"","/**"},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
 public void forward(HttpServletRequest request,HttpServletResponse response)throws IOException{
  if(!env.getProperty("PDF_MCP_ROUTE_ENABLED",Boolean.class,false)){response.setStatus(503);return;}
  String suffix=request.getRequestURI().substring("/api/app/v1/pdf-mcp".length());
  String id="[0-9a-fA-F-]{36}";
  boolean protocol=suffix.isEmpty()||suffix.equals("/");
  boolean upload=request.getMethod().equals("PUT")&&suffix.matches("/files/uploads/"+id+"/content");
  boolean download=request.getMethod().equals("GET")&&suffix.matches("/files/jobs/"+id+"/artifacts/[0-9]{1,3}/content");
  if(!protocol&&!upload&&!download){response.setStatus(404);return;}
  String auth=request.getHeader("Authorization");if(auth==null||!auth.startsWith("Bearer ")||auth.length()>512){response.setStatus(401);return;}
  HttpURLConnection connection=(HttpURLConnection)URI.create(env.getProperty("PDF_MCP_URL","http://127.0.0.1:23343")+(protocol?"/mcp":suffix)).toURL().openConnection();
  connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(120000);connection.setRequestMethod(request.getMethod());
  for(String header:List.of("Authorization","Accept","Content-Type","MCP-Protocol-Version","Mcp-Session-Id","Last-Event-ID","X-Upload-Offset","Origin")){
   String value=request.getHeader(header);if(value!=null&&value.length()<=1024)connection.setRequestProperty(header,value);
  }
  try{
   if(Set.of("POST","PUT").contains(request.getMethod())){
    if(request.getContentLengthLong()>1048576){response.setStatus(413);return;}
    connection.setDoOutput(true);connection.setChunkedStreamingMode(65536);
    try(OutputStream output=connection.getOutputStream()){long total=0;byte[] chunk=new byte[65536];int count;
     while((count=request.getInputStream().read(chunk))!=-1){total+=count;if(total>1048576){response.setStatus(413);return;}output.write(chunk,0,count);}
    }
   }
   int status=connection.getResponseCode();response.setStatus(status);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
   for(String header:List.of("Content-Type","Content-Length","Content-Disposition","Mcp-Session-Id","WWW-Authenticate")){String value=connection.getHeaderField(header);if(value!=null)response.setHeader(header,value);}
   InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();if(input!=null)try(input){input.transferTo(response.getOutputStream());}
  }catch(IOException ignored){if(!response.isCommitted()){response.reset();response.setStatus(503);}}finally{connection.disconnect();}
 }
}
