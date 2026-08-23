package com.yuyutian.mytools.auth.identity;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/** Identity 令牌校验客户端。 */
@Component
public class IdentityValidationGateway {
 private final RestTemplate restTemplate; private final IdentityValidationProperties properties;
 /** 创建客户端。 @param restTemplate HTTP 客户端 @param properties 配置 */ public IdentityValidationGateway(RestTemplate restTemplate,IdentityValidationProperties properties){this.restTemplate=restTemplate;this.properties=properties;}
 /** 远程校验令牌。 @param token 访问令牌 @return 主体 */ public Principal validate(String token){
  if(properties.getInternalToken().isBlank())throw new IllegalStateException("Identity internal token is missing");
  HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(properties.getInternalToken());headers.setContentType(MediaType.APPLICATION_JSON);
  ResponseEntity<Principal> response=restTemplate.exchange(normalized()+"/internal/v1/identity/tokens/validate",HttpMethod.POST,new HttpEntity<>(Map.of("accessToken",token),headers),Principal.class);
  Principal principal=response.getBody();if(!response.getStatusCode().is2xxSuccessful()||principal==null)throw new IllegalStateException("Identity validation response is invalid");return principal;
 }
 private String normalized(){String value=properties.getServiceUrl();return value.endsWith("/")?value.substring(0,value.length()-1):value;}
 /** 远程身份主体。 */ public record Principal(boolean active,long userId,String username,List<String> roles,UUID sessionId,Instant expiresAt) { }
}
